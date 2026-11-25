Set-StrictMode -Version Latest

function Assert-BackupBucket([string] $Bucket) {
    if ($Bucket -cne 'yzh-api-backup-test1') { throw 'invalid_backup_bucket' }
}

function Assert-BackupEndpoint([string] $Endpoint) {
    $uri = $null
    if (-not [Uri]::TryCreate($Endpoint, [UriKind]::Absolute, [ref] $uri) -or
            $uri.Scheme -cne 'https' -or -not $uri.Host.EndsWith('.aliyuncs.com')) {
        throw 'invalid_backup_endpoint'
    }
}

function Assert-BackupObjectKey([string] $Key) {
    if ([string]::IsNullOrWhiteSpace($Key) -or $Key.StartsWith('/') -or
            $Key.Contains('\') -or $Key.Contains('..') -or
            $Key -match '[\x00-\x1f\x7f]' -or $Key.Contains('//') -or
            -not ($Key.StartsWith('database/postgresql/') -or $Key.StartsWith('drills/'))) {
        throw 'invalid_backup_object_key'
    }
    foreach ($segment in $Key.Split('/')) {
        if ([string]::IsNullOrWhiteSpace($segment)) { throw 'invalid_backup_object_key' }
    }
}

function Assert-RestoreDatabaseName([string] $Name, [string] $SourceDatabase) {
    if ($Name -eq $SourceDatabase -or $Name -notmatch '^api_model_platform_restore_[a-z0-9_]+$') {
        throw 'invalid_restore_database'
    }
}

function Get-FileSha256([string] $LiteralPath) {
    if (-not [IO.File]::Exists($LiteralPath)) { throw 'backup_file_missing' }
    $stream = [IO.File]::OpenRead($LiteralPath)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $bytes = $sha.ComputeHash($stream)
            if ($null -ne [Convert].GetMethod('ToHexString', [type[]] @([byte[]]))) {
                return ([Convert]::ToHexString($bytes)).ToLowerInvariant()
            }
            return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
        }
        finally { $sha.Dispose() }
    } finally {
        $stream.Dispose()
    }
}

function Test-BackupManifest($Manifest) {
    $required = @(
        'schemaVersion', 'backupId', 'createdAt', 'database', 'gitSha',
        'postgresMajor', 'dumpFormat', 'dumpKey', 'dumpBytes', 'dumpSha256',
        'platformFlywayVersion', 'gatewayFlywayVersion'
    )
    foreach ($name in $required) {
        if ($null -eq $Manifest.PSObject.Properties[$name] -or
                [string]::IsNullOrWhiteSpace([string] $Manifest.$name)) {
            throw 'invalid_backup_manifest'
        }
    }
    if ([int] $Manifest.schemaVersion -ne 1 -or
            -not [guid]::TryParse([string] $Manifest.backupId, [ref] ([guid]::Empty)) -or
            [string] $Manifest.database -ne 'api_model_platform' -or
            [string] $Manifest.gitSha -notmatch '^[0-9a-f]{40}(-DIRTY)?$' -or
            [int] $Manifest.postgresMajor -ne 17 -or
            [string] $Manifest.dumpFormat -ne 'custom' -or
            [long] $Manifest.dumpBytes -le 0 -or
            [string] $Manifest.dumpSha256 -notmatch '^[0-9a-f]{64}$' -or
            [string] $Manifest.platformFlywayVersion -notmatch '^\d+(\.\d+)*$' -or
            [string] $Manifest.gatewayFlywayVersion -notmatch '^\d+(\.\d+)*$') {
        throw 'invalid_backup_manifest'
    }
    $created = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string] $Manifest.createdAt, [ref] $created) -or
            $created.Offset -ne [TimeSpan]::Zero) {
        throw 'invalid_backup_manifest'
    }
    Assert-BackupObjectKey ([string] $Manifest.dumpKey)
    if ($null -ne $Manifest.PSObject.Properties['endpoint']) {
        Assert-BackupEndpoint ([string] $Manifest.endpoint)
    }
}

function New-BackupManifest([hashtable] $Values) {
    $manifest = [pscustomobject] [ordered] @{
        schemaVersion = 1
        backupId = [string] $Values.backupId
        createdAt = [string] $Values.createdAt
        database = [string] $Values.database
        gitSha = [string] $Values.gitSha
        postgresMajor = [int] $Values.postgresMajor
        dumpFormat = [string] $Values.dumpFormat
        dumpKey = [string] $Values.dumpKey
        dumpBytes = [long] $Values.dumpBytes
        dumpSha256 = [string] $Values.dumpSha256
        platformFlywayVersion = [string] $Values.platformFlywayVersion
        gatewayFlywayVersion = [string] $Values.gatewayFlywayVersion
    }
    Test-BackupManifest $manifest
    return $manifest
}

