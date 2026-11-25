import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDirectory, '..', '..');
const baseCompose = join(projectRoot, 'deploy', 'compose.yaml');
const ossCompose = join(projectRoot, 'deploy', 'compose.oss.yaml');
const ossExample = join(projectRoot, 'deploy', 'env', '.env.oss.local.example');
const applicationProperties = join(
  projectRoot,
  'gateway-service',
  'src',
  'main',
  'resources',
  'application.properties',
);

function composeConfig(files, envFile) {
  const args = ['compose'];
  if (envFile) args.push('--env-file', envFile);
  for (const file of files) args.push('-f', file);
  args.push('config', '--format', 'json');
  const result = spawnSync('docker', args, {
    cwd: projectRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      // Render configuration with test-only secrets; never weaken the required
      // production settings or depend on credentials from the developer's shell.
      SECURITY_INTERNAL_HMAC_SECRET: 'oss-contract-test-internal-hmac',
      SECURITY_API_KEY_PEPPER: 'oss-contract-test-api-key-pepper',
      GATEWAY_SECRETS_ENCRYPTION_KEY: Buffer.alloc(32, 1).toString('base64'),
      DOCUMENT_CONVERTER_AUTH_TOKEN: 'oss-contract-test-converter-token',
      LOCAL_ADMIN_PASSWORD: 'oss-contract-test-admin-password',
      LOGIN_RISK_PEPPER: 'oss-contract-test-login-risk-pepper',
      PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY: 'oss-contract-test-password-login-hmac',
      OSS_ACCESS_KEY_ID: '',
      OSS_ACCESS_KEY_SECRET: '',
      OSS_SESSION_TOKEN: '',
    },
  });
  assert.equal(
    result.status,
    0,
    `docker compose config failed:\n${result.stderr || result.error || 'unknown error'}`,
  );
  return JSON.parse(result.stdout);
}

function exampleEnvironment() {
  const source = readFileSync(ossExample, 'utf8');
  return new Map(source
    .split(/\r?\n/u)
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const separator = line.indexOf('=');
      return separator < 0
        ? [line, '']
        : [line.slice(0, separator), line.slice(separator + 1)];
    }));
}

test('base deployment remains MinIO-first and direct upload is disabled by default', () => {
  const compose = composeConfig([baseCompose]);
  const gateway = compose.services['gateway-service'];
  const properties = readFileSync(applicationProperties, 'utf8');

  assert.ok(compose.services.minio);
  assert.ok(compose.services['minio-init']);
  assert.equal(gateway.environment.STORAGE_FILES_ENDPOINT, 'http://minio:9000');
  assert.equal(gateway.environment.STORAGE_FILES_PROVIDER, undefined);
  assert.match(properties, /^storage\.files\.provider=\$\{STORAGE_FILES_PROVIDER:minio\}$/mu);
  assert.match(
    properties,
    /^storage\.files\.direct-upload-enabled=\$\{STORAGE_FILES_DIRECT_UPLOAD_ENABLED:false\}$/mu,
  );
});

test('OSS override changes only Gateway storage configuration and remains switchable', () => {
  assert.ok(existsSync(ossCompose), 'deploy/compose.oss.yaml must exist');
  assert.ok(existsSync(ossExample), 'deploy/env/.env.oss.local.example must exist');
  const base = composeConfig([baseCompose]);
  const merged = composeConfig([baseCompose, ossCompose], ossExample);
  const baseGateway = base.services['gateway-service'];
  const mergedGateway = merged.services['gateway-service'];
  const overrideSource = readFileSync(ossCompose, 'utf8');

  assert.deepEqual(
    [...overrideSource.matchAll(/^  ([a-z0-9-]+):\s*$/gmu)].map((match) => match[1]),
    ['gateway-service'],
  );
  assert.equal(baseGateway.environment.STORAGE_FILES_PROVIDER, undefined);
  assert.equal(mergedGateway.environment.STORAGE_FILES_PROVIDER, 'aliyun-oss');
  assert.equal(mergedGateway.environment.STORAGE_FILES_DIRECT_UPLOAD_ENABLED, 'true');
  assert.match(
    overrideSource,
    /STORAGE_FILES_PUBLIC_ENDPOINT:\s*\$\{STORAGE_FILES_PUBLIC_ENDPOINT\}/u,
  );
  assert.match(
    overrideSource,
    /STORAGE_FILES_BACKEND_ENDPOINT:\s*\$\{STORAGE_FILES_BACKEND_ENDPOINT\}/u,
  );
  assert.ok(mergedGateway.environment.STORAGE_FILES_PUBLIC_ENDPOINT);
  assert.ok(mergedGateway.environment.STORAGE_FILES_BACKEND_ENDPOINT);
});

test('OSS override configures only the files bucket and passes credentials without literals', () => {
  const source = readFileSync(ossCompose, 'utf8');
  const merged = composeConfig([baseCompose, ossCompose], ossExample);
  const environment = merged.services['gateway-service'].environment;

  assert.equal(environment.STORAGE_FILES_BUCKET, 'yzh-api-files-test');
  assert.equal(environment.OSS_ACCESS_KEY_ID, '');
  assert.equal(environment.OSS_ACCESS_KEY_SECRET, '');
  assert.equal(environment.OSS_SESSION_TOKEN, '');
  assert.doesNotMatch(source, /backup/iu);
  assert.doesNotMatch(source, /OSS_(?:ACCESS_KEY_ID|ACCESS_KEY_SECRET|SESSION_TOKEN):\s*[^$\s]/u);
  assert.doesNotMatch(JSON.stringify(environment), /yzh-api-backup/iu);
});

test('OSS environment sample contains safe placeholders and empty credentials', () => {
  const source = readFileSync(ossExample, 'utf8');
  const environment = exampleEnvironment();

  assert.equal(environment.get('STORAGE_FILES_BUCKET'), 'yzh-api-files-test');
  assert.equal(environment.get('STORAGE_FILES_REGION'), 'cn-beijing');
  assert.equal(
    environment.get('STORAGE_FILES_PUBLIC_ENDPOINT'),
    'https://oss-cn-beijing.aliyuncs.com',
  );
  assert.equal(
    environment.get('STORAGE_FILES_BACKEND_ENDPOINT'),
    'https://oss-cn-beijing.aliyuncs.com',
  );
  assert.equal(environment.get('OSS_ACCESS_KEY_ID'), '');
  assert.equal(environment.get('OSS_ACCESS_KEY_SECRET'), '');
  assert.equal(environment.get('OSS_SESSION_TOKEN'), '');
  assert.match(source, /rotate|rotation|轮换|轮转/iu);
});
