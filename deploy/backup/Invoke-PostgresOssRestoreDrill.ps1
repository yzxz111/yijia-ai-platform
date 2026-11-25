param(
    [string] $DrillId = ([guid]::NewGuid().ToString('N')),
    [string] $ManifestKey,
    [switch] $CreateFreshBackup,
    [switch] $UploadEvidence,
    [switch] $KeepDatabase,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Backup.Common.ps1')
$hasManifestKey = -not [string]::IsNullOrWhiteSpace($ManifestKey)
if ($DrillId -notmatch '^[a-z0-9]{8,64}$' -or
        ($hasManifestKey -eq [bool] $CreateFreshBackup)) {
    throw 'invalid_drill_parameters'
}

$startedAt = [DateTimeOffset]::UtcNow
$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$targetDatabase = "api_model_platform_restore_$DrillId"
Assert-RestoreDatabaseName $targetDatabase 'api_model_platform'
$evidenceDir = Join-Path $PSScriptRoot 'evidence'
[IO.Directory]::CreateDirectory($evidenceDir) | Out-Null
$evidencePath = Join-Path $evidenceDir "$DrillId.local.json"
$phases = [Collections.Generic.List[object]]::new()
$backup = $null
$restore = $null
$outcome = 'FAILED'
$errorCode = $null

function Add-DrillPhase([string] $Name, [string] $Outcome) {
    $phases.Add([pscustomobject] @{ name = $Name; outcome = $Outcome; elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3) })
}

try {
    Add-DrillPhase 'PREREQUISITES' 'SUCCEEDED'
    if ($CreateFreshBackup) {
        $backup = & (Join-Path $PSScriptRoot 'New-PostgresOssBackup.ps1') -RunId $DrillId -DryRun:$DryRun
        $ManifestKey = [string] $backup.manifestKey
    } else {
        Assert-BackupObjectKey $ManifestKey
    }
    Add-DrillPhase 'BACKUP_OR_SELECT' 'SUCCEEDED'
    $restore = & (Join-Path $PSScriptRoot 'Restore-PostgresOssBackup.ps1') `
        -ManifestKey $ManifestKey -TargetDatabase $targetDatabase `
        -KeepDatabase:$KeepDatabase -DryRun:$DryRun
    Add-DrillPhase 'DOWNLOAD' 'SUCCEEDED'
    Add-DrillPhase 'HASH_VERIFY' $(if ($restore.hashVerified) { 'SUCCEEDED' } else { 'FAILED' })
    Add-DrillPhase 'RESTORE' 'SUCCEEDED'
    Add-DrillPhase 'FLYWAY_VALIDATE' $(if ($restore.flywayValidated) { 'SUCCEEDED' } else { 'FAILED' })
    Add-DrillPhase 'INVARIANT_CHECK' $(if ($restore.invariantsPassed) { 'SUCCEEDED' } else { 'FAILED' })
    Add-DrillPhase 'DROP_DATABASE' $(if ($KeepDatabase) { 'SKIPPED' } else { 'SUCCEEDED' })
    $outcome = 'SUCCEEDED'
} catch {
    $errorCode = Protect-OperationalMessage $_.Exception.Message
    Add-DrillPhase 'FAILED_PHASE' 'FAILED'
} finally {
    $stopwatch.Stop()
    $finishedAt = [DateTimeOffset]::UtcNow
    $evidence = [pscustomobject] [ordered] @{
        schemaVersion = 1
        drillId = $DrillId
        startedAt = $startedAt.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        finishedAt = $finishedAt.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
        outcome = $outcome
        errorCode = $errorCode
        backupId = if ($null -ne $backup) { $backup.backupId } else { $null }
        manifestKey = $ManifestKey
        dumpKey = if ($null -ne $backup) { $backup.dumpKey } else { $null }
        dumpBytes = if ($null -ne $restore -and $null -ne $restore.PSObject.Properties['dumpBytes']) { $restore.dumpBytes } elseif ($null -ne $backup) { $backup.dumpBytes } else { $null }
        dumpSha256 = if ($null -ne $restore -and $null -ne $restore.PSObject.Properties['dumpSha256']) { $restore.dumpSha256 } elseif ($null -ne $backup) { $backup.dumpSha256 } else { $null }
        sourceDatabase = 'api_model_platform'
        targetDatabase = $targetDatabase
        backupAgeSecondsAtDrillStart = 0
        hashVerified = if ($null -ne $restore) { [bool] $restore.hashVerified } else { $false }
        flywayValidated = if ($null -ne $restore) { [bool] $restore.flywayValidated } else { $false }
        invariantsPassed = if ($null -ne $restore) { [bool] $restore.invariantsPassed } else { $false }
        databaseDropped = if ($null -ne $restore) { [bool] $restore.databaseDropped } else { $false }
        phases = $phases
        gitSha = if ($null -ne $backup -and $null -ne $backup.PSObject.Properties['gitSha']) { $backup.gitSha } else { $null }
    }
    Write-JsonFileUtf8 $evidencePath $evidence
}

if ($outcome -eq 'SUCCEEDED' -and $UploadEvidence -and -not $DryRun) {
    $evidenceKey = "drills/$DrillId/evidence.json"
    Assert-BackupObjectKey $evidenceKey
    $environment = @{
        OSS_ACCESS_KEY_ID = $env:OSS_ACCESS_KEY_ID
        OSS_ACCESS_KEY_SECRET = $env:OSS_ACCESS_KEY_SECRET
        OSS_SESSION_TOKEN = [string] $env:OSS_SESSION_TOKEN
        OSS_REGION = 'cn-beijing'
    }
    Add-DrillPhase 'EVIDENCE_UPLOAD' 'SUCCEEDED'
    Write-JsonFileUtf8 $evidencePath $evidence
    try {
        Invoke-CheckedCommand 'ossutil' @(
            'cp', $evidencePath, "oss://$env:OSS_BACKUP_BUCKET/$evidenceKey",
            '--endpoint', $env:OSS_BACKUP_ENDPOINT,
            '--ignore-existing', '--no-progress'
        ) $environment 'drill_evidence_upload_failed' | Out-Null
    } catch {
        $phases[$phases.Count - 1].outcome = 'FAILED'
        $evidence.outcome = 'FAILED'
        $evidence.errorCode = 'drill_evidence_upload_failed'
        Write-JsonFileUtf8 $evidencePath $evidence
        $outcome = 'FAILED'
        $errorCode = 'drill_evidence_upload_failed'
    }
} else {
    Add-DrillPhase 'EVIDENCE_UPLOAD' 'SKIPPED'
    Write-JsonFileUtf8 $evidencePath $evidence
}

if ($outcome -ne 'SUCCEEDED') { throw $errorCode }
return [pscustomobject] @{
    drillId = $DrillId
    outcome = $outcome
    manifestKey = $ManifestKey
    targetDatabase = $targetDatabase
    elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
    evidencePath = $evidencePath
    dryRun = [bool] $DryRun
}
