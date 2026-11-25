import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const read = (path) => readFile(new URL(`../../${path}`, import.meta.url), 'utf8')

test('backup environment example is placeholder-only', async () => {
  const env = await read('deploy/env/.env.oss.backup.example')
  assert.match(env, /^OSS_BACKUP_BUCKET=yzh-api-backup-test1$/m)
  assert.match(env, /^OSS_BACKUP_ENDPOINT=https:\/\/oss-cn-beijing\.aliyuncs\.com$/m)

  for (const name of [
    'OSS_ACCESS_KEY_ID',
    'OSS_ACCESS_KEY_SECRET',
    'OSS_SESSION_TOKEN',
    'POSTGRES_PASSWORD',
    'PLATFORM_DB_PASSWORD',
    'GATEWAY_DB_PASSWORD',
  ]) {
    assert.match(env, new RegExp(`^${name}=$`, 'm'))
  }
})

test('runbook defines the backup bucket security and identity boundaries', async () => {
  const runbook = await read('docs/testing/aliyun-oss-backup-restore.md')
  for (const required of [
    'yzh-api-backup-test1',
    'private ACL',
    'block public access',
    'versioning enabled',
    'SSE-OSS',
    'HTTPS-only',
    'no CORS',
    'backup-writer',
    'restore-reader',
    'DeleteObjectVersion',
    'database/postgresql/{yyyy}/{MM}/{dd}/{timestamp}/database.dump',
    'drills/{drillId}/evidence.json',
    '30 days',
  ]) {
    assert.ok(runbook.includes(required), `missing runbook requirement: ${required}`)
  }
})

test('application compose remains independent from backup storage', async () => {
  const compose = await read('deploy/compose.yaml')
  assert.doesNotMatch(compose, /yzh-api-backup-test1|OSS_BACKUP_|backup-writer|restore-reader/)
})

test('generated backup bytes and local evidence are ignored', async () => {
  const ignore = await read('.gitignore')
  assert.match(ignore, /^deploy\/backup\/\.work\/$/m)
  assert.match(ignore, /^deploy\/backup\/\.downloads\/$/m)
  assert.match(ignore, /^deploy\/backup\/evidence\/\*\.local\.json$/m)
  assert.doesNotMatch(ignore, /\.env\.oss\.backup\.example/)
})

test('runbook covers focused acceptance, recovery and production boundaries', async () => {
  const runbook = await read('docs/testing/aliyun-oss-backup-restore.md')
  for (const required of [
    '## 前置检查',
    '## 执行备份',
    '## 隔离恢复',
    '## 权限负向验收',
    '## Evidence 与清理',
    '## 故障排查',
    'RPO',
    'RTO',
    'PITR',
    'Invoke-PostgresOssRestoreDrill.ps1',
    'api_model_platform_restore_',
  ]) {
    assert.ok(runbook.includes(required), `missing acceptance section: ${required}`)
  }

  const readme = await read('README.md')
  assert.match(readme, /aliyun-oss-backup-restore\.md/)
})

test('ossutil v2 uploads preserve unique keys without unsupported raw headers', async () => {
  const backup = await read('deploy/backup/New-PostgresOssBackup.ps1')
  const drill = await read('deploy/backup/Invoke-PostgresOssRestoreDrill.ps1')
  assert.match(backup, /--ignore-existing/)
  assert.match(drill, /--ignore-existing/)
  assert.match(backup, /OSS_REGION\s*=\s*'cn-beijing'/)
  assert.match(drill, /OSS_REGION\s*=\s*'cn-beijing'/)
  assert.doesNotMatch(backup, /--headers/)
  assert.doesNotMatch(drill, /--headers/)
})
