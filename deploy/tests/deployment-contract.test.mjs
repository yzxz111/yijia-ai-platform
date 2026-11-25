import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDirectory, '..', '..');

function loadComposeConfig() {
  const composeFile = join(projectRoot, 'deploy', 'compose.yaml');
  const envFile = join(projectRoot, 'deploy', 'env', '.env.example');
  const result = spawnSync(
    'docker',
    ['compose', '--env-file', envFile, '-f', composeFile, 'config', '--format', 'json'],
    {
      cwd: projectRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        DOCUMENT_CONVERTER_AUTH_TOKEN: 'deployment-contract-test-token',
      },
    },
  );

  assert.equal(
    result.status,
    0,
    `docker compose config failed:\n${result.stderr || result.error || 'unknown error'}`,
  );
  return JSON.parse(result.stdout);
}

// 以真实部署的方式渲染基础 Compose 文件：只提供服务栈真正必需的值，不开启任何可选开关。
// 正是这次渲染决定了「忘记设置某个变量」会不会悄悄重新打开一扇开发后门。
function loadComposeConfigWithoutOptionalOptIns() {
  const composeFile = join(projectRoot, 'deploy', 'compose.yaml');
  const env = {
    ...process.env,
    SECURITY_INTERNAL_HMAC_SECRET: 'contract-test-internal-hmac',
    SECURITY_API_KEY_PEPPER: 'contract-test-api-key-pepper',
    GATEWAY_SECRETS_ENCRYPTION_KEY: 'contract-test-encryption-key-32-bytes',
    DOCUMENT_CONVERTER_AUTH_TOKEN: 'deployment-contract-test-token',
    LOCAL_ADMIN_PASSWORD: 'contract-test-admin-password',
    LOGIN_RISK_PEPPER: 'contract-test-login-risk-pepper-32-bytes',
    PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY: 'contract-test-password-login-hmac-32-bytes',
  };
  // 继承来的值会让这一步失去意义，而项目目录里并不存在 .env，
  // 所以从进程环境中删除这些变量确实能让它们处于未设置状态。
  for (const key of [
    'PAYMENT_MOCK_ENABLED',
    'PAYMENT_MOCK_CALLBACK_SECRET',
    'SPRING_PROFILES_ACTIVE',
    'PAYMENT_WECHAT_PAY_ENABLED',
    'PAYMENT_ALIPAY_ENABLED',
  ]) {
    delete env[key];
  }

  const result = spawnSync(
    'docker',
    ['compose', '-f', composeFile, 'config', '--format', 'json'],
    { cwd: projectRoot, encoding: 'utf8', env },
  );

  assert.equal(
    result.status,
    0,
    `docker compose config (no optional opt-ins) failed:\n${result.stderr || result.error || 'unknown error'}`,
  );
  return JSON.parse(result.stdout);
}

function readExampleEnvironment() {
  const source = readFileSync(join(projectRoot, 'deploy', 'env', '.env.example'), 'utf8');
  return new Map(source
    .split(/\r?\n/u)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const separator = line.indexOf('=');
      return separator < 0 ? [line, ''] : [line.slice(0, separator), line.slice(separator + 1)];
    }));
}

function locationBody(nginxConfig, location) {
  const escaped = location.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
  const match = nginxConfig.match(new RegExp(`location\\s+=\\s+${escaped}\\s*\\{([\\s\\S]*?)\\}`, 'u'));
  assert.ok(match, `expected an exact Nginx location for ${location}`);
  return match[1];
}

for (const service of ['platform', 'gateway']) {
  test(`HTTP health probes forward a valid Host and stay loopback-only: ${service}`, () => {
    const config = readFileSync(join(projectRoot, 'deploy', 'nginx', 'nginx.https.conf'), 'utf8');
    const httpStart = config.indexOf('listen 8080;');
    const httpsStart = config.indexOf('listen 8443 ssl;');
    assert.ok(httpStart >= 0 && httpsStart > httpStart, 'expected separate HTTP and HTTPS servers');
    const body = locationBody(config.slice(httpStart, httpsStart), `/health/${service}`);

    // nginx 默认把 Host 设为上游服务名，其中的下划线会被 Tomcat 拒绝。
    assert.match(body, /proxy_set_header\s+Host\s+\$host\s*;/u);
    assert.match(body, /allow\s+127\.0\.0\.1\s*;/u);
    assert.match(body, /deny\s+all\s*;/u);
    assert.match(body, new RegExp(`proxy_pass\\s+http://${service}_backend/actuator/health/readiness\\s*;`, 'u'));
  });
}

test('third-party container images are pinned to immutable manifest digests', () => {
  const compose = loadComposeConfig();
  for (const [serviceName, service] of Object.entries(compose.services)) {
    const image = String(service.image ?? '');
    if (!image || image.startsWith('yuzhihuihe/')) continue;
    assert.match(
      image,
      /:[^@\s]+@sha256:[a-f0-9]{64}$/u,
      `${serviceName} must use a readable tag plus immutable digest`,
    );
  }

  for (const relativePath of [
    'gateway-service/Dockerfile',
    'platform-service/Dockerfile',
    'web-console/Dockerfile',
    'document-converter/Dockerfile',
  ]) {
    const source = readFileSync(join(projectRoot, relativePath), 'utf8');
    const stages = new Set();
    const fromEntries = [...source.matchAll(
      /^FROM\s+([^\s]+)(?:\s+AS\s+(\S+))?$/gimu,
    )];
    assert.ok(fromEntries.length > 0, `${relativePath} must contain a base image`);
    for (const [, image, alias] of fromEntries) {
      if (stages.has(image.toLowerCase())) {
        if (alias) stages.add(alias.toLowerCase());
        continue;
      }
      assert.match(
        image,
        /:[^@\s]+@sha256:[a-f0-9]{64}$/u,
        `${relativePath} base image must use a readable tag plus immutable digest`,
      );
      if (alias) stages.add(alias.toLowerCase());
    }
  }
});

test('multimodal E2E accepts the active Compose environment file', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'tests', 'multimodal-e2e.test.mjs'),
    'utf8',
  );

  assert.match(
    source,
    /const composeEnvFile = process\.env\.E2E_COMPOSE_ENV_FILE \?\? join\(/u,
  );
});

test('multimodal E2E uses a short test username', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'tests', 'multimodal-e2e.test.mjs'),
    'utf8',
  );

  assert.match(source, /displayName: `多模态测试-\$\{runId\.slice\(-4\)\}`/u);
  assert.doesNotMatch(source, /displayName: `Multimodal E2E \$\{runId\}`/u);
});

test('real-provider acceptance is discoverable and uses the executable E2E asset', () => {
  const guidePath = join(projectRoot, 'docs', 'testing', 'real-provider-acceptance.md');
  assert.equal(existsSync(guidePath), true, 'real-provider acceptance guide must exist');

  const readme = readFileSync(join(projectRoot, 'README.md'), 'utf8');
  const guide = readFileSync(guidePath, 'utf8');
  const harness = readFileSync(
    join(projectRoot, 'deploy', 'tests', 'multimodal-e2e.test.mjs'),
    'utf8',
  );

  assert.match(
    readme,
    /\[真实模型供应商验收指南\]\(docs\/testing\/real-provider-acceptance\.md\)/u,
  );
  assert.match(guide, /node --test \.\\deploy\\tests\\multimodal-e2e\.test\.mjs/u);
  assert.match(guide, /E2E_COMPOSE_ENV_FILE/u);
  assert.match(guide, /E2E_REQUIRE_REAL_PROVIDER=true/u);
  assert.match(guide, /providerCode/u);
  assert.match(guide, /upstreamModel/u);
  assert.match(harness, /function configuredRealComponents\(\)/u);
  assert.match(harness, /E2E_REQUIRE_REAL_PROVIDER/u);
  assert.match(harness, /realComponents\.size > 0/u);
  assert.match(harness, /assert\.notEqual\(stage\.providerCode, 'local-mock'\)/u);
});

test('browser E2E accepts deployed administrator credentials from the environment', () => {
  const source = readFileSync(
    join(projectRoot, 'web-console', 'tests', 'e2e', 'support', 'stack.ts'),
    'utf8',
  );

  assert.match(
    source,
    /export const ADMIN_EMAIL = process\.env\.E2E_ADMIN_EMAIL \?\? 'admin@local\.test'/u,
  );
  // 口令可以来自环境变量，也可以来自部署 env 文件，但绝不能回退到提交进仓库的值。
  assert.match(
    source,
    /export const ADMIN_PASSWORD = process\.env\.E2E_ADMIN_PASSWORD\s*\r?\n\s*\?\? requireComposeEnvironmentValue\('LOCAL_ADMIN_PASSWORD', 'E2E_ADMIN_PASSWORD'\)/u,
  );
  assert.doesNotMatch(source, /'LocalAdmin!2026'/u);
  assert.doesNotMatch(source, /'local-internal-hmac-change-me'/u);
});

