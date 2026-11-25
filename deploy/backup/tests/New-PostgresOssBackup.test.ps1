Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot '..\New-PostgresOssBackup.ps1'
$result = & $scriptPath -RunId 'backupdryrun001' -DryRun `
    -Bucket 'yzh-api-backup-test1' -Endpoint 'https://oss-cn-beijing.aliyuncs.com'

if (-not $result.dryRun -or -not $result.verified) { throw 'backup_dry_run_not_verified' }
if ($result.manifestKey -notmatch '^database/postgresql/\d{4}/\d{2}/\d{2}/[^/]+/manifest\.json$') {
    throw 'invalid_manifest_key'
}
if ($result.dumpKey -ne $result.manifestKey.Replace('manifest.json', 'database.dump')) {
    throw 'dump_manifest_prefix_mismatch'
}
if (-not $result.backupId) { throw 'missing_backup_id' }
Write-Output 'New-PostgresOssBackup tests passed'
