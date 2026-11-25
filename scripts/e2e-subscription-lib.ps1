# Continuous monthly subscription - real-stack E2E acceptance driver library.
#
# Drives the feature through HTTP APIs only and reads evidence back through psql.
# It does not modify the code under test.
#
# Usage:  . .\scripts\e2e-subscription-lib.ps1
#
# NOTE: this file is intentionally pure ASCII. Windows PowerShell 5.1 reads
# script files using the ANSI code page (GBK on this host), so non-ASCII
# characters in a UTF-8 file can be mis-parsed. Keep it ASCII-only.

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:BaseUrl = if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL } else { 'http://127.0.0.1:18081' }
$script:PgContainer = 'yuzhihuihe-api-platform-postgres-1'
$script:PgPassword = 'local-postgres-password'
$script:PgDatabase = 'api_model_platform'

# Fixed device id: a random one accumulates terminal_session rows until
# max_login_devices (default 5) is exceeded and login is rejected.
$script:DeviceId = '550e8400e29b41d4a716446655440000'

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        $Body,
        [string]$IdempotencyKey,
        [switch]$Raw
    )
    $headers = @{ 'X-Device-Id' = $script:DeviceId }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    if ($IdempotencyKey) { $headers['Idempotency-Key'] = $IdempotencyKey }
    $uri = "$script:BaseUrl$Path"
    $params = @{
        Method      = $Method
        Uri         = $uri
        Headers     = $headers
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec  = 30
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 8 -Compress
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $resp = Invoke-WebRequest @params -UseBasicParsing
        $payload = if ($resp.Content) { $resp.Content | ConvertFrom-Json } else { $null }
        if ($Raw) { return $payload }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $payload; Error = $null }
    } catch {
        $status = 0
        $payload = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode.value__
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $text = $sr.ReadToEnd()
                if ($text) { $payload = $text | ConvertFrom-Json }
            } catch { }
        }
        if ($Raw) { throw }
        return [pscustomobject]@{ Status = $status; Body = $payload; Error = $_.Exception.Message }
    }
}