test('the production web console is built with the public OpenAI base URL', () => {
  const compose = loadComposeConfig();
  const webConsole = compose.services['web-console'];
  const dockerfile = readFileSync(join(projectRoot, 'web-console', 'Dockerfile'), 'utf8');

  assert.equal(webConsole.build.args.VITE_API_BASE_URL, 'http://8.138.108.191/v1');
  assert.match(dockerfile, /^ARG VITE_API_BASE_URL$/mu);
  assert.match(dockerfile, /^ENV VITE_API_BASE_URL=\$VITE_API_BASE_URL$/mu);
  assert.match(dockerfile, /^RUN chmod -R a\+rX \/usr\/share\/nginx\/html$/mu);
});

test('the /v1 streaming proxy uses a 600 second read timeout', () => {
  const nginxConfig = readFileSync(
    join(projectRoot, 'deploy', 'nginx', 'nginx.conf'),
    'utf8',
  );
  const v1Location = nginxConfig.match(
    /location\s+\^~\s+\/v1\/\s*\{([\s\S]*?)\}/,
  );

  assert.ok(v1Location, 'expected an Nginx location block for /v1/');
  assert.match(
    v1Location[1],
    /^\s*proxy_read_timeout\s+600s;\s*$/m,
  );
});

test('gateway-service waits for a healthy WireMock service', () => {
  const compose = loadComposeConfig();
  assert.equal(
    compose.services['gateway-service'].depends_on?.wiremock?.condition,
    'service_healthy',
  );
});

test('Nginx overwrites untrusted forwarding chains and Spring resolves the trusted client', () => {
  const nginxConfig = readFileSync(
    join(projectRoot, 'deploy', 'nginx', 'nginx.conf'),
    'utf8',
  );
  const applicationConfig = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'application.yaml'),
    'utf8',
  );

  assert.match(nginxConfig, /^\s*proxy_set_header\s+X-Forwarded-For\s+\$remote_addr;\s*$/mu);
  assert.doesNotMatch(nginxConfig, /X-Forwarded-For\s+\$proxy_add_x_forwarded_for/u);
  // 两种策略都会依据 X-Forwarded-For 重写 getRemoteAddr()；'native' 还会额外把这一点限制在
  // 匹配 internal-proxies 的对端上，这正是信任边界必须保持显式的原因。
  assert.match(applicationConfig, /^server:\s*\r?\n\s+forward-headers-strategy:\s*native\s*$/mu);
  const internalProxies = applicationConfig.match(/^\s+internal-proxies:\s*"([^"]+)"/mu);
  assert.ok(internalProxies, 'the proxy trust boundary must be explicit');
  const proxyRanges = internalProxies[1].replace(/\\+/gu, '');
  for (const range of ['10.', '192.168.', '172.1', '172.2', '172.3', '127.', '::1']) {
    assert.ok(proxyRanges.includes(range), `internal-proxies must trust ${range}`);
  }
  // 本服务栈不使用 RFC 7239 头，且绝不能把来自客户端的该头转发出去。
  assert.match(nginxConfig, /^\s*proxy_set_header\s+Forwarded\s+"";\s*$/mu);
});

test('backend services expose opt-in OTLP tracing configuration', () => {
  const compose = loadComposeConfig();
  const example = readExampleEnvironment();
  const gatewayPom = readFileSync(join(projectRoot, 'gateway-service', 'pom.xml'), 'utf8');
  const platformPom = readFileSync(join(projectRoot, 'platform-service', 'pom.xml'), 'utf8');
  const gatewayConfig = readFileSync(
    join(projectRoot, 'gateway-service', 'src', 'main', 'resources', 'application.properties'),
    'utf8',
  );
  const platformConfig = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'application.yaml'),
    'utf8',
  );
  const outboxPublisher = readFileSync(
    join(
      projectRoot,
      'platform-service',
      'src',
      'main',
      'java',
      'com',
      'yuzhihuihe',
      'apiplatform',
      'platform',
      'outbox',
      'OutboxPublisher.java',
    ),
    'utf8',
  );

  for (const [serviceName, pom] of [
    ['gateway-service', gatewayPom],
    ['platform-service', platformPom],
  ]) {
    assert.match(
      pom,
      /<artifactId>micrometer-tracing-bridge-otel<\/artifactId>/u,
      `${serviceName} must bridge Micrometer tracing to OpenTelemetry`,
    );
    assert.match(
      pom,
      /<artifactId>opentelemetry-exporter-otlp<\/artifactId>/u,
      `${serviceName} must provide the OTLP exporter`,
    );

    const environment = compose.services[serviceName].environment;
    assert.equal(environment.OTEL_TRACING_ENABLED, 'false');
    assert.equal(environment.OTEL_HEAD_SAMPLING_PROBABILITY, '0.1');
    assert.equal(environment.OTEL_TRACES_ENDPOINT, 'http://otel-collector:4318/v1/traces');
  }

  assert.match(
    gatewayConfig,
    /^management\.tracing\.enabled=\$\{OTEL_TRACING_ENABLED:false\}$/mu,
  );
  assert.match(
    gatewayConfig,
    /^management\.tracing\.sampling\.probability=\$\{OTEL_HEAD_SAMPLING_PROBABILITY:0\.1\}$/mu,
  );
  assert.match(
    gatewayConfig,
    /^management\.otlp\.tracing\.endpoint=\$\{OTEL_TRACES_ENDPOINT:http:\/\/otel-collector:4318\/v1\/traces\}$/mu,
  );
  assert.match(gatewayConfig, /^spring\.application\.name=gateway-service$/mu);
  assert.match(
    platformConfig,
    /^spring:\s*\r?\n\s+application:\s*\r?\n\s+name:\s+platform-service\s*$/mu,
  );
  assert.match(platformConfig, /^\s+enabled:\s+\$\{OTEL_TRACING_ENABLED:false\}\s*$/mu);
  assert.match(
    platformConfig,
    /^\s+probability:\s+\$\{OTEL_HEAD_SAMPLING_PROBABILITY:0\.1\}\s*$/mu,
  );
  assert.match(
    platformConfig,
    /^\s+endpoint:\s+\$\{OTEL_TRACES_ENDPOINT:http:\/\/otel-collector:4318\/v1\/traces\}\s*$/mu,
  );

  assert.equal(example.get('OTEL_TRACING_ENABLED'), 'false');
  assert.equal(example.get('OTEL_HEAD_SAMPLING_PROBABILITY'), '0.1');
  assert.equal(example.get('OTEL_TRACES_ENDPOINT'), 'http://otel-collector:4318/v1/traces');
  assert.match(outboxPublisher, /RestClient\.Builder gatewayClientBuilder/u);
  assert.doesNotMatch(outboxPublisher, /this\.gatewayClient\s*=\s*RestClient\.builder\(\)/u);
});

test('SMS request-IP hashing has an independent secret with no code-pepper fallback', () => {
  const applicationConfig = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'application.yaml'),
    'utf8',
  );

  assert.match(applicationConfig, /^\s+request-ip-key:\s*\$\{SMS_REQUEST_IP_KEY:\}\s*$/mu);
  assert.doesNotMatch(applicationConfig, /request-ip-key:[^\r\n]*code-pepper/iu);
});

test('password login rate limiting is configured with an independent server secret', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'].environment;
  const applicationConfig = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'application.yaml'),
    'utf8',
  );

  // 渲染出来的值就是运维提供的那个值；示例值绝不能是一个可用的密钥。
  const example = readExampleEnvironment();
  assert.match(example.get('PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY'), /^replace-me-/u,
    'password login HMAC sample must be a non-secret placeholder');
  assert.equal(platform.PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY,
    example.get('PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY'));
  assert.equal(platform.PASSWORD_LOGIN_ACCOUNT_LIMIT, '5');
  assert.equal(platform.PASSWORD_LOGIN_ACCOUNT_WINDOW, '15m');
  assert.equal(platform.PASSWORD_LOGIN_IP_LIMIT, '20');
  assert.equal(platform.PASSWORD_LOGIN_IP_WINDOW, '10m');
  assert.equal(platform.PASSWORD_LOGIN_BLOCK_DURATION, '15m');
  assert.match(applicationConfig,
    /^\s+hmac-key:\s*\$\{PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY:\}\s*$/mu);
  const composeSource = readFileSync(join(projectRoot, 'deploy', 'compose.yaml'), 'utf8');
  assert.doesNotMatch(composeSource,
    /PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY:\s*\$\{PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY:-/u);
});

