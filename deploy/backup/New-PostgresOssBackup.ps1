param(
    [string] $ComposeFile = 'deploy/compose.yaml',
    [string] $Database = 'api_model_platform',
    [string] $DatabaseHost = 'postgres',
    [string] $DatabaseUser = 'postgres',
    [string] $Bucket = $env:OSS_BACKUP_BUCKET,
    [string] $Endpoint = $env:OSS_BACKUP_ENDPOINT,
    [string] $RunId = ([guid]::NewGuid().ToString('N')),
    [string] $ComposeNetwork = 'yuzhihuihe-api-platform_default',
    [long] $MaxDumpBytes = 2147483648,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Backup.Common.ps1')

Assert-BackupBucket $Bucket
Assert-BackupEndpoint $Endpoint
if ($Database -ne 'api_model_platform' -or $RunId -notmatch '^[a-z0-9]{8,64}$') {
    throw 'invalid_backup_parameters'
}

$createdAt = [DateTimeOffset]::UtcNow
$stamp = $createdAt.ToString('yyyyMMddTHHmmssZ') + '-' + $RunId
$prefix = 'database/postgresql/{0}/{1}/{2}/{3}' -f `
    $createdAt.ToString('yyyy'), $createdAt.ToString('MM'), $createdAt.ToString('dd'), $stamp
$dumpKey = "$prefix/database.dump"
$manifestKey = "$prefix/manifest.json"
Assert-BackupObjectKey $dumpKey
Assert-BackupObjectKey $manifestKey
$backupId = [guid]::NewGuid().ToString()

if ($DryRun) {
    return [pscustomobject] @{
        backupId = $backupId
        manifestKey = $manifestKey
        dumpKey = $dumpKey
        dumpBytes = 1
        dumpSha256 = '0' * 64
        platformFlywayVersion = '0'
        gatewayFlywayVersion = '0'
        verified = $true
        dryRun = $true
    }
}

foreach ($name in @('POSTGRES_PASSWORD', 'OSS_ACCESS_KEY_ID', 'OSS_ACCESS_KEY_SECRET')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw 'backup_credentials_missing'
    }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$workBase = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.work'))
$downloadBase = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.downloads'))
$workDir = [IO.Path]::GetFullPath((Join-Path $workBase $RunId))
$downloadDir = [IO.Path]::GetFullPath((Join-Path $downloadBase $RunId))
$dumpPath = Join-Path $workDir 'database.dump'
$manifestPath = Join-Path $workDir 'manifest.json'
$verifiedDumpPath = Join-Path $downloadDir 'database.dump'
$verifiedManifestPath = Join-Path $downloadDir 'manifest.json'
[IO.Directory]::CreateDirectory($workDir) | Out-Null
[IO.Directory]::CreateDirectory($downloadDir) | Out-Null

$success = $false
try {
    $processEnvironment = @{
        PGPASSWORD = $env:POSTGRES_PASSWORD
        OSS_ACCESS_KEY_ID = $env:OSS_ACCESS_KEY_ID
        OSS_ACCESS_KEY_SECRET = $env:OSS_ACCESS_KEY_SECRET
        OSS_SESSION_TOKEN = [string] $env:OSS_SESSION_TOKEN
        OSS_REGION = 'cn-beijing'
    }
    $dockerVolume = "$workDir`:/work"
    Invoke-CheckedCommand 'docker' @(
        'run', '--rm', '--network', $ComposeNetwork,
        '-e', 'PGPASSWORD', '-v', $dockerVolume,
        'postgres:17-alpine', 'pg_dump', '--host', $DatabaseHost,
        '--username', $DatabaseUser, '--dbname', $Database,
        '--format=custom', '--no-owner', '--no-privileges', '--file=/work/database.dump'
    ) $processEnvironment 'postgres_dump_failed' | Out-Null

    $dumpInfo = Get-Item -LiteralPath $dumpPath
    if ($dumpInfo.Length -le 0 -or $dumpInfo.Length -gt $MaxDumpBytes) { throw 'invalid_backup_dump_size' }
    $dumpSha = Get-FileSha256 $dumpPath

    $gitResult = Invoke-CheckedCommand 'git' @('-C', $projectRoot, 'rev-parse', 'HEAD') @{} 'git_metadata_failed'
    $gitSha = $gitResult.Stdout.Trim().ToLowerInvariant()
    $dirtyResult = Invoke-CheckedCommand 'git' @('-C', $projectRoot, 'status', '--porcelain') @{} 'git_metadata_failed'
    if (-not [string]::IsNullOrWhiteSpace($dirtyResult.Stdout)) { $gitSha += '-DIRTY' }

    function Get-FlywayVersion([string] $Schema) {
        $query = "select coalesce(max(version),'0') from $Schema.flyway_schema_history where success=true;"
        $result = Invoke-CheckedCommand 'docker' @(
            'run', '--rm', '--network', $ComposeNetwork, '-e', 'PGPASSWORD',
            'postgres:17-alpine', 'psql', '--host', $DatabaseHost,
            '--username', $DatabaseUser, '--dbname', $Database, '-Atc', $query
        ) $processEnvironment 'flyway_version_query_failed'
        $version = $result.Stdout.Trim()
        if ($version -notmatch '^\d+(\.\d+)*$') { throw 'flyway_version_query_failed' }
        return $version
    }

    $manifest = New-BackupManifest @{
        backupId = $backupId
        createdAt = $createdAt.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        database = $Database
        gitSha = $gitSha
        postgresMajor = 17
        dumpFormat = 'custom'
        dumpKey = $dumpKey
        dumpBytes = $dumpInfo.Length
        dumpSha256 = $dumpSha
        platformFlywayVersion = Get-FlywayVersion 'platform'
        gatewayFlywayVersion = Get-FlywayVersion 'gateway'
    }
    Write-JsonFileUtf8 $manifestPath $manifest

    Invoke-CheckedCommand 'ossutil' @('cp', $dumpPath, "oss://$Bucket/$dumpKey", '--endpoint', $Endpoint, '--ignore-existing', '--no-progress') $processEnvironment 'backup_dump_upload_failed' | Out-Null
    Invoke-CheckedCommand 'ossutil' @('cp', $manifestPath, "oss://$Bucket/$manifestKey", '--endpoint', $Endpoint, '--ignore-existing', '--no-progress') $processEnvironment 'backup_manifest_upload_failed' | Out-Null
    Invoke-CheckedCommand 'ossutil' @('cp', "oss://$Bucket/$manifestKey", $verifiedManifestPath, '--endpoint', $Endpoint, '--force') $processEnvironment 'backup_manifest_download_failed' | Out-Null
    $verifiedManifest = Read-BackupManifest $verifiedManifestPath
    Invoke-CheckedCommand 'ossutil' @('cp', "oss://$Bucket/$($verifiedManifest.dumpKey)", $verifiedDumpPath, '--endpoint', $Endpoint, '--force') $processEnvironment 'backup_dump_download_failed' | Out-Null
    $verifiedInfo = Get-Item -LiteralPath $verifiedDumpPath
    if ($verifiedInfo.Length -ne [long] $verifiedManifest.dumpBytes -or
            (Get-FileSha256 $verifiedDumpPath) -ne [string] $verifiedManifest.dumpSha256) {
        throw 'backup_verification_failed'
    }

    if (-not [string]::IsNullOrWhiteSpace($env:BACKUP_METRICS_FILE)) {
        Write-BackupTextfileMetrics $env:BACKUP_METRICS_FILE @{
            'backup_upload_total_success_postgresql' = 1
            'backup_last_success_timestamp_seconds_postgresql' = $createdAt.ToUnixTimeSeconds()
        }
    }
    $success = $true
    return [pscustomobject] @{
        backupId = $backupId
        manifestKey = $manifestKey
        dumpKey = $dumpKey
        dumpBytes = $verifiedInfo.Length
        dumpSha256 = [string] $verifiedManifest.dumpSha256
        platformFlywayVersion = [string] $verifiedManifest.platformFlywayVersion
        gatewayFlywayVersion = [string] $verifiedManifest.gatewayFlywayVersion
        verified = $true
        dryRun = $false
    }
} finally {
    foreach ($candidate in @($workDir, $downloadDir)) {
        $base = if ($candidate -eq $workDir) { $workBase } else { $downloadBase }
        if ($candidate.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
                [IO.Directory]::Exists($candidate)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
    if (-not $success -and -not [string]::IsNullOrWhiteSpace($env:BACKUP_METRICS_FILE)) {
        Write-BackupTextfileMetrics $env:BACKUP_METRICS_FILE @{ 'backup_upload_total_failed_postgresql' = 1 }
    }
}