function Write-JsonFileUtf8([string] $LiteralPath, $Value) {
    $parent = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LiteralPath))
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $json = $Value | ConvertTo-Json -Depth 12
    [IO.File]::WriteAllText($LiteralPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Read-BackupManifest([string] $LiteralPath) {
    if (-not [IO.File]::Exists($LiteralPath)) { throw 'backup_manifest_missing' }
    try { $manifest = [IO.File]::ReadAllText($LiteralPath, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'invalid_backup_manifest' }
    Test-BackupManifest $manifest
    return $manifest
}

function Protect-OperationalMessage([string] $Message) {
    if ([string]::IsNullOrEmpty($Message)) { return '' }
    $protected = $Message
    $protected = [regex]::Replace($protected, '(?i)(Authorization\s*[:=]\s*Bearer\s+)\S+', '$1[REDACTED]')
    $protected = [regex]::Replace($protected, '(?i)(access[_-]?key[_-]?(id|secret)|token|password|signature)\s*[:=]\s*[^\s,;]+', '$1=[REDACTED]')
    if ($protected.Length -gt 4096) { return $protected.Substring(0, 4096) }
    return $protected
}

function ConvertTo-WindowsCommandLineArgument([string] $Argument) {
    if ($Argument.Length -eq 0) { return '""' }
    if ($Argument -notmatch '[\s"]') { return $Argument }
    $builder = [Text.StringBuilder]::new()
    [void] $builder.Append('"')
    $backslashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
        } elseif ($character -eq '"') {
            [void] $builder.Append('\' * (2 * $backslashes + 1))
            [void] $builder.Append('"')
            $backslashes = 0
        } else {
            if ($backslashes -gt 0) { [void] $builder.Append('\' * $backslashes) }
            [void] $builder.Append($character)
            $backslashes = 0
        }
    }
    if ($backslashes -gt 0) { [void] $builder.Append('\' * (2 * $backslashes)) }
    [void] $builder.Append('"')
    return $builder.ToString()
}

function Invoke-CheckedCommand(
        [string] $FilePath,
        [string[]] $ArgumentList,
        [hashtable] $Environment,
        [string] $FailureCode) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    if ($null -ne $start.PSObject.Properties['ArgumentList']) {
        foreach ($argument in $ArgumentList) { $start.ArgumentList.Add([string] $argument) }
    } else {
        $start.Arguments = (($ArgumentList | ForEach-Object {
            ConvertTo-WindowsCommandLineArgument ([string] $_)
        }) -join ' ')
    }
    foreach ($name in $Environment.Keys) {
        if ($null -ne $start.PSObject.Properties['Environment']) {
            $start.Environment[[string] $name] = [string] $Environment[$name]
        } else {
            $start.EnvironmentVariables[[string] $name] = [string] $Environment[$name]
        }
    }

    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $safeStderr = ''
    try {
        if (-not $process.Start()) { throw $FailureCode }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = Protect-OperationalMessage $stdoutTask.GetAwaiter().GetResult()
        $stderr = Protect-OperationalMessage $stderrTask.GetAwaiter().GetResult()
        $safeStderr = $stderr
        if ($process.ExitCode -ne 0) { throw $FailureCode }
        return [pscustomobject] @{ ExitCode = 0; Stdout = $stdout; Stderr = $stderr }
    } catch {
        if ($env:BACKUP_DIAGNOSTICS -eq '1' -and -not [string]::IsNullOrWhiteSpace($safeStderr)) {
            [Console]::Error.WriteLine("$FailureCode detail=$safeStderr")
        }
        if ($_.Exception.Message -eq $FailureCode) { throw $FailureCode }
        throw $FailureCode
    } finally {
        $process.Dispose()
    }
}

function Write-BackupTextfileMetrics([string] $LiteralPath, [hashtable] $Values) {
    $parent = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LiteralPath))
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($name in ($Values.Keys | Sort-Object)) {
        if ([string] $name -notmatch '^[a-zA-Z_:][a-zA-Z0-9_:]*$') { throw 'invalid_metric_name' }
        $value = [double] $Values[$name]
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value)) { throw 'invalid_metric_value' }
        $lines.Add("$name $($value.ToString([Globalization.CultureInfo]::InvariantCulture))")
    }
    $temporary = "$LiteralPath.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllLines($temporary, $lines, [Text.UTF8Encoding]::new($false))
        if ([IO.File]::Exists($LiteralPath)) {
            [IO.File]::Replace($temporary, $LiteralPath, $null)
        } else {
            [IO.File]::Move($temporary, $LiteralPath)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}
