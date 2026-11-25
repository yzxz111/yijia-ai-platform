param(
    [Parameter(Mandatory)] [string] $TargetDatabase,
    [Parameter(Mandatory)] [string] $ManifestPath,
    [string] $DatabaseHost = 'postgres',
    [string] $DatabaseUser = 'postgres',
    [string] $ComposeNetwork = 'yuzhihuihe-api-platform_default'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Backup.Common.ps1')
Assert-RestoreDatabaseName $TargetDatabase 'api_model_platform'
$manifest = Read-BackupManifest $ManifestPath
if ([string]::IsNullOrWhiteSpace($env:POSTGRES_PASSWORD)) { throw 'restore_credentials_missing' }
$environment = @{ PGPASSWORD = $env:POSTGRES_PASSWORD; FLYWAY_PASSWORD = $env:POSTGRES_PASSWORD }

$invariantSql = @'
select 'platform_version|' || coalesce(max(version),'0') from platform.flyway_schema_history where success=true;
select 'gateway_version|' || coalesce(max(version),'0') from gateway.flyway_schema_history where success=true;
select 'failed_flyway|' || ((select count(*) from platform.flyway_schema_history where not success) + (select count(*) from gateway.flyway_schema_history where not success));
select 'negative_balance|' || count(*) from platform.account_balance where available_amount < 0;
select 'invalid_quota|' || count(*) from gateway.package_instance where used_tokens < 0 or reserved_tokens < 0 or used_tokens + reserved_tokens > total_token_quota;
select 'unsafe_ready_file|' || count(*) from gateway.file_object where status='READY' and object_key not like 'clean/%';
select 'duplicate_usage_request|' || count(*) from (select request_id from gateway.usage_log group by request_id having count(*) > 1) d;
'@
$result = Invoke-CheckedCommand 'docker' @(
    'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
    'postgres:17-alpine', 'psql', '--host', $DatabaseHost,
    '--username', $DatabaseUser, '--dbname', $TargetDatabase,
    '-v', 'ON_ERROR_STOP=1', '-At', '-c', $invariantSql
) $environment 'restore_invariant_check_failed'
$values = @{}
foreach ($line in ($result.Stdout -split "`r?`n")) {
    if ($line -match '^([^|]+)\|(.+)$') { $values[$Matches[1]] = $Matches[2] }
}
if ($values.platform_version -ne [string] $manifest.platformFlywayVersion -or
        $values.gateway_version -ne [string] $manifest.gatewayFlywayVersion) {
    throw 'restore_flyway_version_mismatch'
}
foreach ($name in @('failed_flyway', 'negative_balance', 'invalid_quota', 'unsafe_ready_file', 'duplicate_usage_request')) {
    if ([long] $values[$name] -ne 0) { throw 'restore_invariant_failed' }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
foreach ($schema in @('platform', 'gateway')) {
    $migrationDir = if ($schema -eq 'platform') {
        Join-Path $projectRoot 'platform-service\src\main\resources\db\migration'
    } else {
        Join-Path $projectRoot 'gateway-service\src\main\resources\db\migration'
    }
    Invoke-CheckedCommand 'docker' @(
        'run', '--rm', '--network', $ComposeNetwork, '-e', 'FLYWAY_PASSWORD',
        '-v', "$migrationDir`:/flyway/sql:ro", 'flyway/flyway:11-alpine',
        "-url=jdbc:postgresql://$DatabaseHost`:5432/$TargetDatabase",
        "-user=$DatabaseUser", "-schemas=$schema", '-locations=filesystem:/flyway/sql', 'validate'
    ) $environment 'restore_flyway_validate_failed' | Out-Null
}

return [pscustomobject] @{
    flywayValidated = $true
    invariantsPassed = $true
    platformFlywayVersion = [string] $manifest.platformFlywayVersion
    gatewayFlywayVersion = [string] $manifest.gatewayFlywayVersion
    invariantCounts = $values
}