test('local model bootstrap prefers credentialed real channels and keeps mock as fallback', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'postgres', 'bootstrap-local.sql'),
    'utf8',
  );
  const mockSeedStart = source.indexOf('INSERT INTO gateway.provider(id, code, name');
  const mockPriceSeed = source.indexOf('INSERT INTO gateway.provider_model_price(');
  const reconciliationStart = source.indexOf('-- Prefer configured real providers');
  const routingTransactionStart = source.lastIndexOf('BEGIN;', mockSeedStart);
  const routingTransactionEnd = source.indexOf('COMMIT;', reconciliationStart);

  assert.doesNotMatch(
    source,
    /UPDATE gateway\.provider\s+SET enabled = false[^;]+WHERE provider_type IN \('DEEPSEEK', 'BAILIAN'\);/su,
  );
  assert.match(
    source,
    /UPDATE gateway\.channel AS real_channel[\s\S]+octet_length\(real_channel\.encrypted_api_key\) > 0/u,
  );
  assert.match(
    source,
    /UPDATE gateway\.provider AS real_provider[\s\S]+EXISTS\s*\(/u,
  );
  assert.match(
    source,
    /UPDATE gateway\.channel AS mock_channel[\s\S]+NOT EXISTS\s*\(/u,
  );
  assert.match(
    source,
    /UPDATE gateway\.provider AS mock_provider[\s\S]+NOT EXISTS\s*\(/u,
  );
  assert.ok(mockSeedStart >= 0, 'expected the local mock provider seed');
  assert.ok(
    reconciliationStart > mockPriceSeed,
    'real-provider reconciliation must run after all mock seeds',
  );
  assert.ok(
    routingTransactionStart >= 0 && routingTransactionStart < mockSeedStart,
    'mock seeding and provider reconciliation must start in one transaction',
  );
  assert.ok(
    routingTransactionEnd > reconciliationStart,
    'mock seeding and provider reconciliation must commit atomically',
  );
});

test('local bootstrap preserves one explicit global administrator identity', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'postgres', 'bootstrap-local.sql'),
    'utf8',
  );

  assert.match(source, /INSERT INTO platform\.app_user\([\s\S]+global_admin\)[\s\S]+'70000000-0000-4000-8000-000000000001'[\s\S]+true\)/u);
  assert.match(source, /SET email = lower\(:'admin_email'\)[\s\S]+global_admin = true/u);
  assert.match(source, /WHERE id = '70000000-0000-4000-8000-000000000001'/u);
  assert.doesNotMatch(source, /WHERE lower\(email\) = lower\(:'admin_email'\)/u);
});

test('local bootstrap keeps the composite model virtual and preserves entitlements', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'postgres', 'bootstrap-local.sql'),
    'utf8',
  );

  assert.match(
    source,
    /DELETE\s+FROM\s+gateway\.channel_model[\s\S]+model\.code\s*=\s*'multimodal-basic'/u,
    'bootstrap must remove any legacy direct composite route',
  );
  assert.doesNotMatch(
    source,
    /(?:UPDATE|DELETE\s+FROM)\s+gateway\.(?:package_instance|api_key)\b/iu,
    'bootstrap must not rewrite package or API-key model permissions',
  );
  assert.match(source, /'text-basic',\s*'text-standard',\s*'vision-basic',\s*'file-basic'/u);
  const mockSeedStart = source.indexOf('INSERT INTO gateway.channel_model(');
  const mockSeedEnd = source.indexOf(';', mockSeedStart);
  assert.ok(mockSeedStart >= 0 && mockSeedEnd > mockSeedStart, 'mock channel-model seed must be present');
  assert.doesNotMatch(
    source.slice(mockSeedStart, mockSeedEnd + 1),
    /'multimodal-basic'/u,
    'the local mock seed must not create a direct composite route',
  );
});

test('real payment providers default disabled and MOCK stays in the local profile', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'].environment;

  assert.equal(platform.SPRING_PROFILES_ACTIVE, 'local');
  assert.equal(platform.PAYMENT_MOCK_ENABLED, 'true');
  assert.equal(platform.PAYMENT_WECHAT_PAY_ENABLED, 'false');
  assert.equal(platform.PAYMENT_ALIPAY_ENABLED, 'false');
  assert.equal(compose.services['gateway-service'].environment.PAYMENT_WECHAT_PAY_ENABLED, undefined);
  assert.equal(compose.services['gateway-service'].environment.PAYMENT_ALIPAY_ENABLED, undefined);
  assert.equal(compose.services['web-console'].environment?.PAYMENT_WECHAT_PAY_ENABLED, undefined);
  assert.equal(compose.services['web-console'].environment?.PAYMENT_ALIPAY_ENABLED, undefined);
});

test('a deployment that does not opt in cannot expose the mock payment callback', () => {
  const compose = loadComposeConfigWithoutOptionalOptIns();
  const platform = compose.services['platform-service'].environment;

  // MockPaymentService 与 MockPaymentController 都通过 @ConditionalOnProperty 依赖这个开关，
  // 因此取值为 'false' 时，不鉴权的 /payments/mock/callback 路由根本不会被注册。
  assert.equal(platform.PAYMENT_MOCK_ENABLED, 'false');
  // 该回调唯一的闸门就是这个 HMAC。它绝不能回退到内部 HMAC 密钥，
  // 因为那个密钥还用于签署 /internal/** 流量，而且曾被提交进仓库。
  assert.equal(platform.PAYMENT_MOCK_CALLBACK_SECRET, '');
  assert.notEqual(platform.PAYMENT_MOCK_CALLBACK_SECRET, platform.SECURITY_INTERNAL_HMAC_SECRET);
  assert.equal(platform.PAYMENT_WECHAT_PAY_ENABLED, 'false');
  assert.equal(platform.PAYMENT_ALIPAY_ENABLED, 'false');
});

test('no server-side secret ships with a usable committed default', () => {
  const composeSource = readFileSync(join(projectRoot, 'deploy', 'compose.yaml'), 'utf8');
  const exampleSource = readFileSync(join(projectRoot, 'deploy', 'env', '.env.example'), 'utf8');
  const rootSource = readFileSync(join(projectRoot, 'README.md'), 'utf8');

  // 这些值过去实际上是生产环境的生效默认值。它们是公开的，因此任何仍会解析到其中之一的部署
  // 都可以被轻易伪造。
  const leakedSecrets = [
    'local-internal-hmac-change-me',
    'local-api-key-pepper-change-me',
    'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=',
    'LocalAdmin!2026',
    // 短信密钥过去就提交在这里，而且短信子系统默认是启用的，
    // 于是只要某个部署忘了配置短信，就会用这些密钥去加密手机号。
    'bG9jYWwtb25seS1zbXMtZW5jcnlwdGlvbi1rZXkhISE=',
    'bG9jYWwtc21zLXBob25lLWxvb2t1cC1rZXktMzJiaXQ=',
    'bG9jYWwtc21zLWNvZGUtcGVwcGVyLTMyLWJ5dGVzISE=',
    'bG9jYWwtc21zLXJlcXVlc3QtaXAta2V5LTMyYnl0ZXM=',
    'local-password-login-rate-limit-hmac-key-32-bytes',
  ];
  for (const source of [composeSource, exampleSource]) {
    for (const secret of leakedSecrets) {
      assert.doesNotMatch(source, new RegExp(secret.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'), 'u'),
        `a committed secret value is still present: ${secret}`);
    }
  }

  // 每个密钥都必须是硬性要求，而不是可选覆盖项。
  for (const variable of [
    'SECURITY_INTERNAL_HMAC_SECRET',
    'SECURITY_API_KEY_PEPPER',
    'GATEWAY_SECRETS_ENCRYPTION_KEY',
    'LOCAL_ADMIN_PASSWORD',
    'DOCUMENT_CONVERTER_AUTH_TOKEN',
    'LOGIN_RISK_PEPPER',
    'PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY',
  ]) {
    assert.match(composeSource, new RegExp(`\\$\\{${variable}:\\?`, 'u'),
      `${variable} must be required, not defaulted`);
    assert.doesNotMatch(composeSource, new RegExp(`\\$\\{${variable}:-`, 'u'),
      `${variable} must not have a fallback default`);
  }

  // 短信必须显式开启且不得带任何可用密钥。每个消费方都会在启动时拒绝空密钥，
  // 因此空默认值能把一次静默的错误配置转变成一次响亮的失败。
  assert.match(composeSource, /SMS_ENABLED:\s*\$\{SMS_ENABLED:-false\}/u);
  assert.doesNotMatch(composeSource, /SMS_ENABLED:\s*\$\{SMS_ENABLED:-true\}/u);
  assert.match(composeSource, /SMS_PROVIDER:\s*\$\{SMS_PROVIDER:-aliyun\}/u);
  for (const variable of [
    'SMS_ENCRYPTION_KEY',
    'SMS_PHONE_LOOKUP_KEY',
    'SMS_CODE_PEPPER',
    'SMS_REQUEST_IP_KEY',
  ]) {
    assert.match(composeSource, new RegExp(`\\$\\{${variable}:-\\}`, 'u'),
      `${variable} must default to empty so that enabling SMS without it fails closed`);
  }

  // 模拟回调密钥刻意保留空值兜底：留空即故障关闭（fail-closed），因为
  // MockPaymentService 在未配置密钥时会拒绝启动。
  assert.match(composeSource, /\$\{PAYMENT_MOCK_CALLBACK_SECRET:-\}/u);
  assert.doesNotMatch(composeSource, /PAYMENT_MOCK_CALLBACK_SECRET:\s*\*internal-hmac/u);
  assert.doesNotMatch(composeSource, /PAYMENT_MOCK_ENABLED:\s*"true"/u);
  // README 不得再宣传任何公开的默认管理员口令。
  assert.doesNotMatch(rootSource, /LocalAdmin!2026/u);
});

test('the JWT signing key is not left world-readable', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'bootstrap', 'generate-jwt-key.mjs'),
    'utf8',
  );
  assert.doesNotMatch(source, /chmodSync\(output,\s*0o644\)/u);
  assert.match(source, /chmodSync\(output,\s*0o600\)/u);
  // platform-service 以 uid 10001 运行且是唯一的消费方，因此属主必须随权限位一起变更，
  // 否则该服务读不到自己的签名密钥。
  assert.match(source, /chownSync\(output,\s*keyOwnerUid/u);
  assert.match(source, /JWT_KEY_OWNER_UID\s*\?\?\s*10001/u);
});

