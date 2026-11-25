param(
    [Parameter(Mandatory = $true)]
    [string]$AdminEmail,
    [Parameter(Mandatory = $true)]
    [string]$AdminPassword,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^1[3-9][0-9]{9}$')]
    [string]$TargetPhone,
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [ValidateRange(1, 3600)]
    [int]$PollTimeoutSeconds = 180,
    [ValidateRange(1, 300)]
    [int]$RequestTimeoutSeconds = 30,
    [ValidatePattern('^[A-Za-z0-9_-]{1,48}$')]
    [string]$RunId,
    [ValidatePattern('^[A-Za-z0-9_]+$')]
    [string[]]$TemplateKey,
    [ValidatePattern('^[0-9a-f]{32}$')]
    [string]$DeviceId
)

$ErrorActionPreference = 'Stop'

$parsedBaseUrl = $null
if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$parsedBaseUrl) `
        -or $parsedBaseUrl.Scheme -notin @('http', 'https') `
        -or -not [string]::IsNullOrEmpty($parsedBaseUrl.UserInfo) `
        -or -not [string]::IsNullOrEmpty($parsedBaseUrl.Query) `
        -or -not [string]::IsNullOrEmpty($parsedBaseUrl.Fragment)) {
    throw 'BaseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment.'
}
$BaseUrl = $parsedBaseUrl.AbsoluteUri.TrimEnd('/')

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = [Guid]::NewGuid().ToString('N')
}
if ([string]::IsNullOrWhiteSpace($DeviceId)) {
    $deviceSeed = "sms-template-runner:$($AdminEmail.Trim().ToLowerInvariant()):$([Environment]::MachineName)"
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $DeviceId = (($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($deviceSeed)) `
                | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 32)
    } finally {
        $sha256.Dispose()
    }
}

$expected = [ordered]@{
    ACCOUNT_ANOMALY_FROZEN = @{ type = 'NOTIFICATION'; variables = @{} }
    ACCOUNT_BANNED = @{ type = 'NOTIFICATION'; variables = @{} }
    ACCOUNT_UNFROZEN = @{ type = 'NOTIFICATION'; variables = @{} }
    API_KEY_RESET_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    API_RATE_LIMITED = @{ type = 'NOTIFICATION'; variables = @{} }
    API_USAGE_DEDUCTED = @{ type = 'NOTIFICATION'; variables = @{ costMoney = '0.01'; balance = '99.99' } }
    BALANCE_LOW = @{ type = 'NOTIFICATION'; variables = @{ warnMoney = '10.00' } }
    BALANCE_RECHARGE_SUCCEEDED = @{ type = 'NOTIFICATION'; variables = @{ payMoney = '19.90'; balance = '99.99' } }
    LOCATION_LOGIN_WARNING = @{ type = 'NOTIFICATION'; variables = @{ loginAddr = $null } }
    LOGIN_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    ORDER_PAYMENT_FAILED = @{ type = 'NOTIFICATION'; variables = @{ payMoney = '19.90' } }
    ORDER_TIMED_OUT = @{ type = 'NOTIFICATION'; variables = @{ orderNo = 'ORD-REAL-20260809' } }
    PACKAGE_EXPIRED = @{ type = 'NOTIFICATION'; variables = @{} }
    PACKAGE_EXPIRES_IN_3_DAYS = @{ type = 'NOTIFICATION'; variables = @{ expireDate = '2026-09-08' } }
    PACKAGE_PAYMENT_SUCCEEDED = @{ type = 'NOTIFICATION'; variables = @{ payMoney = '19.90'; expireDate = '2026-09-08' } }
    PASSWORD_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    PAYMENT_CONFIRM_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    PHONE_CHANGE_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    PLATFORM_MAINTENANCE = @{ type = 'NOTIFICATION'; variables = @{ maintainTime = '2026-08-10 02:00' } }
    REFUND_SUCCEEDED = @{ type = 'NOTIFICATION'; variables = @{ refundMoney = '8.88' } }
    REGISTER_CODE = @{ type = 'VERIFICATION'; variables = @{ code = $null } }
    SUPPORT_TICKET_COMPLETED = @{ type = 'NOTIFICATION'; variables = @{ ticketNo = 'TK-REAL-20260809' } }
}

$selectedKeys = if ($TemplateKey.Count -gt 0) {
    @($TemplateKey | ForEach-Object { $_.ToUpperInvariant() } | Select-Object -Unique)
} else {
    @($expected.Keys)
}
foreach ($key in $selectedKeys) {
    if (-not $expected.Contains($key)) {
        throw "Template is not approved by this runner: $key"
    }
}
$selectedEntries = @($expected.GetEnumerator() | Where-Object { $_.Key -in $selectedKeys })

$loginHeaders = @{ 'X-Device-Id' = $DeviceId }
$loginBody = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json
$session = Invoke-RestMethod -Method Post -ContentType 'application/json; charset=utf-8' `
    -Uri "$BaseUrl/console/platform/auth/login" `
    -Headers $loginHeaders -Body ([Text.Encoding]::UTF8.GetBytes($loginBody)) `
    -TimeoutSec $RequestTimeoutSeconds
if ([string]::IsNullOrWhiteSpace($session.accessToken)) {
    throw 'Administrator login did not return an access token.'
}
$headers = @{
    Authorization = "Bearer $($session.accessToken)"
    'X-Device-Id' = $DeviceId
}

$catalogResponse = Invoke-RestMethod -Method Get `
    -Uri "$BaseUrl/console/platform/admin/sms/templates" -Headers $headers `
    -TimeoutSec $RequestTimeoutSeconds
$catalog = @($catalogResponse | ForEach-Object { $_ })
$active = @($catalog | Where-Object { $_.integrationStatus -eq 'ACTIVE' })
$reserved = @($catalog | Where-Object { $_.integrationStatus -eq 'RESERVED' })
if ($active.Count -ne 22) {
    throw "Expected 22 active SMS templates, found $($active.Count)."
}
if (@($active | Where-Object { $_.templateType -eq 'NOTIFICATION' }).Count -ne 16) {
    throw 'Expected 16 active notification templates.'
}
if (@($active | Where-Object { $_.templateType -eq 'VERIFICATION' }).Count -ne 6) {
    throw 'Expected 6 active verification templates.'
}
if ($reserved.Count -ne 2) {
    throw "Expected 2 reserved SMS templates, found $($reserved.Count)."
}
foreach ($template in $active) {
    if (-not $expected.Contains($template.templateKey)) {
        throw "Active template is not approved by this runner: $($template.templateKey)"
    }
    if ($expected[$template.templateKey].type -ne $template.templateType) {
        throw "Template type mismatch: $($template.templateKey)"
    }
}

$loginAddress = ([string][char]0x5E7F) + [char]0x4E1C + [char]0x4E1C + [char]0x839E
$queued = [System.Collections.Generic.List[object]]::new()
Write-Host "SMS template test run ID: $RunId"
Write-Host "Selected templates: $($selectedKeys -join ', ')"

foreach ($entry in $selectedEntries) {
    $variables = @{}
    foreach ($variable in $entry.Value.variables.GetEnumerator()) {
        if ($variable.Key -eq 'code') {
            $variables.code = (Get-Random -Minimum 100000 -Maximum 1000000).ToString()
        } elseif ($variable.Key -eq 'loginAddr') {
            $variables.loginAddr = $loginAddress
        } else {
            $variables[$variable.Key] = $variable.Value
        }
    }
    $businessNo = "sms-template-test-$RunId-$($entry.Key.ToLowerInvariant())"
    $body = @{
        phone = $TargetPhone
        variables = $variables
        businessNo = $businessNo
    } | ConvertTo-Json -Depth 5
    $delivery = Invoke-RestMethod -Method Post `
        -ContentType 'application/json; charset=utf-8' `
        -Uri "$BaseUrl/console/platform/admin/sms/templates/$($entry.Key)/test-deliveries" `
        -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
        -TimeoutSec $RequestTimeoutSeconds
    $queued.Add([pscustomobject]@{
        templateKey = $entry.Key
        templateType = $entry.Value.type
        deliveryId = $delivery.deliveryId
        maskedPhone = $delivery.maskedPhone
    })
}

$latest = @{}
$deadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
do {
    $allTerminal = $true
    foreach ($item in $queued) {
        $page = Invoke-RestMethod -Method Get `
            -Uri "$BaseUrl/console/platform/admin/sms/deliveries?query=$($item.deliveryId)&size=1" `
            -Headers $headers -TimeoutSec $RequestTimeoutSeconds
        if ($page.total -ne 1) {
            throw "Delivery lookup failed for $($item.templateKey)."
        }
        $delivery = $page.items[0]
        $latest[$item.deliveryId] = $delivery
        if ($delivery.status -notin @('SENT', 'DEAD')) {
            $allTerminal = $false
        }
    }
    if (-not $allTerminal) {
        Start-Sleep -Seconds 2
    }
} while (-not $allTerminal -and (Get-Date) -lt $deadline)

$results = [System.Collections.Generic.List[object]]::new()
foreach ($item in $queued) {
    $delivery = $latest[$item.deliveryId]
    $results.Add([pscustomobject]@{
        templateKey = $item.templateKey
        templateType = $item.templateType
        deliveryId = $item.deliveryId
        maskedPhone = $item.maskedPhone
        status = $delivery.status
        attemptCount = $delivery.attemptCount
        sanitizedError = $delivery.sanitizedError
    })
}

$results | Sort-Object templateKey | Format-Table -AutoSize
$summary = $results | Group-Object status | Sort-Object Name | ForEach-Object {
    [pscustomobject]@{ status = $_.Name; count = $_.Count }
}
$summary | Format-Table -AutoSize

if (@($results | Where-Object { $_.status -ne 'SENT' }).Count -gt 0) {
    exit 2
}
