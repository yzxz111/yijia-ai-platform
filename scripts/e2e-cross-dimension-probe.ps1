# Cross-dimension renewal guard probe.
#
# This feature's design was written against `uq_active_package_per_user`, documented as
# `UNIQUE (user_id) WHERE status='ACTIVE'` -- one active instance per user, globally. Its
# `active_package_present` guard therefore skips a renewal whenever the user holds ANY active
# instance, and the javadoc says so explicitly.
#
# V80__dimension_scoped_active_packages.sql (a migration on this branch) replaced that index
# with `uq_active_package_per_user_dimension` = `UNIQUE (user_id, usage_dimension) WHERE
# status='ACTIVE'`. Its own header states the intent: "the accurate meaning of holding both
# kinds of package is one ACTIVE instance per dimension". Cross-dimension coexistence is now
# expected and legal.
#
# So the guard's premise is stale: a user with an active VIDEO_SECOND package now blocks the
# renewal of an unrelated TOKEN subscription. This probe establishes whether that is real.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

function Open-Subscription {
    param([Parameter(Mandatory)]$Ctx, [string]$Plan = 'STARTER')
    $r = Invoke-Api -Method POST -Path '/console/platform/subscriptions' -Token $Ctx.Token `
        -IdempotencyKey ([guid]::NewGuid().ToString()) `
        -Body @{ packageVersionId = Get-PublishedVersionId -PlanCode $Plan }
    if ($r.Status -ne 201) { throw "open failed: status=$($r.Status) body=$($r.Body | ConvertTo-Json -Compress)" }
    return (Get-SubscriptionId -Payload $r.Body)
}

function Set-Up-DueRenewal {
    param([Parameter(Mandatory)]$Ctx, [int]$TimeoutSeconds = 150)
    $null = Wait-For -TimeoutSeconds $TimeoutSeconds -IntervalSeconds 5 -Description 'first period instance to activate' -Condition {
        if ((Get-ActiveInstanceCount -Email $Ctx.Email) -ge 1) { return $true }
        return $null
    }
    Set-SubscriptionDueNow -Email $Ctx.Email
    $stable = $false
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Expire-ActiveInstances -Email $Ctx.Email
        Start-Sleep -Seconds 3
        if ((Get-ActiveInstanceCount -Email $Ctx.Email) -eq 0) { $stable = $true; break }
    }
    if (-not $stable) { throw 'active instance still present after repeated expiry attempts' }
}

Write-Step 'setup: a TOKEN subscription, with period 1 charged and its instance expired'
$email = "sub-e2e-xdim-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
New-SubscriptionTestUser -Email $email | Out-Null
Set-WalletBalance -Email $email -Cents 100000 | Out-Null
$ctx = [pscustomobject]@{ Email = $email; Token = Connect-DemoUser -Email $email }
$subId = Open-Subscription -Ctx $ctx
$userId = Get-UserId -Email $email
$tenantId = Invoke-SqlScalar "SELECT tenant_id FROM platform.app_user WHERE id = '$userId';"

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'period 1 instance to activate' -Condition {
    if ((Get-ActiveInstanceCount -Email $ctx.Email) -ge 1) { return $true }
    return $null
}
# Order matters. Make the subscription due FIRST, then expire the instances -- and re-check.
# Doing it the other way round lets the scheduler charge and activate a replacement TOKEN
# instance between the two statements, which then looks like an unexplained ACTIVE instance
# and makes this probe report a false finding.
Set-SubscriptionDueNow -Email $ctx.Email
$drained = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Expire-ActiveInstances -Email $ctx.Email
    Start-Sleep -Seconds 3
    if ((Get-ActiveInstanceCount -Email $ctx.Email) -eq 0) { $drained = $true; break }
}
if (-not $drained) { throw 'a TOKEN instance stayed ACTIVE after repeated expiry attempts' }
Assert-Equal (Get-ActiveInstanceCount -Email $ctx.Email) 0 'no ACTIVE instance before the probe'

