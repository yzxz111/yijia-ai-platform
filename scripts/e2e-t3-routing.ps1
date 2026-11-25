# T-3 expiry-notice routing on the real stack: subscriber vs non-subscriber.
#
# Closes the gap in docs/testing/subscription-renewal-acceptance.md 2.2.1 item 3. The
# entry point is POST /internal/platform/sms/signals/package-expiry, which is guarded by
# HMAC v2, so this script reproduces the signer:
#
#   canonical = timestamp \n requestId \n METHOD \n path \n sha256hex(body)
#   signature = hmacSha256hex(secret, canonical)
#
# with headers X-Internal-Timestamp / X-Internal-Request-Id /
# X-Internal-Content-Sha256 / X-Internal-Signature. The secret is shared by both services
# through SECURITY_INTERNAL_HMAC_SECRET.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

function Get-HmacSecret {
    if ($env:SECURITY_INTERNAL_HMAC_SECRET) { return $env:SECURITY_INTERNAL_HMAC_SECRET }
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy/env/.env'
    foreach ($line in [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8)) {
        if ($line -match '^\s*SECURITY_INTERNAL_HMAC_SECRET\s*=\s*(.+?)\s*$') { return $Matches[1] }
    }
    throw 'SECURITY_INTERNAL_HMAC_SECRET not available'
}

function Get-Sha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-HmacSha256Hex {
    param([string]$Secret, [string]$Message)
    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    try {
        $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
        return ([System.BitConverter]::ToString(
            $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Message))) -replace '-', '').ToLowerInvariant()
    } finally { $hmac.Dispose() }
}

<#
.SYNOPSIS
POSTs a signed internal request and returns the HTTP status plus raw body.
#>
function Invoke-SignedInternal {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Json
    )
    # The signature must cover the EXACT request bytes, so the body is written to a file and
    # never goes through shell quoting. The call is issued from INSIDE the compose network:
    # nginx deliberately exposes no /internal/** location (deploy/nginx/nginx.conf), so over
    # the public port these paths fall through to the console-frontend catch-all and a POST
    # gets a 405 from the static server.
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $requestId = [guid]::NewGuid().ToString()
    $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $localJson = Join-Path $env:TEMP "t3-$stamp.json"
    $localScript = Join-Path $env:TEMP "t3-$stamp.sh"
    [System.IO.File]::WriteAllText($localJson, $Json, (New-Object System.Text.UTF8Encoding($false)))

    $sh = @"
