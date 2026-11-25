Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$env:OSS_BACKUP_BUCKET = 'yzh-api-backup-test1'
$env:OSS_BACKUP_ENDPOINT = 'https://oss-cn-beijing.aliyuncs.com'

$scriptPath = Join-Path $PSScriptRoot '..\Invoke-PostgresOssRestoreDrill.ps1'
$result = & $scriptPath -DrillId 'drilldryrun001' -CreateFreshBackup -UploadEvidence -DryRun
if ($result.outcome -ne 'SUCCEEDED' -or -not $result.dryRun) { throw 'drill_dry_run_failed' }
if (-not [IO.File]::Exists($result.evidencePath)) { throw 'drill_evidence_missing' }
$evidence = [IO.File]::ReadAllText($result.evidencePath) | ConvertFrom-Json
if ($evidence.phases.Count -lt 5 -or -not $evidence.manifestKey) { throw 'drill_evidence_incomplete' }
if ('EVIDENCE_UPLOAD' -notin @($evidence.phases.name)) { throw 'drill_evidence_upload_phase_missing' }
$serialized = $evidence | ConvertTo-Json -Depth 10
if ($serialized -match '(?i)access.key|password|authorization|secret') { throw 'drill_evidence_contains_secret' }
[IO.File]::Delete($result.evidencePath)
Write-Output 'Invoke-PostgresOssRestoreDrill tests passed'