test('the local administrator password is only seeded once', () => {
  const source = readFileSync(
    join(projectRoot, 'deploy', 'postgres', 'bootstrap-local.sql'),
    'utf8',
  );
  // 重新部署不得把运维改过的凭证悄悄重置回 env 里的值。
  assert.doesNotMatch(source, /^\s*password_hash = crypt\(:'admin_password'/mu);
  assert.match(source, /WHEN password_hash IS NULL OR password_hash = ''/u);
});

test('provider credential samples are empty and never reach browser or gateway services', () => {
  const example = readExampleEnvironment();
  const credentialKeys = [
    'PAYMENT_WECHAT_PAY_APP_ID',
    'PAYMENT_WECHAT_PAY_MERCHANT_ID',
    'PAYMENT_WECHAT_PAY_MERCHANT_PRIVATE_KEY_RESOURCE',
    'PAYMENT_WECHAT_PAY_API_V3_KEY',
    'PAYMENT_WECHAT_PAY_CERTIFICATE_SERIAL',
    'PAYMENT_WECHAT_PAY_PLATFORM_PUBLIC_KEY_RESOURCE',
    'PAYMENT_WECHAT_PAY_PLATFORM_PUBLIC_KEY_ID',
    'PAYMENT_ALIPAY_APP_ID',
    'PAYMENT_ALIPAY_APPLICATION_PRIVATE_KEY_RESOURCE',
    'PAYMENT_ALIPAY_ALIPAY_PUBLIC_KEY_RESOURCE',
    'PAYMENT_ALIPAY_SELLER_ID',
  ];
  for (const key of credentialKeys) assert.equal(example.get(key), '', `${key} must have an empty sample`);

  const compose = loadComposeConfig();
  for (const serviceName of ['gateway-service', 'web-console']) {
    const environment = compose.services[serviceName].environment || {};
    for (const key of credentialKeys) assert.equal(environment[key], undefined, `${key} leaked to ${serviceName}`);
  }
  assert.equal(compose.services['web-console'].environment?.PAYMENT_PRIVATE_KEY, undefined);
});

test('SMS is disabled by default and all SMS secrets have empty samples', () => {
  const example = readExampleEnvironment();
  assert.equal(example.get('SMS_ENABLED'), 'false');

  const secretKeys = [
    'SMS_ENCRYPTION_KEY',
    'SMS_PHONE_LOOKUP_KEY',
    'SMS_CODE_PEPPER',
    'SMS_REQUEST_IP_KEY',
    'SMS_ALIYUN_ACCESS_KEY_ID',
    'SMS_ALIYUN_ACCESS_KEY_SECRET',
  ];
  for (const key of secretKeys) {
    assert.equal(example.get(key), '', `${key} must have an empty sample`);
  }

  assert.equal(example.get('SMS_ALIYUN_SIGN_NAME'), '宇智汇和东莞科技');
  assert.equal(example.has('SMS_TEST_PHONE'), false);
});

test('referral codes do not require separate deployment keys', () => {
  const example = readExampleEnvironment();
  const compose = loadComposeConfig();
  for (const key of ['REFERRAL_CODE_ENCRYPTION_KEY', 'REFERRAL_CODE_LOOKUP_KEY']) {
    assert.equal(example.has(key), false, `${key} must not be required in the sample`);
    assert.equal(compose.services['platform-service'].environment[key], undefined,
      `${key} must not be required by platform-service`);
    assert.equal(compose.services['gateway-service'].environment[key], undefined,
      `${key} must not reach gateway-service`);
    assert.equal(compose.services['web-console'].environment?.[key], undefined,
      `${key} must not reach web-console`);
  }
});

test('SMS runtime configuration reaches only platform-service without fallback credentials', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'].environment;
  const smsKeys = [
    'SMS_ENABLED',
    'SMS_ENCRYPTION_KEY',
    'SMS_PHONE_LOOKUP_KEY',
    'SMS_CODE_PEPPER',
    'SMS_REQUEST_IP_KEY',
    'SMS_ALIYUN_ENDPOINT',
    'SMS_ALIYUN_ACCESS_KEY_ID',
    'SMS_ALIYUN_ACCESS_KEY_SECRET',
    'SMS_ALIYUN_SIGN_NAME',
    'SMS_ALIYUN_REGION_ID',
  ];

  assert.equal(platform.SMS_ENABLED, 'false');
  assert.equal(platform.SMS_ALIYUN_ACCESS_KEY_ID, '');
  assert.equal(platform.SMS_ALIYUN_ACCESS_KEY_SECRET, '');
  for (const key of smsKeys) {
    assert.notEqual(platform[key], undefined, `${key} missing from platform-service`);
    assert.equal(compose.services['gateway-service'].environment[key], undefined, `${key} leaked to gateway-service`);
    assert.equal(compose.services['web-console'].environment?.[key], undefined, `${key} leaked to web-console`);
  }

  const envSource = readFileSync(join(projectRoot, 'deploy', 'env', '.env.example'), 'utf8');
  const composeSource = readFileSync(join(projectRoot, 'deploy', 'compose.yaml'), 'utf8');
  for (const source of [envSource, composeSource]) {
    assert.doesNotMatch(source, /\b1[3-9]\d{9}\b/u, 'deployment configuration contains a full phone number');
    assert.doesNotMatch(source, /\bLTAI[A-Za-z0-9]{12,}\b/u, 'deployment configuration contains an AccessKey value');
  }
});