set -e
JSON=`$(cat /tmp/t3body.json)
HASH=`$(printf '%s' "`$JSON" | sha256sum | cut -d' ' -f1)
CANON=`$(printf '%s\n%s\nPOST\n$Path\n%s' '$timestamp' '$requestId' "`$HASH")
SIG=`$(printf '%s' "`$CANON" | openssl dgst -sha256 -hmac "`$SECURITY_INTERNAL_HMAC_SECRET" -r | cut -d' ' -f1)
curl -s -o /tmp/t3resp.txt -w '%{http_code}' -X POST 'http://localhost:8080$Path' \
  -H 'Content-Type: application/json' \
  -H 'X-Internal-Timestamp: $timestamp' \
  -H 'X-Internal-Request-Id: $requestId' \
  -H "X-Internal-Content-Sha256: `$HASH" \
  -H "X-Internal-Signature: `$SIG" \
  --data-binary "@/tmp/t3body.json"
echo ""
echo "---RESPONSE---"
cat /tmp/t3resp.txt
"@
    [System.IO.File]::WriteAllText($localScript, $sh, (New-Object System.Text.UTF8Encoding($false)))

    $container = 'yuzhihuihe-api-platform-platform-service-1'
    & docker cp $localJson "${container}:/tmp/t3body.json" 2>&1 | Out-Null
    & docker cp $localScript "${container}:/tmp/t3call.sh" 2>&1 | Out-Null
    $raw = & docker exec $container sh /tmp/t3call.sh 2>&1
    Remove-Item $localJson, $localScript -Force -ErrorAction SilentlyContinue

    $text = ($raw | Out-String).Trim()
    $code = 0
    if ($text -match '^(?<st>\d{3})\s*---RESPONSE---\s*(?<body>[\s\S]*)$') {
        $code = [int]$Matches['st']
        $text = $Matches['body'].Trim()
    }
    return [pscustomobject]@{ Status = $code; Text = $text }
}

# Local equivalents of the helpers that live inside e2e-full.ps1 (that file is a script,
# not a library, so its helpers cannot be dot-sourced from here).
function New-T3Account {
    param([Parameter(Mandatory)][string]$Name)
    $email = "sub-e2e-$Name-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
    New-SubscriptionTestUser -Email $email | Out-Null
    Set-WalletBalance -Email $email -Cents 100000 | Out-Null
    $token = Connect-DemoUser -Email $email
    return [pscustomobject]@{ Email = $email; Token = $token }
}

function Open-T3Subscription {
    param([Parameter(Mandatory)]$Ctx)
    $r = Invoke-Api -Method POST -Path '/console/platform/subscriptions' -Token $Ctx.Token `
        -IdempotencyKey ([guid]::NewGuid().ToString()) `
        -Body @{ packageVersionId = Get-PublishedVersionId -PlanCode 'STARTER' }
    if ($r.Status -ne 201) { throw "open failed: status=$($r.Status) body=$($r.Body | ConvertTo-Json -Compress)" }
    return (Get-SubscriptionId -Payload $r.Body)
}

$signalPath = '/internal/platform/sms/signals/package-expiry'

Write-Step 'T-3 setup: one subscriber and one non-subscriber, each with an instance expiring soon'
$subCtx = New-T3Account -Name 't3sub'
$subId = Open-T3Subscription -Ctx $subCtx
$subUserId = Get-UserId -Email $subCtx.Email
$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'subscriber instance to activate' -Condition {
    if ((Get-ActiveInstanceCount -Email $subCtx.Email) -ge 1) { return $true }
    return $null
}
$subInstance = Invoke-SqlScalar ("SELECT pi.id FROM gateway.package_instance pi " +
    "JOIN platform.app_user u ON u.id = pi.user_id WHERE lower(u.email) = lower('$($subCtx.Email)') " +
    "ORDER BY pi.created_at DESC LIMIT 1;")

# Routing is decided by "does this user have an active subscription", so the
# non-subscriber sample must be a real one-time purchaser: a user holding an ACTIVE
# package instance but NO active subscription. A fresh test account has no instance at
# all, so use a seeded demo account -- its instance came from the seed grant, not from a
# subscription, which is exactly that shape.
$plainRow = Invoke-Sql -TuplesOnly @"
SELECT u.email || '|' || u.id || '|' || u.tenant_id || '|' || pi.id
FROM platform.app_user u
JOIN gateway.package_instance pi ON pi.user_id = u.id AND pi.status = 'ACTIVE'
WHERE u.email LIKE '%@yuzhi.test'
  AND NOT EXISTS (
      SELECT 1 FROM platform.subscription s
      WHERE s.user_id = u.id AND s.status IN ('ACTIVE', 'PAST_DUE'))
ORDER BY u.email
LIMIT 1;
"@
$plainParts = ($plainRow | Where-Object { $_ -match '\|' } | Select-Object -First 1) -split '\|'
if (-not $plainParts -or $plainParts.Count -lt 4) {
    throw 'no seeded demo user with an ACTIVE instance and no active subscription; the non-subscriber branch cannot be exercised'
}
$plainEmail = $plainParts[0]
$plainUserId = $plainParts[1]
$plainTenant = $plainParts[2]
$plainInstance = $plainParts[3]
Write-Host "  subscriber instance = $subInstance (subId=$subId)"
Write-Host "  non-subscriber      = $plainEmail (instance $plainInstance)"

$expiresAt = (Get-Date).ToUniversalTime().AddHours(36).ToString("yyyy-MM-ddTHH:mm:ss'Z'")
$subTenant = Invoke-SqlScalar "SELECT tenant_id FROM platform.app_user WHERE id = '$subUserId';"

Write-Step 'T-3a: the signed signal must be accepted'
$subSignal = @{ tenantId = $subTenant; userId = $subUserId; packageId = $subInstance; expiresAt = $expiresAt } |
    ConvertTo-Json -Compress
$subResponse = Invoke-SignedInternal -Path $signalPath -Json $subSignal
Write-Host "  POST $signalPath (subscriber) -> HTTP $($subResponse.Status)"
if ($subResponse.Status -ne 200) {
    Write-Host "  body = $($subResponse.Text)"
    throw "signed internal signal was rejected (HTTP $($subResponse.Status)); check the HMAC canonical/headers against InternalHmacWebFilter"
}

Write-Step 'T-3b: a subscriber must get SUBSCRIPTION_RENEWAL_DUE, not PACKAGE_EXPIRES_IN_3_DAYS'
$due = Get-NotificationCount -Email $subCtx.Email -EventType 'SUBSCRIPTION_RENEWAL_DUE'
$old = Get-NotificationCount -Email $subCtx.Email -EventType 'PACKAGE_EXPIRES_IN_3_DAYS'
Write-Host "  SUBSCRIPTION_RENEWAL_DUE = $due ; PACKAGE_EXPIRES_IN_3_DAYS = $old"
Assert-True ($due -ge 1) 'subscriber received SUBSCRIPTION_RENEWAL_DUE'
Assert-Equal $old 0 'subscriber did NOT receive the non-subscriber PACKAGE_EXPIRES_IN_3_DAYS notice'
Invoke-Sql @"
\pset pager off
SELECT e.event_type, e.status, n.title, n.summary
FROM platform.notification_event e
LEFT JOIN platform.in_app_notification n ON n.event_id = e.id
JOIN platform.app_user u ON u.id = e.user_id
WHERE lower(u.email) = lower('$($subCtx.Email)') AND e.event_type = 'SUBSCRIPTION_RENEWAL_DUE'
ORDER BY e.created_at DESC LIMIT 1;
"@ | ForEach-Object { Write-Host "  $_" }

Write-Step 'T-3c: a non-subscriber must keep the old PACKAGE_EXPIRES_IN_3_DAYS notice'
$plainSignal = @{ tenantId = $plainTenant; userId = $plainUserId; packageId = $plainInstance; expiresAt = $expiresAt } |
    ConvertTo-Json -Compress
$plainResponse = Invoke-SignedInternal -Path $signalPath -Json $plainSignal
Write-Host "  POST $signalPath (non-subscriber) -> HTTP $($plainResponse.Status)"
Assert-Equal $plainResponse.Status 200 'signed signal accepted for the non-subscriber'
$plainOld = Get-NotificationCount -Email $plainEmail -EventType 'PACKAGE_EXPIRES_IN_3_DAYS'
$plainDue = Get-NotificationCount -Email $plainEmail -EventType 'SUBSCRIPTION_RENEWAL_DUE'
Write-Host "  PACKAGE_EXPIRES_IN_3_DAYS = $plainOld ; SUBSCRIPTION_RENEWAL_DUE = $plainDue"
Assert-True ($plainOld -ge 1) 'non-subscriber received PACKAGE_EXPIRES_IN_3_DAYS'
Assert-Equal $plainDue 0 'non-subscriber did NOT receive a subscription notice'

Write-Step 'T-3d: the in-app message must be delivered while the SMS is suppressed (template RESERVED)'
# The signal only creates the notification_event; a separate fanout job turns it into
# channel deliveries. Poll instead of assuming it has already run.
$inApp = Wait-For -TimeoutSeconds 90 -IntervalSeconds 5 -Description 'the in-app notice to be fanned out' -Condition {
    $n = Invoke-SqlScalar ("SELECT count(*) FROM platform.in_app_notification n " +
        "JOIN platform.notification_event e ON e.id = n.event_id " +
        "JOIN platform.app_user u ON u.id = n.user_id " +
        "WHERE lower(u.email) = lower('$($subCtx.Email)') AND e.event_type = 'SUBSCRIPTION_RENEWAL_DUE';")
    if ([int]$n -ge 1) { return $n }
    return $null
}
Write-Host "  in_app_notification rows = $inApp"
Assert-True ([int]$inApp -ge 1) 'the in-app notice was delivered'
Invoke-Sql @"
\pset pager off
SELECT d.channel, d.status, d.suppression_reason
FROM platform.notification_channel_delivery d
JOIN platform.notification_event e ON e.id = d.event_id
JOIN platform.app_user u ON u.id = e.user_id
WHERE lower(u.email) = lower('$($subCtx.Email)') AND e.event_type = 'SUBSCRIPTION_RENEWAL_DUE'
ORDER BY d.channel;
"@ | ForEach-Object { Write-Host "  $_" }

# The pre-charge notice is registered with mandatorySms = true, so it must produce an SMS
# delivery row even though the user's category preference was never enabled. Until the
# channel approves the template, that row is suppressed with TEMPLATE_INACTIVE -- which is
# the expected state and the reason this feature has a launch blocker (see 3).
$smsRows = Invoke-Sql -TuplesOnly @"
SELECT coalesce(string_agg(d.channel || '=' || d.status || coalesce('/' || d.suppression_reason, ''), ','), '<none>')
FROM platform.notification_channel_delivery d
JOIN platform.notification_event e ON e.id = d.event_id
JOIN platform.app_user u ON u.id = e.user_id
WHERE lower(u.email) = lower('$($subCtx.Email)') AND e.event_type = 'SUBSCRIPTION_RENEWAL_DUE';
"@
Write-Host "  delivery rows = $smsRows"
Assert-True ($smsRows -match 'SMS=') 'an SMS delivery row exists (mandatorySms bypasses the user preference)'

Write-Step 'T-3e: an unsigned request must be rejected by the HMAC filter'
# Probed from inside the network on purpose. Over the public port this path never reaches
# the application at all (nginx has no /internal/** location, so it falls through to the
# console-frontend catch-all and returns 405), which would prove nothing about the filter.
$unsignedScript = @'
set -e
JSON=$(cat /tmp/t3body.json)
curl -s -o /tmp/t3unsigned.txt -w '%{http_code}' -X POST 'http://localhost:8080/internal/platform/sms/signals/package-expiry' \
  -H 'Content-Type: application/json' \
  --data-binary "@/tmp/t3body.json"
echo ""
echo "---RESPONSE---"
cat /tmp/t3unsigned.txt
'@
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$localJson = Join-Path $env:TEMP "t3u-$stamp.json"
$localScript = Join-Path $env:TEMP "t3u-$stamp.sh"
$unsignedBody = @{ tenantId = $subTenant; userId = $subUserId; packageId = $subInstance; expiresAt = $expiresAt } |
    ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($localJson, $unsignedBody, (New-Object System.Text.UTF8Encoding($false)))
[System.IO.File]::WriteAllText($localScript, $unsignedScript, (New-Object System.Text.UTF8Encoding($false)))
$container = 'yuzhihuihe-api-platform-platform-service-1'
& docker cp $localJson "${container}:/tmp/t3body.json" 2>&1 | Out-Null
& docker cp $localScript "${container}:/tmp/t3unsigned.sh" 2>&1 | Out-Null
$unsignedRaw = ($(& docker exec $container sh /tmp/t3unsigned.sh 2>&1) | Out-String).Trim()
Remove-Item $localJson, $localScript -Force -ErrorAction SilentlyContinue
Write-Host "  unsigned (in-network) -> $($unsignedRaw -replace "`r?`n", ' ')"
$unsignedCode = 0
if ($unsignedRaw -match '(\d{3})') { $unsignedCode = [int]$Matches[1] }
Assert-Equal $unsignedCode 401 'an unsigned in-network request is rejected by the HMAC filter'

Write-Host ''
Write-Host 'T-3 ROUTING VERIFIED' -ForegroundColor Green
