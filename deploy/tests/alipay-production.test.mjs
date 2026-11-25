import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const gitExecutable = process.platform === 'win32'
  ? spawnSync('where.exe', ['git'], { encoding: 'utf8' }).stdout?.trim().split(/\r?\n/u)[0]
  : undefined;
const windowsBash = [
  gitExecutable && join(dirname(dirname(gitExecutable)), 'bin', 'bash.exe'),
  'C:/Program Files/Git/bin/bash.exe',
].find((path) => path && existsSync(path));
const bash = process.platform === 'win32' ? (windowsBash ?? 'bash') : 'bash';

function productionConfig(withAlipay) {
  const fixture = mkdtempSync(join(tmpdir(), 'alipay-production-'));
  try {
    mkdirSync(join(fixture, 'deploy'));
    mkdirSync(join(fixture, '.secrets'));
    for (const file of ['production.sh', 'compose.yaml', 'compose.https.yaml', 'compose.alipay.yaml']) {
      const source = readFileSync(join(projectRoot, 'deploy', file), 'utf8').replaceAll('\r\n', '\n');
      writeFileSync(join(fixture, 'deploy', file), source);
    }
    writeFileSync(join(fixture, '.secrets', 'oss.env'),
      readFileSync(join(projectRoot, 'deploy', 'env', '.env.example'), 'utf8'));
    // Exercise existing optional runtime loading and HTTPS override precedence.
    writeFileSync(join(fixture, '.secrets', 'local-runtime.env'), 'EMAIL_FROM_NAME=Mail Test\n');
    if (withAlipay) {
      writeFileSync(join(fixture, '.secrets', 'alipay.env'), [
        'PAYMENT_ALIPAY_ENABLED=false',
        'PAYMENT_ALIPAY_APP_ID=2021000000000001',
        'PAYMENT_ALIPAY_APPLICATION_PRIVATE_KEY_RESOURCE=file:/run/payment/alipay/application-private-key.pem',
        'PAYMENT_ALIPAY_ALIPAY_PUBLIC_KEY_RESOURCE=file:/run/payment/alipay/alipay-public-key.pem',
        'PAYMENT_ALIPAY_WAP_APP_ID=2021000000000002',
        'PAYMENT_ALIPAY_WAP_ENABLED=false',
        'PAYMENT_ALIPAY_WAP_APPLICATION_PRIVATE_KEY_RESOURCE=file:/run/payment/alipay/wap/application-private-key.pem',
        'PAYMENT_ALIPAY_WAP_ALIPAY_PUBLIC_KEY_RESOURCE=file:/run/payment/alipay/wap/alipay-public-key.pem',
        '',
      ].join('\n'));
    }
    const result = spawnSync(bash,
      [join(fixture, 'deploy', 'production.sh').replaceAll('\\', '/'), 'config', '--format', 'json'], {
        cwd: fixture,
        encoding: 'utf8',
        timeout: 20000,
        env: { ...process.env, DOCUMENT_CONVERTER_AUTH_TOKEN: 'deployment-test-only' },
      });
    assert.equal(result.status, 0, result.stderr || String(result.error || 'Compose validation failed'));
    return JSON.parse(result.stdout);
  } finally {
    rmSync(fixture, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  }
}

for (const withAlipay of [false, true]) {
  test(`production wrapper preserves HTTPS with Alipay configuration ${withAlipay ? 'present' : 'absent'}`, () => {
    const config = productionConfig(withAlipay);
    const platform = config.services['platform-service'];
    assert.equal(platform.environment.PAYMENT_ALIPAY_ENABLED, 'false');
    assert.equal(platform.environment.SECURITY_COOKIES_SECURE, 'true');
    assert.equal(platform.environment.EMAIL_FROM_NAME, 'Mail Test');
    assert.equal(config.services['gateway-service'].environment.GATEWAY_CLIENT_API_BASE_URL,
      'https://api.yuzhh.com/v1');

    const mount = platform.volumes.find((item) => item.target === '/run/payment/alipay');
    if (withAlipay) {
      assert.equal(platform.environment.PAYMENT_ALIPAY_APP_ID, '2021000000000001');
      assert.equal(platform.environment.PAYMENT_ALIPAY_WAP_APP_ID, '2021000000000002');
      assert.equal(platform.environment.PAYMENT_ALIPAY_WAP_ENABLED, 'false');
      assert.equal(platform.environment.PAYMENT_ALIPAY_WAP_APPLICATION_PRIVATE_KEY_RESOURCE,
        'file:/run/payment/alipay/wap/application-private-key.pem');
      assert.equal(platform.environment.PAYMENT_ALIPAY_APPLICATION_PRIVATE_KEY_RESOURCE,
        'file:/run/payment/alipay/application-private-key.pem');
      assert.equal(mount.type, 'bind');
      assert.equal(mount.read_only, true);
      assert.notEqual(mount.bind?.create_host_path, true);
      assert.ok(mount.source.replaceAll('\\', '/').endsWith('/.secrets/alipay'));
    } else {
      assert.equal(mount, undefined);
    }
    for (const [name, service] of Object.entries(config.services)) {
      if (name === 'platform-service') continue;
      assert.ok(!(service.volumes ?? []).some((item) => item.target === '/run/payment/alipay'),
        `${name} must not receive Alipay credentials`);
    }
  });
}
