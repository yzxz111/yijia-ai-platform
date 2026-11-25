// Local Windows runner. Dependencies and persistent data live outside the source tree.
import { existsSync, readFileSync, writeFileSync, mkdirSync, readdirSync, openSync, closeSync, cpSync } from 'node:fs'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomBytes, generateKeyPairSync, createHash, createHmac } from 'node:crypto'
import { spawn, spawnSync } from 'node:child_process'
import net from 'node:net'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const runtime = process.env.YIJIA_LOCAL_RUNTIME || resolve(root, '../../../yijia-local-runtime')
const tools = join(runtime, 'tools')
const logs = join(runtime, 'logs')
const secrets = join(runtime, '.secrets')
const stateFile = join(runtime, 'processes.json')
const envFile = join(root, 'deploy/env/.env')
for (const p of [runtime, tools, logs, secrets, join(runtime, 'data')]) mkdirSync(p, { recursive: true })
const state = existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, 'utf8')) : {}
const save = () => writeFileSync(stateFile, JSON.stringify(state, null, 2))
const directory = prefix => join(tools, readdirSync(tools).find(n => n.startsWith(prefix)) || `MISSING-${prefix}`)
const java = join(directory('jdk-21'), 'bin/java.exe')
const pg = join(tools, 'pgsql/bin')
const pgdata = join(runtime, 'data/postgres')
const delay = ms => new Promise(r => setTimeout(r, ms))

