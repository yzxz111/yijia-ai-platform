Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot '..\Restore-PostgresOssBackup.ps1'
$manifestKey = 'database/postgresql/2026/08/10/20260810T100000Z/manifest.json'
$result = & $scriptPath -ManifestKey $manifestKey `
    -TargetDatabase 'api_model_platform_restore_dryrun001' -DryRun `
    -Bucket 'yzh-api-backup-test1' -Endpoint 'https://oss-cn-beijing.aliyuncs.com'
if (-not $result.dryRun -or $result.targetDatabase -ne 'api_model_platform_restore_dryrun001') {
    throw 'restore_dry_run_failed'
}

try {
    & $scriptPath -ManifestKey $manifestKey -TargetDatabase 'api_model_platform' -DryRun `
        -Bucket 'yzh-api-backup-test1' -Endpoint 'https://oss-cn-beijing.aliyuncs.com' | Out-Null
    throw 'unsafe_restore_name_was_accepted'
} catch {
    if ($_.Exception.Message -ne 'invalid_restore_database') { throw }
}
Write-Output 'Restore-PostgresOssBackup tests passed'