test('login-risk secrets and local GeoIP data reach only platform-service', () => {
  const example = readExampleEnvironment();
  // 示例值不得携带可用的密钥。它之所以非空，只是因为 compose 把该变量声明为必填
  // （`${LOGIN_RISK_PEPPER:?}`）；示例值为空会让文档中的首次部署渲染失败。
  // 任何不是真实密钥的值都满足这一意图。
  assert.match(example.get('LOGIN_RISK_PEPPER'), /^replace-me-/u,
    'login-risk pepper sample must be a non-secret placeholder');
  assert.equal(example.get('GEOIP_V4_DATA_SOURCE'), '/run/geoip/ip2region_v4.xdb');
  assert.equal(example.get('GEOIP_V6_DATA_SOURCE'), '/run/geoip/ip2region_v6.xdb');

  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'];
  assert.equal(platform.environment.LOGIN_RISK_PEPPER, example.get('LOGIN_RISK_PEPPER'));
  assert.notEqual(platform.environment.LOGIN_RISK_PEPPER, '');
  assert.equal(platform.environment.GEOIP_V4_DATA_SOURCE, '/run/geoip/ip2region_v4.xdb');
  assert.equal(platform.environment.GEOIP_V6_DATA_SOURCE, '/run/geoip/ip2region_v6.xdb');
  assert.equal(compose.services['gateway-service'].environment.LOGIN_RISK_PEPPER, undefined);
  assert.equal(compose.services['gateway-service'].environment.GEOIP_V4_DATA_SOURCE, undefined);
  assert.equal(compose.services['gateway-service'].environment.GEOIP_V6_DATA_SOURCE, undefined);
  assert.equal(compose.services['web-console'].environment?.LOGIN_RISK_PEPPER, undefined);
  assert.equal(compose.services['web-console'].environment?.GEOIP_V4_DATA_SOURCE, undefined);
  assert.equal(compose.services['web-console'].environment?.GEOIP_V6_DATA_SOURCE, undefined);

  const geoipDirectory = join(projectRoot, 'deploy', 'geoip');
  assert.ok(existsSync(geoipDirectory), 'safe local GeoIP host directory must exist');
  const mount = platform.volumes.find(({ target }) => target === '/run/geoip');
  assert.ok(mount, 'platform-service must mount the local GeoIP directory');
  assert.equal(mount.read_only, true);
  assert.equal(resolve(mount.source), resolve(geoipDirectory));

  assert.equal(platform.healthcheck.test[0], 'CMD-SHELL');
  const healthCommand = platform.healthcheck.test[1];
  assert.match(healthCommand, /test -s \/run\/geoip\/ip2region_v4\.xdb/u);
  assert.match(healthCommand, /test -s \/run\/geoip\/ip2region_v6\.xdb/u);
  assert.match(healthCommand, /actuator\/health\/readiness/u);
});

test('semantic safety MOCK stays consistent with its own allow-mock switch', () => {
  const compose = loadComposeConfig();
  const gateway = compose.services['gateway-service'].environment;
  const example = readExampleEnvironment();
  const composeSource = readFileSync(join(projectRoot, 'deploy', 'compose.yaml'), 'utf8');

  assert.equal(gateway.CONTENT_SAFETY_SEMANTIC_ALLOW_MOCK, 'true');
  assert.equal(gateway.SPRING_PROFILES_ACTIVE, 'local');
  assert.equal(example.get('CONTENT_SAFETY_SEMANTIC_ALLOW_MOCK'), 'true');

  // 2026-10-12 生产事故后收紧：**compose 的默认值不能自相矛盾。**
  //
  // 原先这里断言的是默认 `:-false`，而同一段的 PROVIDER 默认是 `:-MOCK`。
  // 那一对组合是自相矛盾的：SemanticSafetyProviderRegistry 要求"用 MOCK"
  // 必须同时满足 allow-mock=true 且 profile ∈ {local,test}，所以
  // `PROVIDER=MOCK` + `ALLOW_MOCK=false` 的默认值会让**任何一次重启**都以
  // `content_safety_mock_provider_not_allowed` 启动失败。
  //
  // 它在生产潜伏了很久，因为旧容器是在这个守卫生效之前创建的、一直没被重启；
  // 一次镜像重建就让它崩溃循环约 25 分钟。
  //
  // 因此本用例改为断言"两个默认值彼此一致"，而不是钉死某一个字面量：
  // 若将来把 PROVIDER 默认改成 HTTP，那么 ALLOW_MOCK 的默认就应当可以回到 false，
  // 那时这条断言仍然成立（并且由下面的 if 说明为什么）。
  const providerDefault = /\$\{CONTENT_SAFETY_SEMANTIC_PROVIDER:-([A-Z]+)\}/u
    .exec(composeSource)?.[1];
  const allowMockDefault = /\$\{CONTENT_SAFETY_SEMANTIC_ALLOW_MOCK:-(\w+)\}/u
    .exec(composeSource)?.[1];
  assert.ok(providerDefault, 'PROVIDER default must be explicit in compose.yaml');
  assert.ok(allowMockDefault, 'ALLOW_MOCK default must be explicit in compose.yaml');
  if (providerDefault === 'MOCK') {
    assert.equal(
      allowMockDefault,
      'true',
      'PROVIDER defaults to MOCK, so ALLOW_MOCK must default to true or the '
        + 'gateway fails to start on every restart',
    );
  }
});

test('provider endpoints, timeouts, and commercial jobs reach only platform-service', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'].environment;
  const platformOnlyKeys = [
    'PAYMENT_WECHAT_PAY_NOTIFY_URL',
    'PAYMENT_WECHAT_PAY_CONNECT_TIMEOUT',
    'PAYMENT_WECHAT_PAY_READ_TIMEOUT',
    'PAYMENT_WECHAT_PAY_TOTAL_OPERATION_TIMEOUT',
    'PAYMENT_ALIPAY_GATEWAY_URL',
    'PAYMENT_ALIPAY_NOTIFY_URL',
    'PAYMENT_ALIPAY_RETURN_URL',
    'PAYMENT_ALIPAY_CONNECT_TIMEOUT',
    'PAYMENT_ALIPAY_READ_TIMEOUT',
    'PAYMENT_ALIPAY_TOTAL_OPERATION_TIMEOUT',
    'PAYMENT_ORDER_EXPIRY',
    'PAYMENT_JOBS_BATCH_SIZE',
    'PAYMENT_JOBS_CLAIM_TIMEOUT',
    'PAYMENT_JOBS_INITIAL_BACKOFF',
    'PAYMENT_JOBS_MAX_BACKOFF',
    'PAYMENT_JOBS_MAX_ATTEMPTS',
    'PAYMENT_JOBS_EXPIRED_ORDER_DELAY_MS',
    'PAYMENT_JOBS_COMPENSATION_DELAY_MS',
    'REFUND_JOBS_PREPARATION_DELAY_MS',
    'REFUND_JOBS_COMPENSATION_DELAY_MS',
    'REFUND_JOBS_BATCH_SIZE',
    'REFUND_JOBS_CLAIM_TIMEOUT',
    'REFUND_JOBS_INITIAL_BACKOFF',
    'REFUND_JOBS_MAX_BACKOFF',
    'REFUND_JOBS_MAX_ATTEMPTS',
    'RECONCILIATION_DAILY_CRON',
    'RECONCILIATION_ZONE',
    'RECONCILIATION_MAX_BILL_BYTES',
    'RECONCILIATION_MAX_RECORDS',
  ];
  for (const key of platformOnlyKeys) {
    assert.notEqual(platform[key], undefined, `${key} missing from platform-service`);
    assert.equal(compose.services['gateway-service'].environment[key], undefined, `${key} leaked to gateway-service`);
    assert.equal(compose.services['web-console'].environment?.[key], undefined, `${key} leaked to web-console`);
  }
});

test('local refund callback is HTTPS-valid and payment credentials are never mounted by default', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'];

  assert.equal(
    platform.environment.REFUND_PROVIDER_NOTIFY_URL,
    'https://localhost/console/platform/refunds/wechat-pay/callback',
  );
  const paymentMounts = (platform.volumes || []).filter(({ source, target }) =>
    /payment|wechat|alipay|credential|private.?key|certificate/iu.test(`${source}:${target}`));
  assert.deepEqual(paymentMounts, []);
});

test('WireMock is not configured as a real payment-provider endpoint', () => {
  const compose = loadComposeConfig();
  const platform = compose.services['platform-service'].environment;
  for (const key of [
    'PAYMENT_WECHAT_PAY_NOTIFY_URL',
    'PAYMENT_ALIPAY_GATEWAY_URL',
    'PAYMENT_ALIPAY_NOTIFY_URL',
    'PAYMENT_ALIPAY_RETURN_URL',
    'REFUND_PROVIDER_NOTIFY_URL',
  ]) {
    assert.doesNotMatch(String(platform[key] || ''), /wiremock/iu, `${key} points at WireMock`);
  }
});

test('shared internal HMAC reaches both backend services', () => {
  const compose = loadComposeConfig();
  assert.ok(compose.services['platform-service'].environment.SECURITY_INTERNAL_HMAC_SECRET);
  assert.equal(
    compose.services['gateway-service'].environment.SECURITY_INTERNAL_HMAC_SECRET,
    compose.services['platform-service'].environment.SECURITY_INTERNAL_HMAC_SECRET,
  );
});