function Reset-SubscriptionState {
    param([Parameter(Mandatory)][string]$Email)
    # Delete children before parents: subscription_cycle -> subscription, then orders.
    #
    # Balance ledger rows are intentionally NOT touched: platform.balance_ledger is
    # append-only (a trigger rejects DELETE). That is correct accounting behaviour and
    # it means this E2E is single-use per account -- leftovers from a previous run also
    # count towards the pre-existing MAX_PURCHASES_PER_PACKAGE = 3 limit. Use a fresh
    # account (New-SubscriptionTestUser) for each full run.
    Invoke-Sql @"
DELETE FROM platform.subscription_cycle WHERE subscription_id IN (
    SELECT s.id FROM platform.subscription s
    JOIN platform.app_user u ON u.id = s.user_id
    WHERE lower(u.email) = lower('$Email'));
DELETE FROM platform.subscription WHERE user_id = (
    SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'));
DELETE FROM platform.customer_order WHERE user_id = (
    SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'));
"@ | Out-Null
}

<#
.SYNOPSIS
Creates a dedicated, clean subscription test account.
.DESCRIPTION
Gives the account an ACTIVE status, USER role, a zeroed wallet, a CLEARED safety
cache and NO package instance, which is exactly the "customer with no active
package" state the subscription first-period path requires.
#>
function New-SubscriptionTestUser {
    param([Parameter(Mandatory)][string]$Email)
    $existing = Get-UserId -Email $Email
    if ($existing) { return $existing }
    $id = Invoke-SqlScalar @"
INSERT INTO platform.app_user(id, tenant_id, email, password_hash, display_name, status)
VALUES (gen_random_uuid(), gen_random_uuid(), lower('$Email'),
        crypt('123456', gen_salt('bf', 12)), 'Subscription E2E', 'ACTIVE')
RETURNING id;
"@
    Invoke-Sql @"
INSERT INTO platform.user_role(tenant_id, user_id, role_id)
SELECT tenant_id, id, (SELECT id FROM platform.app_role WHERE code = 'USER')
FROM platform.app_user WHERE id = '$id';
INSERT INTO platform.account_balance(id, tenant_id, user_id, currency, available_amount)
SELECT gen_random_uuid(), tenant_id, id, 'CNY', 0
FROM platform.app_user WHERE id = '$id';
INSERT INTO gateway.safety_enforcement_cache(
    id, tenant_id, user_id, state, reason_code, event_version, effective_at, updated_at)
SELECT gen_random_uuid(), tenant_id, id, 'CLEARED', 'account_initialized', 1, now(), now()
FROM platform.app_user WHERE id = '$id';
"@ | Out-Null
    return $id
}

function Get-Prop {
    param($Object, [Parameter(Mandatory)][string]$Name)
    if ($null -eq $Object) { return $null }
    $p = $Object.PSObject.Properties[$Name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

function Get-SubscriptionId {
    param($Payload)
    $id = Get-Prop -Object $Payload -Name 'id'
    if (-not $id) { $id = Get-Prop -Object $Payload -Name 'subscriptionId' }
    return $id
}

function Connect-DemoUser {
    param([Parameter(Mandatory)][string]$Email, [string]$Password = '123456')
    $r = Invoke-Api -Method POST -Path '/console/platform/auth/login' -Body @{
        identifier = $Email
        password   = $Password
    }
    if ($r.Status -ne 200 -or -not $r.Body.accessToken) {
        throw "login failed for $Email -> status=$($r.Status) body=$($r.Body | ConvertTo-Json -Compress -Depth 5) err=$($r.Error)"
    }
    return $r.Body.accessToken
}

function Invoke-Sql {
    param([Parameter(Mandatory)][string]$Sql, [switch]$TuplesOnly)
    $prefix = if ($TuplesOnly) { "\pset tuples_only on`n\pset format unaligned`n" } else { "\pset pager off`n" }
    $out = ($prefix + $Sql) | docker exec -i -e "PGPASSWORD=$script:PgPassword" $script:PgContainer `
        psql -U postgres -d $script:PgDatabase -v ON_ERROR_STOP=1 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed ($LASTEXITCODE): $($out -join "`n")"
    }
    return ($out | Where-Object {
            $_ -notmatch '^Pager usage is off\.$' -and
            $_ -notmatch '^Tuples only is on\.$' -and
            $_ -notmatch '^Output format is unaligned\.$'
        })
}

function Invoke-SqlScalar {
    param([Parameter(Mandatory)][string]$Sql)
    $rows = Invoke-Sql -Sql $Sql -TuplesOnly
    $value = ($rows | Where-Object { $_ -ne '' } | Select-Object -First 1)
    if ($null -eq $value) { return $null }
    return $value.Trim()
}

function Get-UserId {
    param([Parameter(Mandatory)][string]$Email)
    return Invoke-SqlScalar "SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email');"
}

<#
.SYNOPSIS
Logs in as the local bootstrap administrator.
.DESCRIPTION
That account is created by the `local-bootstrap` compose service
(deploy/postgres/bootstrap-local.sql), NOT by the plan seed scripts, so the service
must have run at least once. Credentials come from deploy/env/.env
(LOCAL_ADMIN_EMAIL / LOCAL_ADMIN_PASSWORD).
#>
function Connect-LocalAdmin {
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy/env/.env'
    if (-not (Test-Path $envFile)) { throw "missing $envFile" }
    $email = $null
    $password = $null
    # Read as explicit UTF-8: Windows PowerShell 5.1's Get-Content defaults to the ANSI
    # code page (GBK here), which mangles the file's Chinese comments badly enough that
    # line matching silently fails.
    foreach ($line in [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8)) {
        if ($line -match '^\s*LOCAL_ADMIN_EMAIL\s*=\s*(.+?)\s*$') { $email = $Matches[1] }
        if ($line -match '^\s*LOCAL_ADMIN_PASSWORD\s*=\s*(.+?)\s*$') { $password = $Matches[1] }
    }
    if (-not $email -or -not $password) { throw 'LOCAL_ADMIN_EMAIL / LOCAL_ADMIN_PASSWORD not set in deploy/env/.env' }
    $r = Invoke-Api -Method POST -Path '/console/platform/auth/login' -Body @{
        identifier = $email
        password   = $password
    }
    if ($r.Status -ne 200 -or -not $r.Body.accessToken) {
        throw "admin login failed -> status=$($r.Status) body=$($r.Body | ConvertTo-Json -Compress -Depth 5)"
    }
    return $r.Body.accessToken
}

<#
.SYNOPSIS
Posts an intentionally raw/invalid JSON body and returns status plus the raw text.
.DESCRIPTION
Used for negative tests whose body must not be well-formed.
#>
function Invoke-ApiRaw {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Token,
        [Parameter(Mandatory)][string]$Json
    )
    $headers = @{ 'X-Device-Id' = $script:DeviceId }
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $params = @{
        Method      = $Method
        Uri         = "$script:BaseUrl$Path"
        Headers     = $headers
        ContentType = 'application/json; charset=utf-8'
        TimeoutSec  = 30
        Body        = [System.Text.Encoding]::UTF8.GetBytes($Json)
    }
    try {
        $resp = Invoke-WebRequest @params -UseBasicParsing
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Text = $resp.Content }
    } catch {
        $status = 0
        $text = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode.value__
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $text = $sr.ReadToEnd()
            } catch { }
        }
        return [pscustomobject]@{ Status = $status; Text = $text }
    }
}

