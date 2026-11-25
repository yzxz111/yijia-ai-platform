import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = path => readFileSync(join(root, path), 'utf8')

test('notification center deployment exposes durable workflows with SMS disabled by default', () => {
  const migration = read('platform-service/src/main/resources/db/migration/V21__notification_center.sql')
  const compose = read('deploy/compose.yaml')
  const router = read('web-console/src/router/index.ts')

  for (const table of ['notification_event', 'notification_channel_delivery', 'notification_broadcast', 'support_ticket']) {
    assert.match(migration, new RegExp(`CREATE TABLE platform\\.${table}`, 'i'))
  }
  assert.match(compose, /SMS_ENABLED:\s*\$\{SMS_ENABLED:-false\}/)
  assert.match(compose, /SMS_PROVIDER:\s*\$\{SMS_PROVIDER:-aliyun\}/)
  assert.match(compose, /NOTIFICATION_FANOUT_BATCH_SIZE:/)
  assert.match(compose, /NOTIFICATION_BROADCAST_BATCH_SIZE:/)
  assert.match(router, /path:\s*'communications'/)
  assert.match(router, /AdminCommunicationsView\.vue/)
})
