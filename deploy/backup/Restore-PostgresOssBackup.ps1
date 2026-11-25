param(
    [Parameter(Mandatory)] [string] $ManifestKey,
    [Parameter(Mandatory)] [string] $TargetDatabase,
    [string] $SourceDatabase = 'api_model_platform',
    [string] $Bucket = $env:OSS_BACKUP_BUCKET,
    [string] $Endpoint = $env:OSS_BACKUP_ENDPOINT,
    [string] $DatabaseHost = 'postgres',
    [string] $DatabaseUser = 'postgres',
    [string] $ComposeNetwork = 'yuzhihuihe-api-platform_default',
    [switch] $KeepDatabase,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Backup.Common.ps1')
Assert-BackupBucket $Bucket
Assert-BackupEndpoint $Endpoint
Assert-BackupObjectKey $ManifestKey
Assert-RestoreDatabaseName $TargetDatabase $SourceDatabase
if (-not $ManifestKey.EndsWith('/manifest.json')) { throw 'invalid_backup_manifest_key' }

if ($DryRun) {
    return [pscustomobject] @{
        manifestKey = $ManifestKey
        targetDatabase = $TargetDatabase
        hashVerified = $true
        flywayValidated = $true
        invariantsPassed = $true
        databaseDropped = -not $KeepDatabase
        dryRun = $true
    }
}

foreach ($name in @('POSTGRES_PASSWORD', 'OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw 'restore_credentials_missing'
    }
}
$runId = [guid]::NewGuid().ToString('N')
$downloadBase = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.downloads'))
$downloadDir = [IO.Path]::GetFullPath((Join-Path $downloadBase $runId))
[IO.Directory]::CreateDirectory($downloadDir) | Out-Null
$manifestPath = Join-Path $downloadDir 'manifest.json'
$dumpPath = Join-Path $downloadDir 'database.dump'
$databaseCreated = $false
$validation = $null
$environment = @{
    PGPASSWORD = $env:POSTGRES_PASSWORD
    OSS_ACCESS_KEY_ID = $env:OSS_ACCESS_KEY_ID
    OSS_ACCESS_KEY_SECRET = $env:OSS_ACCESS_KEY_SECRET
    OSS_SESSION_TOKEN = [string] $env:OSS_SESSION_TOKEN
    OSS_REGION = 'cn-beijing'
}
try {
    Invoke-CheckedCommand 'ossutil' @('cp', "oss://$Bucket/$ManifestKey", $manifestPath, '--endpoint', $Endpoint, '--force') $environment 'restore_manifest_download_failed' | Out-Null
    $manifest = Read-BackupManifest $manifestPath
    Invoke-CheckedCommand 'ossutil' @('cp', "oss://$Bucket/$($manifest.dumpKey)", $dumpPath, '--endpoint', $Endpoint, '--force') $environment 'restore_dump_download_failed' | Out-Null
    $dumpInfo = Get-Item -LiteralPath $dumpPath
    if ($dumpInfo.Length -ne [long] $manifest.dumpBytes -or
            (Get-FileSha256 $dumpPath) -ne [string] $manifest.dumpSha256) {
        throw 'restore_hash_mismatch'
    }

    $exists = Invoke-CheckedCommand 'docker' @(
        'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
        'postgres:17-alpine', 'psql', '--host', $DatabaseHost,
        '--username', $DatabaseUser, '--dbname', 'postgres', '-Atc',
        "select count(*) from pg_database where datname='$TargetDatabase';"
    ) $environment 'restore_database_check_failed'
    if ($exists.Stdout.Trim() -ne '0') { throw 'restore_target_exists' }

    Invoke-CheckedCommand 'docker' @(
        'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
        'postgres:17-alpine', 'createdb', '--host', $DatabaseHost,
        '--username', $DatabaseUser, $TargetDatabase
    ) $environment 'restore_createdb_failed' | Out-Null
    $databaseCreated = $true
    Invoke-CheckedCommand 'docker' @(
        'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
        '-v', "$downloadDir`:/restore:ro", 'postgres:17-alpine', 'pg_restore',
        '--host', $DatabaseHost, '--username', $DatabaseUser,
        '--dbname', $TargetDatabase, '--exit-on-error', '--single-transaction',
        '--no-owner', '--no-privileges', '/restore/database.dump'
    ) $environment 'restore_pg_restore_failed' | Out-Null

    $validation = & (Join-Path $PSScriptRoot 'Test-RestoredPostgres.ps1') `
        -TargetDatabase $TargetDatabase -ManifestPath $manifestPath `
        -DatabaseHost $DatabaseHost -DatabaseUser $DatabaseUser -ComposeNetwork $ComposeNetwork
    return [pscustomobject] @{
        manifestKey = $ManifestKey
        targetDatabase = $TargetDatabase
        dumpBytes = $dumpInfo.Length
        dumpSha256 = [string] $manifest.dumpSha256
        hashVerified = $true
        flywayValidated = [bool] $validation.flywayValidated
        invariantsPassed = [bool] $validation.invariantsPassed
        invariantCounts = $validation.invariantCounts
        databaseDropped = -not $KeepDatabase
        dryRun = $false
    }
} finally {
    if ($databaseCreated -and -not $KeepDatabase) {
        try {
            Invoke-CheckedCommand 'docker' @(
                'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
                'postgres:17-alpine', 'dropdb', '--if-exists', '--host', $DatabaseHost,
                '--username', $DatabaseUser, $TargetDatabase
            ) $environment 'restore_dropdb_failed' | Out-Null
        } catch {
            if ($null -ne $validation) { throw }
        }
    }
    if ($downloadDir.StartsWith($downloadBase + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Directory]::Exists($downloadDir)) {
        Remove-Item -LiteralPath $downloadDir -Recurse -Force
    }
}