function Get-SubscriptionFieldById {
    param([Parameter(Mandatory)][string]$SubscriptionId, [Parameter(Mandatory)][string]$Field)
    return Invoke-SqlScalar "SELECT $Field FROM platform.subscription WHERE id = '$SubscriptionId';"
}

function Set-WalletBalance {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][long]$Cents)
    Invoke-Sql "UPDATE platform.account_balance SET available_amount = $Cents WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'));" | Out-Null
    return (Get-WalletBalance -Email $Email)
}

function Get-WalletBalance {
    param([Parameter(Mandatory)][string]$Email)
    return [long](Invoke-SqlScalar "SELECT available_amount FROM platform.account_balance WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'));")
}

function Get-PublishedVersionId {
    param([Parameter(Mandatory)][string]$PlanCode)
    return Invoke-SqlScalar @"
SELECT v.id FROM platform.package_plan p
JOIN platform.package_plan_version v ON v.package_plan_id = p.id AND v.status = 'PUBLISHED'
WHERE p.code = '$PlanCode';
"@
}

function Get-SubscriptionRow {
    param([Parameter(Mandatory)][string]$Email)
    $rows = Invoke-Sql -Sql @"
\x on
SELECT s.id, s.status, s.auto_renew, s.cancel_at_period_end, s.consecutive_failures,
       s.renewal_count, s.last_charged_amount, s.last_skip_reason,
       s.current_period_start, s.current_period_end, s.next_charge_at, s.grace_until
FROM platform.subscription s
JOIN platform.app_user u ON u.id = s.user_id
WHERE lower(u.email) = lower('$Email')
ORDER BY s.created_at DESC LIMIT 1;
"@
    $text = ($rows -join "`n")
    if ($text -notmatch 'id\s+\|') { return $null }
    $obj = [ordered]@{}
    foreach ($line in ($text -split "`n")) {
        if ($line -match '^\s*([a-z_]+)\s+\|\s*(.*?)\s*$') {
            $obj[$Matches[1]] = $Matches[2]
        }
    }
    return [pscustomobject]$obj
}

function Get-CycleRows {
    param([Parameter(Mandatory)][string]$Email)
    return Invoke-Sql -Sql @"
\pset pager off
SELECT c.period_index, c.status, c.amount, c.attempt_count, c.failure_reason,
       (c.order_id IS NOT NULL) AS has_order, (c.charged_at IS NOT NULL) AS charged
FROM platform.subscription_cycle c
JOIN platform.subscription s ON s.id = c.subscription_id
JOIN platform.app_user u ON u.id = s.user_id
WHERE lower(u.email) = lower('$Email')
ORDER BY period_index;
"@
}