test('document converter is private and reachable without blocking gateway startup', () => {
  const compose = loadComposeConfig();
  const converter = compose.services['document-converter'];
  const gateway = compose.services['gateway-service'];

  assert.ok(converter, 'document-converter service is required');
  assert.equal(converter.ports, undefined);
  assert.equal(converter.expose, undefined);
  assert.equal(compose.networks.converter.internal, true);
  assert.deepEqual(Object.keys(converter.networks), ['converter']);
  assert.equal(Object.hasOwn(gateway.networks, 'default'), true);
  assert.equal(Object.hasOwn(gateway.networks, 'converter'), true);
  assert.equal(Object.hasOwn(gateway.depends_on ?? {}, 'document-converter'), false);
  assert.equal(gateway.depends_on?.postgres?.condition, 'service_healthy');
  assert.equal(gateway.depends_on?.wiremock?.condition, 'service_healthy');
  assert.equal(
    gateway.environment.DOCUMENT_CONVERTER_AUTH_TOKEN,
    converter.environment.DOCUMENT_CONVERTER_AUTH_TOKEN,
  );
  assert.equal(compose.services['platform-service'].environment.DOCUMENT_CONVERTER_AUTH_TOKEN, undefined);
  assert.equal(compose.services['web-console'].environment?.DOCUMENT_CONVERTER_AUTH_TOKEN, undefined);
});