function run(exe, args, options = {}) {
  const r = spawnSync(exe, args, { cwd: root, encoding: 'utf8', windowsHide: true, ...options })
  if (r.error) throw r.error
  if (r.status !== 0) throw new Error(`${exe} failed (${r.status}): ${(r.stderr || r.stdout || '').slice(-4000)}`)
  return (r.stdout || '').trim()
}
function parseEnv(text) {
  return Object.fromEntries(text.split(/\r?\n/).filter(l => /^[A-Z][A-Z0-9_]*=/.test(l)).map(l => {
    const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1)]
  }))
}
function setupEnv() {
  if (!existsSync(envFile)) {
    let text = readFileSync(join(root, 'deploy/env/.env.example'), 'utf8')
    const set = (k, v) => {
      const pattern = new RegExp(`^${k}=.*$`, 'm')
      text = pattern.test(text) ? text.replace(pattern, `${k}=${v}`) : `${text.trimEnd()}\n${k}=${v}\n`
    }
    for (const k of ['POSTGRES_PASSWORD', 'PLATFORM_DB_PASSWORD', 'GATEWAY_DB_PASSWORD', 'REDIS_PASSWORD',
      'MINIO_ROOT_PASSWORD', 'SECURITY_INTERNAL_HMAC_SECRET', 'SECURITY_API_KEY_PEPPER',
      'DOCUMENT_CONVERTER_AUTH_TOKEN', 'PAYMENT_MOCK_CALLBACK_SECRET', 'LOGIN_RISK_PEPPER',
      'PASSWORD_LOGIN_RATE_LIMIT_HMAC_KEY', 'EMAIL_CODE_PEPPER', 'SMS_CODE_PEPPER',
      'SMS_PHONE_LOOKUP_KEY', 'SMS_REQUEST_IP_KEY']) set(k, randomBytes(32).toString('hex'))
    for (const k of ['GATEWAY_SECRETS_ENCRYPTION_KEY', 'SMS_ENCRYPTION_KEY']) set(k, randomBytes(32).toString('base64'))
    set('LOCAL_ADMIN_PASSWORD', `Local!${randomBytes(12).toString('hex')}`)
    set('VITE_API_BASE_URL', 'http://127.0.0.1:18081/v1')
    set('GATEWAY_PUBLIC_BASE_URL', 'http://127.0.0.1:18081')
    set('SUBSCRIPTION_JOBS_ENABLED', 'false')
    writeFileSync(envFile, text, { mode: 0o600 })
    console.log('Created deploy/env/.env with independent local secrets (values hidden).')
  }
  const env = parseEnv(readFileSync(envFile, 'utf8'))
  const jwks = join(secrets, 'platform-jwt-jwks.json')
  if (!existsSync(jwks)) {
    const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
    const key = privateKey.export({ format: 'jwk' })
    Object.assign(key, { kid: env.SECURITY_JWT_ACTIVE_KEY_ID, alg: 'RS256', use: 'sig' })
    writeFileSync(jwks, JSON.stringify({ keys: [key] }), { mode: 0o600 })
  }
  return env
}
const env = setupEnv()
const pgEnv = { ...process.env, PGPASSWORD: env.POSTGRES_PASSWORD, PGCLIENTENCODING: 'UTF8' }
function psql(sql, database = 'api_model_platform') {
  return run(join(pg, 'psql.exe'), ['-h', '127.0.0.1', '-p', '55432', '-U', 'postgres', '-d', database,
    '-v', 'ON_ERROR_STOP=1', '-X', '-tA', '-c', sql], { env: pgEnv })
}
async function portOpen(port) {
  return new Promise(resolve => {
    const s = net.createConnection({ host: '127.0.0.1', port })
    s.setTimeout(700)
    const done = result => { s.destroy(); resolve(result) }
    s.once('connect', () => done(true)); s.once('error', () => done(false)); s.once('timeout', () => done(false))
  })
}
async function waitFor(check, label, timeout = 180000) {
  const until = Date.now() + timeout
  while (Date.now() < until) {
    try { if (await check()) { console.log(`${label}: ready`); return } } catch {}
    await delay(1500)
  }
  throw new Error(`${label} did not become ready. Inspect ${logs}`)
}
async function healthy(url) {
  const r = await fetch(url, { signal: AbortSignal.timeout(2500) })
  return r.ok && (await r.json()).status === 'UP'
}
function launch(name, exe, args, extraEnv = {}, cwd = root) {
  const out = openSync(join(logs, `${name}.log`), 'a')
  const err = openSync(join(logs, `${name}.err.log`), 'a')
  const child = spawn(exe, args, { cwd, env: { ...process.env, ...extraEnv }, detached: true,
    windowsHide: true, stdio: ['ignore', out, err] })
  closeSync(out); closeSync(err)
  child.unref()
  state[name] = { pid: child.pid, exe, startedAt: new Date().toISOString() }
  save()
  console.log(`${name}: started, pid ${child.pid}`)
}
async function postgres() {
  if (!existsSync(join(pgdata, 'PG_VERSION'))) {
    const passwordFile = join(secrets, 'postgres-password.txt')
    writeFileSync(passwordFile, env.POSTGRES_PASSWORD, { mode: 0o600 })
    run(join(pg, 'initdb.exe'), ['-D', pgdata, '-U', 'postgres', '--encoding=UTF8', '--locale=C',
      '--auth-host=scram-sha-256', '--auth-local=scram-sha-256', `--pwfile=${passwordFile}`])
    console.log('Initialized independent PostgreSQL data directory.')
  }
  if (!(await portOpen(55432))) {
    run(join(pg, 'pg_ctl.exe'), ['-D', pgdata, '-l', join(logs, 'postgres.log'), '-o',
      '-h 127.0.0.1 -p 55432 -c shared_buffers=64MB -c max_connections=80', '-w', 'start'], { stdio: 'ignore' })
  }
  const current = psql('SHOW data_directory;', 'postgres')
  if (resolve(current).toLowerCase() !== resolve(pgdata).toLowerCase()) throw new Error('Port 55432 belongs to another database; refusing to modify it.')
  if (!psql("SELECT 1 FROM pg_database WHERE datname='api_model_platform'", 'postgres')) psql('CREATE DATABASE api_model_platform;', 'postgres')
  // The init script revokes bootstrap privileges. Never reapply it after Flyway has granted the read-only status view.
  if (psql("SELECT to_regclass('platform.flyway_schema_history') IS NOT NULL") === 't') {
    console.log('PostgreSQL: ready; existing migrated database retained.')
    return
  }
  let init = readFileSync(join(root, 'deploy/postgres/init.sql'), 'utf8')
  init = init.replaceAll('local-platform-password', env.PLATFORM_DB_PASSWORD).replaceAll('local-gateway-password', env.GATEWAY_DB_PASSWORD)
  const initFile = join(secrets, 'init.sql')
  writeFileSync(initFile, init, { mode: 0o600 })
  run(join(pg, 'psql.exe'), ['-h', '127.0.0.1', '-p', '55432', '-U', 'postgres', '-d', 'api_model_platform',
    '-v', 'ON_ERROR_STOP=1', '-f', initFile], { env: pgEnv })
  console.log('PostgreSQL: ready; schemas and extensions initialized.')
}
async function storage() {
  const minio = join(tools, 'minio.exe')
  if (existsSync(minio)) {
    if (!(await portOpen(59000))) launch('minio', minio, ['server', join(runtime, 'data/minio'),
      '--address', '127.0.0.1:59000', '--console-address', '127.0.0.1:59001', '--quiet'], {
      MINIO_ROOT_USER: env.MINIO_ROOT_USER, MINIO_ROOT_PASSWORD: env.MINIO_ROOT_PASSWORD,
      MINIO_BROWSER: 'off', GOMAXPROCS: '2'
    }, runtime)
    await waitFor(async () => (await fetch('http://127.0.0.1:59000/minio/health/ready')).ok, 'MinIO')
    const host = '127.0.0.1:59000'
    const path = `/${env.STORAGE_FILES_BUCKET}`
    const date = new Date().toISOString().replace(/[-:]|\.\d{3}/g, '')
    const short = date.slice(0, 8)
    const digest = value => createHash('sha256').update(value).digest('hex')
    const hmac = (key, value) => createHmac('sha256', key).update(value).digest()
    const bodyHash = digest('')
    const scope = `${short}/${env.STORAGE_FILES_REGION}/s3/aws4_request`
    const signed = 'host;x-amz-content-sha256;x-amz-date'
    const headers = `host:${host}\nx-amz-content-sha256:${bodyHash}\nx-amz-date:${date}\n`
    const canonical = ['PUT', path, '', headers, signed, bodyHash].join('\n')
    let key = hmac(`AWS4${env.MINIO_ROOT_PASSWORD}`, short)
    for (const component of [env.STORAGE_FILES_REGION, 's3', 'aws4_request']) key = hmac(key, component)
    const signature = hmac(key, `AWS4-HMAC-SHA256\n${date}\n${scope}\n${digest(canonical)}`).toString('hex')
    const r = await fetch(`http://${host}${path}`, { method: 'PUT', headers: {
      'x-amz-content-sha256': bodyHash, 'x-amz-date': date,
      Authorization: `AWS4-HMAC-SHA256 Credential=${env.MINIO_ROOT_USER}/${scope}, SignedHeaders=${signed}, Signature=${signature}`
    } })
    if (!r.ok && !(r.status === 409 && (await r.text()).includes('BucketAlreadyOwnedByYou'))) throw new Error(`Local bucket initialization failed (${r.status})`)
    console.log('Private local storage bucket: ready')
  }
  const clamRoot = join(tools, 'clamav/clamav-1.4.3.win.x64')
  if (existsSync(join(clamRoot, 'clamd.exe')) && existsSync(join(runtime, 'data/clamav/daily.cvd'))) {
    if (!(await portOpen(3310))) {
      const config = join(runtime, 'clamd.conf')
      writeFileSync(config, [`DatabaseDirectory ${join(runtime, 'data/clamav')}`, `LogFile ${join(logs, 'clamav.log')}`,
        'TCPSocket 3310', 'TCPAddr 127.0.0.1', 'MaxThreads 2', 'StreamMaxLength 200M', 'MaxFileSize 200M',
        'MaxScanSize 500M', 'Foreground yes'].join('\n') + '\n')
      launch('clamav', join(clamRoot, 'clamd.exe'), ['--config-file', config], {}, runtime)
      await waitFor(() => portOpen(3310), 'ClamAV scanner')
    }
  }
}
async function redis() {
  if (await portOpen(56379)) return
  const defaults = {
    Address: '127.0.0.1', Port: 56379, LogMemorySize: '128m', PageSize: '4m', SegmentSize: '64m',
    IndexMemorySize: '16m', BufferPoolMemoryBudget: '32m', EnableLua: true, LuaTransactionMode: true,
    AuthenticationMode: 'Password', Password: env.REDIS_PASSWORD, EnableStorageTier: true, EnableAOF: true, Recover: true,
    LogDir: join(runtime, 'data/redis'), CheckpointDir: join(runtime, 'data/redis/checkpoints')
  }
  const config = join(secrets, 'garnet.json')
  writeFileSync(config, JSON.stringify(defaults, null, 2), { mode: 0o600 })
  launch('redis', join(tools, 'garnet/net10.0/GarnetServer.exe'), ['--config-import-path', config],
    { DOTNET_ROOT: join(tools, 'dotnet') }, runtime)
  await waitFor(() => portOpen(56379), 'Redis-compatible service')
}
const common = {
  ...env, SPRING_PROFILES_ACTIVE: 'local', SERVER_ADDRESS: '127.0.0.1',
  SPRING_DATA_REDIS_HOST: '127.0.0.1', SPRING_DATA_REDIS_PORT: '56379',
  SPRING_DATA_REDIS_PASSWORD: env.REDIS_PASSWORD,
  SECURITY_JWT_JWK_SET_LOCATION: pathToFileURL(join(secrets, 'platform-jwt-jwks.json')).href,
  SECURITY_JWT_JWK_SET_URI: 'http://127.0.0.1:8080/.well-known/jwks.json',
  SECURITY_COOKIES_SECURE: 'false', GATEWAY_INTERNAL_BASE_URL: 'http://127.0.0.1:8081',
  GATEWAY_CONSOLE_TOKEN_VALIDATION_URI: 'http://127.0.0.1:8080/console/platform/auth/validate',
  GATEWAY_TERMINAL_DEVICE_VALIDATION_URI: 'http://127.0.0.1:8080/console/platform/auth/terminal-device/validate',
  GATEWAY_ECONOMICS_PLATFORM_URL: 'http://127.0.0.1:8080',
  PLATFORM_SMS_INTERNAL_BASE_URL: 'http://127.0.0.1:8080',
  PLATFORM_CONTENT_SAFETY_INTERNAL_BASE_URL: 'http://127.0.0.1:8080',
  STORAGE_FILES_ENDPOINT: 'http://127.0.0.1:59000', STORAGE_FILES_ACCESS_KEY: env.MINIO_ROOT_USER,
  STORAGE_FILES_SECRET_KEY: env.MINIO_ROOT_PASSWORD,
  CONTENT_SAFETY_SEMANTIC_BASE_URL: 'http://127.0.0.1:19090',
  CONTENT_SAFETY_CLAMAV_HOST: '127.0.0.1', GATEWAY_CHANNEL_LOCAL_HOST_ALLOWLIST: '127.0.0.1,localhost',
  DOCUMENT_CONVERTER_BASE_URL: 'http://127.0.0.1:19091', DOCUMENT_CONVERTER_ALLOW_INSECURE_LOCAL: 'true',
  DOCUMENT_CONVERTER_ALLOWED_HOSTS: '127.0.0.1', GATEWAY_SKILLS_ROOT: join(root, 'skills'),
  GEOIP_V4_DATA_SOURCE: join(root, 'deploy/geoip/ip2region_v4.xdb'),
  GEOIP_V6_DATA_SOURCE: join(root, 'deploy/geoip/ip2region_v6.xdb'),
  SMS_ENABLED: 'false', EMAIL_ENABLED: 'false', SUBSCRIPTION_JOBS_ENABLED: 'false'
}
async function backend(name, port, scheduler = true) {
  if (await portOpen(port)) return
  const schema = name === 'platform-service' ? 'platform' : 'gateway'
  const jdbc = `jdbc:postgresql://127.0.0.1:55432/api_model_platform?currentSchema=${schema}`
  const specific = schema === 'platform' ? {
    SPRING_DATASOURCE_URL: jdbc, SPRING_DATASOURCE_USERNAME: 'platform_app',
    SPRING_DATASOURCE_PASSWORD: env.PLATFORM_DB_PASSWORD
  } : {
    SPRING_R2DBC_URL: 'r2dbc:postgresql://127.0.0.1:55432/api_model_platform?schema=gateway',
    SPRING_R2DBC_USERNAME: 'gateway_app', SPRING_R2DBC_PASSWORD: env.GATEWAY_DB_PASSWORD,
    SPRING_FLYWAY_URL: jdbc, SPRING_FLYWAY_USER: 'gateway_app', SPRING_FLYWAY_PASSWORD: env.GATEWAY_DB_PASSWORD,
    GATEWAY_SCHEDULING_ENABLED: String(scheduler)
  }
  const jar = join(root, name, `target/${name}-0.0.1-SNAPSHOT.jar`)
  if (!existsSync(jar)) throw new Error(`Missing built application: ${jar}`)
  launch(name, java, ['-Xms128m', '-Xmx512m', '-XX:ActiveProcessorCount=4', '-Dfile.encoding=UTF-8', '-jar', jar], {
    ...common, ...specific, SERVER_PORT: String(port), SPRING_FLYWAY_SCHEMAS: schema, SPRING_FLYWAY_DEFAULT_SCHEMA: schema
  })
  await waitFor(() => healthy(`http://127.0.0.1:${port}/actuator/health/readiness`), name)
}
function bootstrap() {
  let sql = readFileSync(join(root, 'deploy/postgres/bootstrap-local.sql'), 'utf8')
    .replaceAll(":'admin_email'", `'${env.LOCAL_ADMIN_EMAIL.replaceAll("'", "''")}'`)
    .replaceAll(":'admin_password'", `'${env.LOCAL_ADMIN_PASSWORD.replaceAll("'", "''")}'`)
    .replaceAll('http://wiremock:8080', 'http://127.0.0.1:19090')
  // This database was created above and is dedicated to local development.
  sql += "\nUPDATE gateway.channel SET enabled=false WHERE environment <> 'LOCAL';\n"
  const file = join(secrets, 'bootstrap-local.sql')
  writeFileSync(file, sql, { mode: 0o600 })
  run(join(pg, 'psql.exe'), ['-h', '127.0.0.1', '-p', '55432', '-U', 'postgres', '-d', 'api_model_platform',
    '-v', 'ON_ERROR_STOP=1', '-f', file], { env: pgEnv })
  console.log('Local administrator and local-only model channels initialized.')
}
function nginx() {
  const nginxRoot = directory('nginx-')
  cpSync(join(root, 'web-console/dist'), join(runtime, 'www'), { recursive: true })
  let text = readFileSync(join(root, 'deploy/nginx/nginx.conf'), 'utf8')
    .replace('worker_processes auto;', 'worker_processes 1;')
    .replace('include /etc/nginx/mime.types;', `include "${join(nginxRoot, 'conf/mime.types').replaceAll('\\', '/')}";`)
    .replace('resolver 127.0.0.11 valid=5s ipv6=off;', '')
    .replace('server platform-service:8080 resolve;', 'server 127.0.0.1:8080;')
    .replace('server gateway-service:8080 resolve;', 'server 127.0.0.1:8081;')
    .replace('server web-console:8080 resolve;', 'server 127.0.0.1:18083;')
    .replace('listen 8080;', 'listen 127.0.0.1:18081;')
  const site = `\n    server {\n        listen 127.0.0.1:18083;\n        root "${join(runtime, 'www').replaceAll('\\', '/')}";\n        location / { try_files $uri $uri/ /index.html; }\n    }\n`
  text = text.replace(/}\s*$/, `${site}}\n`)
  const config = join(runtime, 'nginx.conf')
  writeFileSync(config, text)
  run(join(nginxRoot, 'nginx.exe'), ['-t', '-p', `${nginxRoot}/`, '-c', config])
  launch('nginx', join(nginxRoot, 'nginx.exe'), ['-p', `${nginxRoot}/`, '-c', config], {}, nginxRoot)
}
async function stopOne(name) {
  const s = state[name]
  if (!s?.pid) return
  try {
    if (name === 'nginx') {
      run(s.exe, ['-p', `${dirname(s.exe)}/`, '-c', join(runtime, 'nginx.conf'), '-s', 'quit'])
      delete state[name]; save(); return
    }
    const path = run('powershell.exe', ['-NoProfile', '-Command', `(Get-CimInstance Win32_Process -Filter 'ProcessId = ${Number(s.pid)}').ExecutablePath`])
    if (path.toLowerCase() !== s.exe.toLowerCase()) throw new Error(`Process identity changed for ${name}; refusing to stop it.`)
    process.kill(s.pid)
  } catch (e) { if (!/ESRCH/.test(e.message)) console.warn(e.message) }
  delete state[name]; save()
}
async function dev() {
  if (!(await portOpen(5178))) {
    const vite = join(root, 'web-console/node_modules/vite/bin/vite.js')
    launch('dev', process.execPath, [vite, '--host', '127.0.0.1', '--port', '5178', '--strictPort'], {
      SYSTEM_API_PROXY_TARGET: 'http://127.0.0.1:18081', VITE_API_BASE_URL: 'http://127.0.0.1:18081/v1'
    }, join(root, 'web-console'))
    await waitFor(() => portOpen(5178), 'Development preview with local API proxy')
  }
}
async function start() {
  await postgres(); await redis()
  await storage()
  if (!(await portOpen(19090))) {
    launch('wiremock', java, ['-Xms32m', '-Xmx160m', '-jar', join(tools, 'wiremock.jar'),
      '--bind-address', '127.0.0.1', '--port', '19090', '--root-dir', join(root, 'deploy/wiremock'), '--global-response-templating'])
    await waitFor(() => portOpen(19090), 'Local model fixtures')
  }
  await backend('platform-service', 8080)
  const hasBootstrap = existsSync(join(runtime, 'bootstrap-complete'))
  await backend('gateway-service', 8081, hasBootstrap)
  bootstrap()
  if (!hasBootstrap) {
    writeFileSync(join(runtime, 'bootstrap-complete'), new Date().toISOString())
    await stopOne('gateway-service')
    await waitFor(async () => !(await portOpen(8081)), 'Gateway stopped for local channel activation', 20000)
    await backend('gateway-service', 8081, true)
  }
  if (!(await portOpen(18081))) nginx()
  await waitFor(() => healthy('http://127.0.0.1:18081/health/platform'), 'Platform through local proxy')
  await waitFor(() => healthy('http://127.0.0.1:18081/health/gateway'), 'Gateway through local proxy')
  await dev()
  console.log('Console: http://127.0.0.1:18081/login')
  console.log(`Administrator: ${env.LOCAL_ADMIN_EMAIL}; password is in deploy/env/.env (not printed).`)
}
const command = process.argv[2] || 'status'
try {
  if (command === 'start') await start()
  else if (command === 'dev') await dev()
  else if (command === 'init') await postgres()
  else if (command === 'status') {
    for (const [label, port] of Object.entries({ postgres: 55432, redis: 56379, wiremock: 19090, platform: 8080, gateway: 8081, nginx: 18081, minio: 59000, clamav: 3310 })) {
      console.log(`${label}: ${await portOpen(port) ? 'listening' : 'stopped'}`)
    }
  } else if (command === 'stop') {
    for (const name of ['dev', 'nginx', 'gateway-service', 'platform-service', 'wiremock', 'clamav', 'minio', 'redis']) await stopOne(name)
    if (existsSync(join(pgdata, 'postmaster.pid'))) run(join(pg, 'pg_ctl.exe'), ['-D', pgdata, '-m', 'fast', '-w', 'stop'])
    console.log('Local processes stopped. Persistent data retained.')
  } else throw new Error('Usage: node scripts/local-windows.mjs start|stop|status|init|dev')
} catch (e) { console.error(e.message); process.exitCode = 1 }