Write-Step 'give the user an ACTIVE VIDEO_SECOND instance (a legitimate cross-dimension coexistence)'
$videoInstance = Invoke-SqlScalar @"
INSERT INTO gateway.package_instance(
    id, grant_event_id, grant_request_id, tenant_id, user_id, package_plan_version_id,
    package_code, package_name, price_amount, currency, allowed_models, total_token_quota,
    max_api_keys, requests_per_minute, tokens_per_minute, max_concurrency, duration_days,
    usage_dimension, status, starts_at, expires_at, activated_at, identity_key,
    max_file_size_bytes)
SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), '$tenantId', '$userId',
       '00000000-0000-4000-8000-000000000122',
       'VIDEO_XDIM_PROBE', 'cross-dimension probe video package', 0, 'CNY', jsonb_build_object(), 1000,
       1, 10, 1000, 1, 30,
       'VIDEO_SECOND', 'ACTIVE', now(), now() + make_interval(days => 30), now(),
       'xdim-probe-' || replace(gen_random_uuid()::text, '-', ''), 20971520
RETURNING id;
"@
Assert-True ($null -ne $videoInstance) "active VIDEO_SECOND instance created ($videoInstance)"

$dimensions = Invoke-Sql -TuplesOnly @"
SELECT usage_dimension || '=' || status FROM gateway.package_instance
WHERE user_id = '$userId' ORDER BY usage_dimension;
"@
Write-Host "  instances now: $($dimensions -join ', ')"
Write-Host '  -- proof that cross-dimension coexistence is legal under the current index --'
Invoke-Sql @"
\pset pager off
SELECT pi.usage_dimension, pi.status FROM gateway.package_instance pi
WHERE pi.user_id = '$userId' ORDER BY pi.usage_dimension;
"@ | ForEach-Object { Write-Host "  $_" }

Write-Step 'the scheduler must still charge the TOKEN subscription'
$walletBefore = Get-WalletBalance -Email $ctx.Email
$ordersBefore = Get-OrderCount -Email $ctx.Email
Write-Host "  before: wallet=$walletBefore orders=$ordersBefore renewal_count=$((Get-SubscriptionRow -Email $ctx.Email).renewal_count)"

$charged = $false
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
    if ((Get-SubscriptionRow -Email $ctx.Email).renewal_count -eq '1') { $charged = $true; break }
    Start-Sleep -Seconds 5
}

$row = Get-SubscriptionRow -Email $ctx.Email
$walletAfter = Get-WalletBalance -Email $ctx.Email
Write-Host "  after:  wallet=$walletAfter orders=$(Get-OrderCount -Email $ctx.Email) renewal_count=$($row.renewal_count) last_skip_reason=$($row.last_skip_reason)"

if ($charged -and $walletAfter -lt $walletBefore) {
    Write-Host ''
    Write-Host '  [ok] the TOKEN subscription renewed despite the unrelated VIDEO_SECOND instance' -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host '  FINDING: the renewal was blocked by an UNRELATED-dimension instance.' -ForegroundColor Yellow
    Write-Host "    last_skip_reason = $($row.last_skip_reason)"
    Write-Host '    hasActivePackageInstance() asks only "does this user have ANY ACTIVE instance"'
    Write-Host '    (SubscriptionRepository.java:196-202) and its javadoc still cites the GLOBAL'
    Write-Host '    uq_active_package_per_user. V80 replaced that index with a per-dimension one, so'
    Write-Host '    cross-dimension coexistence is now legal and expected -- which turns a guard that'
    Write-Host '    used to be equivalent into one that skips unrelated subscriptions.'
    Write-Host '    Effect: a user holding a video package never gets their text subscription renewed.'
    Write-Host ''
    Write-Host '    The fix is to scope the guard to the dimension of the plan being renewed, which'
    Write-Host '    needs package_plan_version.usage_dimension for that subscription.'
}
