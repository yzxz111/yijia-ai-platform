param(
    [string]$Destination = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$commit = '907400bb3023ce48aaedf34453a81c0073b7cef0'
$files = @(
    @{
        Name = 'ip2region_v4.xdb'
        Sha256 = '750D08A83969B5203C055E9F2B0073EC511610ADF54915BB1F35AE932AEA1A11'
    },
    @{
        Name = 'ip2region_v6.xdb'
        Sha256 = 'D1B49CEF8C59BE7C6878385783B2D5DEB0F28E41E519143CECA4DAAEB6786B92'
    }
)

New-Item -ItemType Directory -Force -Path $Destination | Out-Null
foreach ($file in $files) {
    $target = Join-Path $Destination $file.Name
    if (Test-Path -LiteralPath $target) {
        $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
        if ($existingHash -eq $file.Sha256) {
            Write-Output "$($file.Name) already verified"
            continue
        }
    }

    $temporary = "$target.download-$PID"
    $url = "https://raw.githubusercontent.com/lionsoul2014/ip2region/$commit/data/$($file.Name)"
    try {
        Invoke-WebRequest -Uri $url -OutFile $temporary
        $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporary).Hash
        if ($actualHash -ne $file.Sha256) {
            throw "Checksum mismatch for $($file.Name)"
        }
        Move-Item -Force -LiteralPath $temporary -Destination $target
        Write-Output "$($file.Name) downloaded and verified"
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -Force -LiteralPath $temporary
        }
    }
}
