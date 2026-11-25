import { chmodSync, chownSync, existsSync, mkdirSync, renameSync, writeFileSync } from 'node:fs'
import { generateKeyPairSync } from 'node:crypto'

const output = process.env.JWT_KEY_PATH ?? '/keys/platform-jwt-jwks.json'
const keyId = process.env.JWT_KEY_ID ?? 'local-platform-active'
// platform-service 以 uid 10001 运行（见 platform-service/Dockerfile），并以只读方式挂载本卷。
// 它是唯一的消费方，因此这里把属主交给该 uid，而不是放宽权限位：此处的文件一旦对所有人可读，
// 任何能读取该卷的进程都能签发有效的 role=ADMIN 令牌。
const keyOwnerUid = Number(process.env.JWT_KEY_OWNER_UID ?? 10001)

if (!existsSync(output)) {
  mkdirSync('/keys', { recursive: true })
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const jwk = privateKey.export({ format: 'jwk' })
  Object.assign(jwk, { kid: keyId, alg: 'RS256', use: 'sig' })
  const temporary = `${output}.tmp`
  writeFileSync(temporary, `${JSON.stringify({ keys: [jwk] })}\n`, { mode: 0o600 })
  renameSync(temporary, output)
}

// 每次运行都会重新施加权限，因此早期版本（0o644）写下的旧密钥会在下次启动时被收紧。
// chown 要先于 chmod 执行：只改权限位会把 platform-service 锁在外面。
try {
  chownSync(output, keyOwnerUid, keyOwnerUid)
} catch (error) {
  // 以非 root 身份生成密钥（或文件系统不支持属主）时不能中断启动；收紧的权限位依旧会被施加，
  // 而且该卷只由 platform-service 挂载。
  console.warn(`generate-jwt-key: could not chown ${output} to uid ${keyOwnerUid}: ${error.message}`)
}
chmodSync(output, 0o600)