function Get-LedgerCount {
    param([Parameter(Mandatory)][string]$Email)
    return [int](Invoke-SqlScalar @"
SELECT count(*) FROM platform.balance_ledger l
JOIN platform.app_user u ON u.id = l.user_id
WHERE lower(u.email) = lower('$Email');
"@)
}

function Get-OrderCount {
    param([Parameter(Mandatory)][string]$Email)
    return [int](Invoke-SqlScalar @"
SELECT count(*) FROM platform.customer_order o
JOIN platform.app_user u ON u.id = o.user_id
WHERE lower(u.email) = lower('$Email');
"@)
}

function Get-ActiveInstanceCount {
    param([Parameter(Mandatory)][string]$Email)
    return [int](Invoke-SqlScalar @"
SELECT count(*) FROM gateway.package_instance pi
JOIN platform.app_user u ON u.id = pi.user_id
WHERE lower(u.email) = lower('$Email') AND pi.status = 'ACTIVE';
"@)
}

function Set-SubscriptionDueNow {
    param([Parameter(Mandatory)][string]$Email, [int]$DaysBack = 1)
    Invoke-Sql @"
UPDATE platform.subscription s
SET current_period_start = now() - interval '${DaysBack} days' - interval '30 days',
    current_period_end   = now() - interval '${DaysBack} days',
    next_charge_at       = now() - interval '${DaysBack} days',
    claim_token = NULL, claimed_until = NULL, updated_at = now()
WHERE s.id = (
    SELECT s2.id FROM platform.subscription s2
    JOIN platform.app_user u ON u.id = s2.user_id
    WHERE lower(u.email) = lower('$Email')
    ORDER BY s2.created_at DESC LIMIT 1);
"@ | Out-Null
}

<#
.SYNOPSIS
Opens the retry window so the next scheduler tick makes another attempt.
.DESCRIPTION
On a failed charge, recordFailure() advances next_charge_at by a WHOLE PERIOD (not by
the backoff duration) so the user gets one attempt per period inside the grace window.
To exercise repeated failures in a test we therefore have to pull the subscription
watermark back, and pull the FAILED cycle's next_attempt_at back with it.
This only manipulates the test clock substitute, never production semantics.
#>
function Open-RetryWindow {
    param([Parameter(Mandatory)][string]$Email)
    Invoke-Sql @"
UPDATE platform.subscription SET next_charge_at = now() - interval '1 minute', updated_at = now()
WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'));
UPDATE platform.subscription_cycle SET next_attempt_at = now() - interval '1 minute'
WHERE status = 'FAILED'
  AND subscription_id = (SELECT s.id FROM platform.subscription s
                         JOIN platform.app_user u ON u.id = s.user_id
                         WHERE lower(u.email) = lower('$Email'));
"@ | Out-Null
}

function Expire-ActiveInstances {
    param([Parameter(Mandatory)][string]$Email)
    Invoke-Sql @"
UPDATE gateway.package_instance pi
SET status = 'EXPIRED', updated_at = now()
WHERE pi.user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$Email'))
  AND pi.status = 'ACTIVE';
"@ | Out-Null
}

function Get-NotificationRows {
    param([Parameter(Mandatory)][string]$Email, [int]$Limit = 20)
    return Invoke-Sql -Sql @"
\pset pager off
SELECT e.event_type, e.category, e.status, n.title, e.created_at
FROM platform.notification_event e
LEFT JOIN platform.in_app_notification n ON n.event_id = e.id
JOIN platform.app_user u ON u.id = e.user_id
WHERE lower(u.email) = lower('$Email')
ORDER BY e.created_at DESC LIMIT $Limit;
"@
}

function Get-NotificationCount {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$EventType)
    return [int](Invoke-SqlScalar @"
SELECT count(*) FROM platform.notification_event e
JOIN platform.app_user u ON u.id = e.user_id
WHERE lower(u.email) = lower('$Email') AND e.event_type = '$EventType';
"@)
}

function Wait-For {
    param(
        [Parameter(Mandatory)][scriptblock]$Condition,
        [int]$TimeoutSeconds = 90,
        [int]$IntervalSeconds = 5,
        [string]$Description = 'condition'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $result = & $Condition
        if ($result) { return $result }
        Start-Sleep -Seconds $IntervalSeconds
    }
    throw "timeout after ${TimeoutSeconds}s waiting for $Description"
}

function Assert-Equal {
    param($Actual, $Expected, [string]$What)
    if ("$Actual" -ne "$Expected") {
        throw "ASSERT FAILED [$What]: expected '$Expected' but got '$Actual'"
    }
    Write-Host "  [ok] $What = $Actual" -ForegroundColor Green
}

function Assert-True {
    param($Value, [string]$What)
    if (-not $Value) { throw "ASSERT FAILED [$What]: expected truthy, got '$Value'" }
    Write-Host "  [ok] $What" -ForegroundColor Green
}

function Write-Step {
    param([string]$Text)
    Write-Host ""
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}
