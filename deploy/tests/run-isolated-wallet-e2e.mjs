import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import net from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDirectory = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDirectory, '..', '..');
const composeFile = join(projectRoot, 'deploy', 'compose.yaml');
const webConsoleDirectory = join(projectRoot, 'web-console');
const playwrightCli = join(webConsoleDirectory, 'node_modules', '@playwright', 'test', 'cli.js');
const composeProject = `yzh-wallet-e2e-${process.pid}-${randomUUID().slice(0, 8)}`;
const hostPort = await reserveHostPort();
const composeEnvironment = {
  ...process.env,
  NGINX_PORT: String(hostPort),
  PAYMENT_WECHAT_PAY_ENABLED: 'false',
  PAYMENT_ALIPAY_ENABLED: 'false',
  SMS_ENABLED: 'false',
};

let exitCode = 1;
const sharedWalletFactsBefore = querySharedWalletFacts();

try {
  run('docker', [
    'compose', '--project-name', composeProject, '-f', composeFile,
    'up', '-d', '--no-build', '--wait', '--wait-timeout', '240',
  ], projectRoot, composeEnvironment);

  run(process.execPath, [
    playwrightCli, 'test', 'tests/e2e/wallet-workflows.spec.ts',
  ], webConsoleDirectory, {
    ...composeEnvironment,
    E2E_BASE_URL: `http://127.0.0.1:${hostPort}`,
    E2E_COMPOSE_PROJECT_NAME: composeProject,
    WALLET_E2E_ISOLATED: 'true',
  });
  exitCode = 0;
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
} finally {
  const down = command('docker', [
    'compose', '--project-name', composeProject, '-f', composeFile,
    'down', '--volumes', '--remove-orphans', '--timeout', '10',
  ], projectRoot, composeEnvironment);
  if (down.status !== 0) {
    console.error(`isolated Compose teardown failed with exit code ${down.status}`);
    exitCode = 1;
  }

  const remainingContainers = output('docker', [
    'compose', '--project-name', composeProject, '-f', composeFile, 'ps', '-aq',
  ], projectRoot, composeEnvironment);
  const remainingVolumes = output('docker', [
    'volume', 'ls', '--quiet', '--filter', `label=com.docker.compose.project=${composeProject}`,
  ], projectRoot, composeEnvironment);
  if (remainingContainers || remainingVolumes) {
    console.error('isolated Compose resources remain after teardown');
    exitCode = 1;
  }

  const sharedWalletFactsAfter = querySharedWalletFacts();
  if (sharedWalletFactsBefore !== null
      && sharedWalletFactsAfter !== sharedWalletFactsBefore) {
    console.error(
      `shared wallet E2E facts changed: ${sharedWalletFactsBefore} -> ${sharedWalletFactsAfter}`,
    );
    exitCode = 1;
  }
}

process.exitCode = exitCode;

function run(executable, args, cwd, env) {
  const result = command(executable, args, cwd, env);
  if (result.status !== 0) {
    throw new Error(result.error?.message
      || `${executable} failed with exit code ${result.status}`);
  }
}

function command(executable, args, cwd, env) {
  return spawnSync(executable, args, {
    cwd,
    env,
    stdio: 'inherit',
    windowsHide: true,
  });
}

function output(executable, args, cwd, env) {
  const result = spawnSync(executable, args, {
    cwd,
    env,
    encoding: 'utf8',
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(result.stderr || `${executable} failed with exit code ${result.status}`);
  }
  return result.stdout.trim();
}

function querySharedWalletFacts() {
  const postgresContainer = output(
    'docker', ['compose', '-f', composeFile, 'ps', '-q', 'postgres'],
    projectRoot, process.env,
  );
  if (!postgresContainer) return null;
  return output('docker', [
    'compose', '-f', composeFile, 'exec', '-T', 'postgres',
    'psql', '-U', 'postgres', '-d', 'api_model_platform', '-X', '-A', '-t',
    '-c', `SELECT
      (SELECT count(*) FROM platform.app_user users
        WHERE users.email LIKE 'e2e-wallet-%@local.test')
      || '|' ||
      (SELECT count(*) FROM platform.balance_ledger ledger
        JOIN platform.app_user users ON users.id = ledger.user_id
        WHERE users.email LIKE 'e2e-wallet-%@local.test')`,
  ], projectRoot, process.env);
}

function reserveHostPort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') {
        server.close();
        reject(new Error('failed to reserve an isolated wallet E2E port'));
        return;
      }
      server.close((error) => {
        if (error) reject(error);
        else resolvePort(address.port);
      });
    });
  });
}
