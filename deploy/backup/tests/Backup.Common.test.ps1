Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "assert_true_failed:$Message" }
}

function Assert-Equal($Expected, $Actual, [string] $Message) {
    if ($Expected -ne $Actual) { throw "assert_equal_failed:$Message expected=$Expected actual=$Actual" }
}

function Assert-Throws([scriptblock] $Action, [string] $ExpectedMessage) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -eq $ExpectedMessage) { return }
        throw "assert_throws_wrong_message: expected=$ExpectedMessage actual=$($_.Exception.Message)"
    }
    throw "assert_throws_missing:$ExpectedMessage"
}

. (Join-Path $PSScriptRoot '..\Backup.Common.ps1')

Assert-BackupBucket 'yzh-api-backup-test1'
Assert-Throws { Assert-BackupBucket 'yzh-api-files-test' } 'invalid_backup_bucket'
Assert-BackupObjectKey 'database/postgresql/2026/08/10/20260810T100000Z/database.dump'
Assert-BackupObjectKey 'drills/abc123/evidence.json'
Assert-Throws { Assert-BackupObjectKey '../database.dump' } 'invalid_backup_object_key'
Assert-Throws { Assert-BackupObjectKey 'database\postgresql\database.dump' } 'invalid_backup_object_key'
Assert-RestoreDatabaseName 'api_model_platform_restore_abc123' 'api_model_platform'
Assert-Throws { Assert-RestoreDatabaseName 'api_model_platform' 'api_model_platform' } 'invalid_restore_database'

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("backup-common-" + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($tempRoot) | Out-Null
try {
    $payload = Join-Path $tempRoot 'database.dump'
    [IO.File]::WriteAllText($payload, 'backup-test', [Text.UTF8Encoding]::new($false))
    Assert-Equal 64 (Get-FileSha256 $payload).Length 'sha256 length'

    $manifest = New-BackupManifest @{
        backupId = [guid]::NewGuid().ToString()
        createdAt = '2026-08-10T10:00:00Z'
        database = 'api_model_platform'
        gitSha = ('a' * 40) + '-DIRTY'
        postgresMajor = 17
        dumpFormat = 'custom'
        dumpKey = 'database/postgresql/2026/08/10/20260810T100000Z/database.dump'
        dumpBytes = 11
        dumpSha256 = 'b' * 64
        platformFlywayVersion = '25'
        gatewayFlywayVersion = '24'
    }
    $manifestPath = Join-Path $tempRoot 'manifest.json'
    Write-JsonFileUtf8 -LiteralPath $manifestPath -Value $manifest
    $roundTrip = Read-BackupManifest $manifestPath
    Assert-Equal 1 $roundTrip.schemaVersion 'manifest schema'
    Assert-Equal 'custom' $roundTrip.dumpFormat 'dump format'

    $protected = Protect-OperationalMessage 'token=secret-value Authorization: Bearer abc'
    Assert-True (-not $protected.Contains('secret-value')) 'redact token'
    Assert-True (-not $protected.Contains('Bearer abc')) 'redact authorization'

    $metricsPath = Join-Path $tempRoot 'backup.prom'
    Write-BackupTextfileMetrics $metricsPath @{ backup_upload_total_success_postgresql = 1 }
    Assert-True ([IO.File]::ReadAllText($metricsPath).Contains('backup_upload_total_success_postgresql 1')) 'metric write'

    if ($PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows) {
        $command = 'cmd.exe'
        $arguments = @('/c', 'exit', '7')
    } else {
        $command = '/bin/sh'
        $arguments = @('-c', 'exit 7')
    }
    Assert-Throws {
        Invoke-CheckedCommand -FilePath $command -ArgumentList $arguments `
            -Environment @{} -FailureCode 'external_command_failed' | Out-Null
    } 'external_command_failed'
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}

Write-Output 'Backup.Common tests passed'