test('document converter sandbox has explicit process and filesystem limits', () => {
  const compose = loadComposeConfig();
  const converter = compose.services['document-converter'];
  const dockerfile = readFileSync(
    join(projectRoot, 'document-converter', 'Dockerfile'),
    'utf8',
  );
  const converterSource = readFileSync(
    join(projectRoot, 'document-converter', 'src', 'converter.mjs'),
    'utf8',
  );
  const registry = readFileSync(
    join(projectRoot, 'document-converter', 'registrymodifications.xcu'),
    'utf8',
  );

  assert.equal(converter.read_only, true);
  assert.deepEqual(converter.cap_drop, ['ALL']);
  assert.ok(converter.security_opt.includes('no-new-privileges:true'));
  assert.ok(converter.pids_limit > 0 && converter.pids_limit <= 64);
  assert.ok(Number(converter.cpus) > 0 && Number(converter.cpus) <= 1);
  assert.ok(converter.mem_limit > 0 && converter.mem_limit <= 536_870_912);
  assert.equal(converter.volumes, undefined);
  assert.ok(converter.tmpfs.some((entry) => String(entry).startsWith('/tmp:')));
  assert.match(dockerfile, /^USER\s+converter$/mu);
  assert.doesNotMatch(dockerfile, /ENTRYPOINT\s+\[?\s*["']?(?:sh|bash)/iu);
  assert.match(String(converter.healthcheck.test), /\/health\/ready/u);
  assert.match(dockerfile, /AS\s+verification/iu);
  assert.match(dockerfile, /node\s+--test\s+test\/\*\.test\.mjs/u);
  assert.match(dockerfile, /COPY\s+--from=verification/iu);
  assert.match(converterSource, /SAL_USE_VCLPLUGIN:\s*'svp'/u);
  assert.match(converterSource, /SAL_DISABLE_OPENCL:\s*'1'/u);
  assert.doesNotMatch(converterSource, /\.\.\.process\.env/u);
  assert.doesNotMatch(converterSource, /['"]--accept(?:=|['"])/u);
  assert.match(registry, /MacroSecurityLevel[\s\S]*?<value>3<\/value>/u);
  assert.match(registry, /UpdateDocMode[\s\S]*?<value>0<\/value>/u);
});

test('real SMS template runner has bounded resumable execution controls', () => {
  const runner = readFileSync(
    join(projectRoot, 'scripts', 'test-active-sms-templates.ps1'),
    'utf8',
  );

  assert.match(runner, /\[string\]\$RunId/u);
  assert.match(runner, /\[string\[\]\]\$TemplateKey/u);
  assert.match(runner, /\[string\]\$DeviceId/u);
  assert.match(runner, /\[int\]\$RequestTimeoutSeconds/u);
  assert.match(runner, /\[Guid\]::NewGuid\(\)\.ToString\('N'\)/u);
  assert.match(runner, /'X-Device-Id'\s*=\s*\$DeviceId/u);
  assert.match(runner, /-TimeoutSec\s+\$RequestTimeoutSeconds/u);
  assert.match(runner, /\[Uri\]::TryCreate/u);
});

test('payment callbacks use exact bounded Nginx proxies without access logging', () => {
  const nginxConfig = readFileSync(join(projectRoot, 'deploy', 'nginx', 'nginx.conf'), 'utf8');
  for (const callback of [
    '/console/platform/payments/wechat-pay/callback',
    '/console/platform/payments/alipay/callback',
    '/console/platform/refunds/wechat-pay/callback',
  ]) {
    const body = locationBody(nginxConfig, callback);
    assert.match(body, /^\s*access_log\s+off;\s*$/mu);
    assert.match(body, /^\s*client_max_body_size\s+1m;\s*$/mu);
    assert.match(body, /^\s*proxy_request_buffering\s+off;\s*$/mu);
    assert.match(body, /^\s*proxy_pass\s+http:\/\/platform_backend;\s*$/mu);
  }
  assert.doesNotMatch(nginxConfig, /location\s+\^~\s+\/console\/platform\/(?:admin|payments)\//u);
});

test('ticket attachment requests fit five 5 MiB files plus multipart framing', () => {
  const minimumRequestBytes = (5 * 5 + 1) * 1024 * 1024;
  for (const configName of ['nginx.conf', 'nginx.https.conf']) {
    const config = readFileSync(join(projectRoot, 'deploy', 'nginx', configName), 'utf8');
    const maxBodySize = config.match(/^\s*client_max_body_size\s+(\d+)m;\s*$/mu);
    assert.ok(maxBodySize, `${configName} must set the baseline request body limit`);
    assert.ok(Number(maxBodySize[1]) * 1024 * 1024 >= minimumRequestBytes,
      `${configName} must fit five full-size ticket attachments and multipart framing`);
  }

  const applicationConfig = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'application.yaml'), 'utf8');
  const servletRequestSize = applicationConfig.match(/^\s*max-request-size:\s*(\d+)MB\s*$/mu);
  assert.ok(servletRequestSize, 'platform-service must set its multipart request limit');
  assert.ok(Number(servletRequestSize[1]) * 1024 * 1024 >= minimumRequestBytes,
    'platform-service must fit five full-size ticket attachments and multipart framing');
});

test('both Nginx entries enforce edge abuse caps and baseline response hardening', () => {
  const entries = [
    { name: 'nginx.conf', body: readFileSync(join(projectRoot, 'deploy', 'nginx', 'nginx.conf'), 'utf8') },
    { name: 'nginx.https.conf', body: readFileSync(join(projectRoot, 'deploy', 'nginx', 'nginx.https.conf'), 'utf8') },
  ];

  for (const { name, body } of entries) {
    // 限速与连接数 zone 必须声明在 http 块里，而不是逐个 location 声明。
    for (const variable of ['auth_zone', 'callback_zone', 'v1_zone', 'session_zone']) {
      assert.match(body, new RegExp(`^\\s*limit_req_zone\\s+\\$binary_remote_addr\\s+zone=${variable}:`, 'mu'),
        `${name} must declare the ${variable} rate-limit zone`);
    }
    assert.match(body, /^\s*limit_conn_zone\s+\$binary_remote_addr\s+zone=perip_conn:/mu);
    assert.match(body, /^\s*limit_req_status\s+429;\s*$/mu);
    assert.match(body, /^\s*limit_conn_status\s+429;\s*$/mu);
    assert.match(body, /^\s*limit_conn\s+perip_conn\s+\d+;\s*$/mu);

    // Slowloris / 请求体停滞的预算必须有上限，不能沿用 Nginx 的默认值。
    for (const directive of ['client_header_timeout', 'client_body_timeout', 'send_timeout']) {
      assert.match(body, new RegExp(`^\\s*${directive}\\s+\\d+s;\\s*$`, 'mu'),
        `${name} must bound ${directive}`);
    }

    // 基础安全响应头，以及施加在凭证处理前缀上的认证桶。
    assert.match(body, /^\s*add_header\s+X-Content-Type-Options\s+nosniff\s+always;\s*$/mu);
    assert.match(body, /^\s*add_header\s+X-Frame-Options\s+SAMEORIGIN\s+always;\s*$/mu);
    assert.match(body, /^\s*add_header\s+Referrer-Policy\s+strict-origin-when-cross-origin\s+always;\s*$/mu);
    assert.match(body, /^\s*add_header\s+Permissions-Policy\s+"geolocation=\(\), microphone=\(\), camera=\(\)"\s+always;\s*$/mu);
    // 凭证签发继续用严格桶；会话维持类轮询另给一个更宽松的桶。
    // 凭证前缀**不能**是 ^~ location：^~ 会屏蔽正则匹配，于是会话类端点会掉回 60r/m 的桶，
    // 已登录的 SPA（它会轮询 terminal-presence 与待处理的登录交接）在正常使用中就会不断收到 429。
    assert.match(body, /location\s+\/console\/platform\/auth\/\s*\{[^}]*limit_req\s+zone=auth_zone/u,
      `${name} must rate-limit the credential prefix with the auth zone`);
    assert.doesNotMatch(body, /location\s+\^~\s+\/console\/platform\/auth\//u,
      `${name} must not declare the auth prefix as ^~: it would shadow the session-endpoint regex`);
    const sessionLocation = body.match(
      /location\s+~\s+\^\/console\/platform\/auth\/\(([^)]*)\)\s*\{([\s\S]*?)\n\s*\}/u);
    assert.ok(sessionLocation,
      `${name} must route session-maintenance endpoints through their own regex location`);
    assert.match(sessionLocation[2], /limit_req\s+zone=session_zone/u,
      `${name} session-maintenance endpoints must use the session zone`);
    for (const endpoint of ['refresh', 'terminal-presence', 'login-handoffs/']) {
      assert.ok(sessionLocation[1].includes(endpoint),
        `${name} session-endpoint regex must cover ${endpoint}`);
    }

    // add_header 不会继承到自行声明了 add_header 的 location 中，因此 /v1/ 必须把加固响应头
    // 与它的 X-Accel-Buffering 指令一起重复一遍。
    const v1 = body.match(/location\s+\^~\s+\/v1\/\s*\{([\s\S]*?)\n\s*\}/u);
    assert.ok(v1, `${name} must keep the /v1/ location`);
    assert.match(v1[1], /limit_req\s+zone=v1_zone/u, `${name} /v1/ must use the v1 zone`);
    assert.match(v1[1], /add_header\s+X-Accel-Buffering\s+no\s+always;/u);
    assert.match(v1[1], /add_header\s+X-Content-Type-Options\s+nosniff\s+always;/u);
    assert.match(v1[1], /add_header\s+X-Frame-Options\s+SAMEORIGIN\s+always;/u);
    assert.match(v1[1], /add_header\s+Referrer-Policy\s+strict-origin-when-cross-origin\s+always;/u);

    // 点文件一律拒绝，公开的 JWKS 路径除外。
    assert.match(body, /location\s+~\s+\/\\\.\(\?!well-known\/\)\s*\{[^}]*deny\s+all;/u,
      `${name} must refuse dotfile paths while exempting /.well-known/`);
  }

  // HSTS 只属于 TLS 入口；通过明文 HTTP 发送它既无意义又具误导性。
  assert.match(entries[1].body, /^\s*add_header\s+Strict-Transport-Security\s+"max-age=31536000; includeSubDomains"\s+always;\s*$/mu);
  assert.doesNotMatch(entries[0].body, /Strict-Transport-Security/u);
});

test('all deterministic payment provider mappings are present', () => {
  const names = [
    'wechat-native-prepay.json',
    'wechat-h5-prepay.json',
    'wechat-query-paid.json',
    'wechat-close.json',
    'wechat-refund.json',
    'wechat-refund-query.json',
    'wechat-bill.json',
    'alipay-page-pay.json',
    'alipay-wap-pay.json',
    'alipay-query-paid.json',
    'alipay-close.json',
    'alipay-refund.json',
    'alipay-refund-query.json',
    'alipay-bill.json',
  ];
  for (const name of names) {
    const source = readFileSync(join(projectRoot, 'deploy', 'wiremock', 'mappings', name), 'utf8');
    const mapping = JSON.parse(source);
    assert.ok(source.length <= 4_096, `${name} fixture is not bounded`);
    assert.doesNotMatch(source, /BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|api[_-]?v3[_-]?key/iu);
    assert.ok(mapping.request.method, `${name} must match an exact method`);
    assert.ok(mapping.request.urlPath, `${name} must match an exact path`);
    assert.doesNotMatch(mapping.request.urlPath, /\*/u, `${name} must not use wildcard paths`);
  }
});

test('provider pending, failed, and timeout responses use explicit WireMock scenario states', () => {
  const names = [
    'wechat-query-pending.json',
    'wechat-query-failed.json',
    'wechat-query-timeout.json',
    'wechat-refund-query-pending.json',
    'wechat-refund-query-failed.json',
    'wechat-refund-query-timeout.json',
    'alipay-query-pending.json',
    'alipay-query-failed.json',
    'alipay-query-timeout.json',
    'alipay-refund-query-pending.json',
    'alipay-refund-query-failed.json',
    'alipay-refund-query-timeout.json',
  ];
  for (const name of names) {
    const source = readFileSync(join(projectRoot, 'deploy', 'wiremock', 'mappings', name), 'utf8');
    const mapping = JSON.parse(source);
    assert.ok(source.length <= 4_096, `${name} fixture is not bounded`);
    assert.equal(mapping.priority, 1, `${name} must override the success fixture only by state`);
    assert.match(mapping.scenarioName, /^(?:wechat|alipay)-(?:payment|refund)-query$/u);
    assert.match(mapping.requiredScenarioState, /^(?:PENDING|FAILED|TIMEOUT)$/u);
    assert.ok(mapping.request.method, `${name} must match an exact method`);
    assert.ok(mapping.request.urlPath, `${name} must match an exact path`);
    assert.doesNotMatch(source, /BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY|api[_-]?v3[_-]?key/iu);
  }
});

test('provider POST mappings bind exact business fields instead of body substrings', () => {
  const alipayMappings = [
    ['alipay-bill.json', 'alipay.data.dataservice.bill.downloadurl.query', 'bill_date', '2026-08'],
    ['alipay-close.json', 'alipay.trade.close', 'out_trade_no', 'pay_fixture_close'],
    ['alipay-page-pay.json', 'alipay.trade.page.pay', 'out_trade_no', 'pay_fixture_page'],
    ['alipay-query-paid.json', 'alipay.trade.query', 'out_trade_no', 'pay_fixture_paid'],
    ['alipay-query-pending.json', 'alipay.trade.query', 'out_trade_no', 'pay_fixture_paid'],
    ['alipay-query-failed.json', 'alipay.trade.query', 'out_trade_no', 'pay_fixture_paid'],
    ['alipay-query-timeout.json', 'alipay.trade.query', 'out_trade_no', 'pay_fixture_paid'],
    ['alipay-refund.json', 'alipay.trade.refund', 'out_request_no', 'ref_fixture_paid'],
    ['alipay-refund-query.json', 'alipay.trade.fastpay.refund.query', 'out_request_no', 'ref_fixture_paid'],
    ['alipay-refund-query-pending.json', 'alipay.trade.fastpay.refund.query', 'out_request_no', 'ref_fixture_paid'],
    ['alipay-refund-query-failed.json', 'alipay.trade.fastpay.refund.query', 'out_request_no', 'ref_fixture_paid'],
    ['alipay-refund-query-timeout.json', 'alipay.trade.fastpay.refund.query', 'out_request_no', 'ref_fixture_paid'],
    ['alipay-wap-pay.json', 'alipay.trade.wap.pay', 'out_trade_no', 'pay_fixture_wap'],
  ];
  for (const [name, method, field, value] of alipayMappings) {
    const source = readFileSync(join(projectRoot, 'deploy', 'wiremock', 'mappings', name), 'utf8');
    const mapping = JSON.parse(source);
    assert.doesNotMatch(source, /"contains"\s*:/u, `${name} uses substring matching`);
    assert.equal(mapping.request.formParameters?.method?.equalTo, method, `${name} method`);
    const businessMatcher = mapping.request.formParameters?.biz_content?.matches;
    assert.equal(typeof businessMatcher, 'string', `${name} biz_content matcher`);
    assert.ok(businessMatcher.startsWith('(?s)^\\{'), `${name} matcher must anchor the JSON object`);
    assert.ok(
      businessMatcher.includes(`\\"${field}\\":\\"${value}\\"`),
      `${name} must bind ${field}=${value}`,
    );
  }

  const wechatMappings = [
    ['wechat-native-prepay.json', '$.out_trade_no', 'pay_fixture_native'],
    ['wechat-h5-prepay.json', '$.out_trade_no', 'pay_fixture_h5'],
    ['wechat-refund.json', '$.out_refund_no', 'ref_fixture_paid'],
  ];
  for (const [name, expression, value] of wechatMappings) {
    const source = readFileSync(join(projectRoot, 'deploy', 'wiremock', 'mappings', name), 'utf8');
    const mapping = JSON.parse(source);
    assert.doesNotMatch(source, /"contains"\s*:/u, `${name} uses substring matching`);
    assert.ok(mapping.request.bodyPatterns.some((pattern) =>
      pattern.matchesJsonPath?.expression === expression
        && pattern.matchesJsonPath?.equalTo === value), `${name} must bind ${expression}=${value}`);
  }
});

test('k6 order-payment workload preserves the release-gate load shape', () => {
  const source = readFileSync(join(projectRoot, 'deploy', 'perf', 'order-payment.js'), 'utf8');
  assert.match(source, /executor:\s*['"]constant-arrival-rate['"]/u);
  assert.match(source, /rate:\s*30\b/u);
  assert.match(source, /timeUnit:\s*['"]1s['"]/u);
  assert.match(source, /duration:\s*['"]60s['"]/u);
  assert.match(source, /preAllocatedVUs:\s*40\b/u);
  assert.match(source, /http_req_failed:\s*\[['"]rate<0\.005['"]\]/u);
  assert.match(source, /http_req_duration:\s*\[['"]p\(95\)<500['"]\]/u);
  assert.match(source, /PAYMENT_PROVIDER[^\n]*MOCK/u);
  assert.doesNotMatch(source, /WECHAT_PAY|ALIPAY/u);
});

test('SMS operations and Phase 3 records cover every release gate and remaining inactive template', () => {
  const operationsPath = join(projectRoot, 'docs', 'operations', 'sms-notifications.md');
  assert.ok(existsSync(operationsPath), 'SMS operations runbook is required');
  const operations = readFileSync(operationsPath, 'utf8');
  for (const requirement of [
    /dysms:SendSms/u,
    /SMS_ENABLED=false/u,
    /^SMS_ALIYUN_ACCESS_KEY_ID=\s*$/mu,
    /^SMS_ALIYUN_ACCESS_KEY_SECRET=\s*$/mu,
    /宇智汇和东莞科技/u,
    /metrics/iu,
    /failed delivery/iu,
    /retry/iu,
    /rotation/iu,
    /rollback/iu,
    /consent/iu,
    /retention/iu,
  ]) {
    assert.match(operations, requirement);
  }

  const readme = readFileSync(join(projectRoot, 'README.md'), 'utf8');
  assert.match(readme, /\(docs\/operations\/sms-notifications\.md\)/u);

  const phase2 = readFileSync(
    join(projectRoot, 'docs', 'superpowers', 'plans', '2026-08-01-api-model-platform-phase2.md'),
    'utf8',
  );
  assert.match(phase2, /^## Completed Extension: SMS Notifications$/mu);
  const registry = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'db', 'migration', 'V10__sms_notifications.sql'),
    'utf8',
  );
  const inactiveRows = [...registry.matchAll(
    /^\s*\('([^']+)',\s*'(SMS_\d+)',\s*'[^']+',\s*'[^']+'::jsonb,\s*'(PENDING_INTEGRATION|RESERVED)'\)/gmu,
  )];
  assert.ok(inactiveRows.length > 0, 'inactive SMS templates must be registered');
  const smsLoginMigration = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'resources', 'db', 'migration', 'V14__sms_login.sql'),
    'utf8',
  );
  assert.match(smsLoginMigration, /SET integration_status = 'ACTIVE'[\s\S]*WHERE template_key = 'LOGIN_CODE'/u);
  assert.match(phase2, /`LOGIN_CODE` template for verified bound-phone login/u);
  for (const [, templateKey, templateCode, status] of inactiveRows.filter(([, key]) => key !== 'LOGIN_CODE')) {
    const expectedRow = `| \`${templateKey}\` | \`${templateCode}\` | \`${status}\` |`;
    assert.ok(phase2.includes(expectedRow), `${templateKey} missing from the Phase 3 table`);
  }
});

test('wallet E2E runs only in a disposable isolated Compose project', () => {
  const runnerPath = join(projectRoot, 'deploy', 'tests', 'run-isolated-wallet-e2e.mjs');
  assert.ok(existsSync(runnerPath), 'isolated wallet E2E runner is required');

  const runner = readFileSync(runnerPath, 'utf8');
  const support = readFileSync(
    join(projectRoot, 'web-console', 'tests', 'e2e', 'support', 'stack.ts'),
    'utf8',
  );
  const workflow = readFileSync(
    join(projectRoot, 'web-console', 'tests', 'e2e', 'wallet-workflows.spec.ts'),
    'utf8',
  );

  assert.match(runner, /--project-name/u);
  assert.match(runner, /E2E_COMPOSE_PROJECT_NAME/u);
  assert.match(runner, /WALLET_E2E_ISOLATED/u);
  assert.match(
    runner,
    /SELECT count\(\*\)[\s\S]+SELECT count\(\*\) FROM platform\.balance_ledger/u,
    'shared facts must compare both E2E user count and ledger row count',
  );
  assert.doesNotMatch(runner, /count\(\*\) FILTER/u);
  assert.match(runner, /finally\s*\{/u);
  assert.match(runner, /down[^\n]*--volumes/u);
  assert.doesNotMatch(runner, /shell\s*:/u);
  assert.match(support, /E2E_COMPOSE_PROJECT_NAME/u);
  assert.match(support, /--project-name/u);
  assert.match(workflow, /WALLET_E2E_ISOLATED/u);
  assert.match(workflow, /E2E_BASE_URL/u);
  assert.match(workflow, /not\.toBe\(['"]18081['"]\)/u);
  assert.doesNotMatch(workflow, /cleanupStackUsers/u);
});

test('every internal platform-service route is listed in the security allowlist', () => {
  // platform-service 会拒绝所有未显式列入白名单的 /internal/** 路径。缺少一条白名单并不会
  // 响亮地失败：服务照常启动、照常提供服务，只有调用方会察觉。这正是
  // /internal/gateway/content-safety/policy 被漏掉时的情形——网关的策略刷新开始失败，内存中的
  // 快照变陈旧，随后每次模型调用都开始返回 503 content_safety_unavailable。本测试枚举所有
  // internal 控制器，并要求每个类级别的前缀都出现在白名单中，从而让新增的 internal 控制器
  // 无法在无人察觉的情况下变得不可达。
  const securityConfiguration = readFileSync(
    join(projectRoot, 'platform-service', 'src', 'main', 'java', 'com', 'yuzhihuihe', 'apiplatform',
      'platform', 'security', 'SecurityConfiguration.java'),
    'utf8',
  );
  const platformSources = join(projectRoot, 'platform-service', 'src', 'main', 'java');

  // 白名单是唯一提到 /internal/ 的 .requestMatchers(...) 块。
  const allowlistBlock = securityConfiguration
    .split('.requestMatchers(')
    .find((block) => block.includes('"/internal/'));
  assert.ok(allowlistBlock, 'SecurityConfiguration must keep an explicit /internal/ allowlist');
  const allowlisted = [...allowlistBlock.matchAll(/"(\/internal\/[^"]*)"/gu)].map((match) => match[1]);

  const internalPrefixes = new Set();
  const walk = (directory) => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.name.endsWith('.java')) {
        continue;
      }
      const source = readFileSync(path, 'utf8');
      for (const match of source.matchAll(/@RequestMapping\("(\/internal\/[^"]*)"\)/gu)) {
        internalPrefixes.add(match[1]);
      }
    }
  };
  walk(platformSources);

  assert.ok(internalPrefixes.size >= 5,
    `expected to discover the internal controllers, found ${internalPrefixes.size}`);

  for (const prefix of internalPrefixes) {
    const covered = allowlisted.some((entry) =>
      entry === prefix || entry.startsWith(`${prefix}/`) || prefix.startsWith(`${entry}/`));
    assert.ok(covered,
      `${prefix} is mapped but absent from the /internal/ security allowlist; with `
      + '.requestMatchers("/internal/**").denyAll() the route is unreachable. Add it to the '
      + 'allowlist in SecurityConfiguration (and to SIGNED_INTERNAL_PATHS in '
      + 'InternalSurfaceAuthorizationTest).');
  }

  // 白名单还必须排在拒绝规则之前，否则它什么也放行不了。
  assert.ok(securityConfiguration.indexOf('"/internal/') < securityConfiguration.indexOf('denyAll()'),
    'the /internal/ allowlist must be declared before the deny rule');
});
