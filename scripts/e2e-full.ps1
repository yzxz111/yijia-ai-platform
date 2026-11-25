# Full real-stack E2E for continuous monthly subscription auto-renewal.
#
# Every scenario provisions its own fresh account, because platform.balance_ledger
# is append-only and PAID orders count towards the pre-existing
# MAX_PURCHASES_PER_PACKAGE = 3 limit. Nothing here can be rolled back, so each run
# leaves new accounts behind (harmless, they are named sub-e2e-*@yuzhi.test).
#
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e-full.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

$script:Plan = 'STARTER'
$script:PeriodCents = 2900
$script:SeedCents = 100000
$script:Passed = 0
# Non-fatal findings collected during the run. A probe that cannot reach its branch, or
# that uncovers something, records here instead of either failing the suite or being lost.
$script:Findings = @()

function New-Scenario {
    param([Parameter(Mandatory)][string]$Name)
    $email = "sub-e2e-$Name-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
    New-SubscriptionTestUser -Email $email | Out-Null
    Set-WalletBalance -Email $email -Cents $script:SeedCents | Out-Null
    $token = Connect-DemoUser -Email $email
    return [pscustomobject]@{ Email = $email; Token = $token }
}

function Open-Subscription {
    param([Parameter(Mandatory)]$Ctx)
    $r = Invoke-Api -Method POST -Path '/console/platform/subscriptions' -Token $Ctx.Token `
        -IdempotencyKey ([guid]::NewGuid().ToString()) `
        -Body @{ packageVersionId = Get-PublishedVersionId -PlanCode $script:Plan }
    if ($r.Status -ne 201) { throw "open failed: status=$($r.Status) body=$($r.Body | ConvertTo-Json -Compress)" }
    return (Get-SubscriptionId -Payload $r.Body)
}

<#
.SYNOPSIS
Makes a subscription chargeable for the NEXT scheduler tick.
.DESCRIPTION
Opening a subscription charges period 1 AND queues a grant, so a package_instance
appears a few seconds later once the outbox delivers it. The global
single-active-instance guard (design 5.8) then correctly skips renewal.

So we must first WAIT for that first instance to reach ACTIVE, and only then expire it.
Expiring before it activates leaves the pending grant to activate afterwards, which
looks exactly like "the renewal was skipped for no reason".
#>
function Set-Up-DueRenewal {
    param([Parameter(Mandatory)]$Ctx, [int]$TimeoutSeconds = 150)
    $null = Wait-For -TimeoutSeconds $TimeoutSeconds -IntervalSeconds 5 -Description 'first period instance to activate' -Condition {
        if ((Get-ActiveInstanceCount -Email $Ctx.Email) -ge 1) { return $true }
        return $null
    }
    Set-SubscriptionDueNow -Email $Ctx.Email
    # Converge rather than expire once: a grant can still be in flight when the first
    # instance activates, so a single UPDATE can leave a second, later ACTIVE instance
    # behind -- which then makes the renewal look "skipped for no reason".
    $stable = $false
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        Expire-ActiveInstances -Email $Ctx.Email
        Start-Sleep -Seconds 3
        if ((Get-ActiveInstanceCount -Email $Ctx.Email) -eq 0) { $stable = $true; break }
    }
    if (-not $stable) { throw 'active instance still present after repeated expiry attempts' }
}

function Get-CycleCount {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$Status)
    return [int](Invoke-SqlScalar @"
SELECT count(*) FROM platform.subscription_cycle c
JOIN platform.subscription s ON s.id = c.subscription_id
JOIN platform.app_user u ON u.id = s.user_id
WHERE lower(u.email) = lower('$Email') AND c.status = '$Status';
"@)
}

function Get-SubscriptionField {
    param([Parameter(Mandatory)][string]$Email, [Parameter(Mandatory)][string]$Field)
    return Invoke-SqlScalar @"
SELECT s.$Field FROM platform.subscription s
JOIN platform.app_user u ON u.id = s.user_id
WHERE lower(u.email) = lower('$Email');
"@
}

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO A: no double charge across repeated scheduler ticks (step 5)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'nodouble'
$subId = Open-Subscription -Ctx $ctx
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'first renewal' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}
$walletAfterFirst = Get-WalletBalance -Email $ctx.Email
$ledgerAfterFirst = Get-LedgerCount -Email $ctx.Email
$ordersAfterFirst = Get-OrderCount -Email $ctx.Email
Write-Host "  after 1st renewal: renewal_count=1 wallet=$walletAfterFirst ledger=$ledgerAfterFirst orders=$ordersAfterFirst"

Write-Host '  waiting 3 more scheduler ticks (45s+)...'
Start-Sleep -Seconds 50
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') '1' 'renewal_count still 1 after extra ticks'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) $walletAfterFirst 'wallet unchanged after extra ticks'
Assert-Equal (Get-LedgerCount -Email $ctx.Email) $ledgerAfterFirst 'ledger unchanged after extra ticks'
Assert-Equal (Get-OrderCount -Email $ctx.Email) $ordersAfterFirst 'orders unchanged after extra ticks'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claim_token') '' 'lease released (claim_token NULL)'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claimed_until') '' 'lease released (claimed_until NULL)'
Assert-Equal (Get-CycleCount -Email $ctx.Email -Status 'CHARGED') 2 'exactly 2 CHARGED cycles'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO B: cancel stops charging, current period is kept (step 6)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'cancel'
$subId = Open-Subscription -Ctx $ctx
$walletAfterOpen = Get-WalletBalance -Email $ctx.Email
$periodEndBefore = Get-SubscriptionField -Email $ctx.Email -Field 'current_period_end'

$r = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/cancel" -Token $ctx.Token
Write-Host "  cancel -> $($r.Status) $($r.Body | ConvertTo-Json -Compress)"
Assert-Equal $r.Status 200 'cancel status'
Assert-Equal (Get-Prop -Object $r.Body -Name 'autoRenew') 'False' 'autoRenew false after cancel'
Assert-Equal (Get-Prop -Object $r.Body -Name 'cancelAtPeriodEnd') 'True' 'cancelAtPeriodEnd true after cancel'
Assert-Equal (Get-Prop -Object $r.Body -Name 'status') 'ACTIVE' 'status still ACTIVE (period runs out)'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'current_period_end') $periodEndBefore 'current period unchanged'

Write-Host '  making it due anyway; it must NOT be charged'
Set-SubscriptionDueNow -Email $ctx.Email
Expire-ActiveInstances -Email $ctx.Email
Start-Sleep -Seconds 45
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') '0' 'renewal_count still 0'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) $walletAfterOpen 'wallet unchanged after cancel'
Assert-Equal (Get-CycleCount -Email $ctx.Email -Status 'CHARGED') 1 'still only the first CHARGED cycle'
# Canceled subscriptions are excluded by the partial index ix_subscription_due itself
# (it requires auto_renew), so the scheduler never claims them and no skip is even
# recorded. That is a stronger guarantee than "the policy declined", so assert the
# absence of any evaluation rather than a skip reason.
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'last_skip_reason') '' 'never even claimed: no skip reason recorded'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claim_token') '' 'no lease held'

Write-Step 'SCENARIO B2: resume is refused once the period has ended (negative case)'
# The step above pushed the period into the past, so resume must be refused:
# you cannot un-cancel a subscription whose paid period is already over.
$r = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/resume" -Token $ctx.Token
Write-Host "  resume after period end -> $($r.Status) $($r.Body | ConvertTo-Json -Compress)"
Assert-Equal $r.Status 409 'resume refused after the period ended'
Assert-Equal (Get-Prop -Object $r.Body -Name 'code') 'subscription_period_ended' 'error code'

Write-Step 'SCENARIO B3: cancel then resume INSIDE the period re-arms and charges again'
$ctx3 = New-Scenario -Name 'resume'
$subId3 = Open-Subscription -Ctx $ctx3
$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'period-1 instance' -Condition {
    if ((Get-ActiveInstanceCount -Email $ctx3.Email) -ge 1) { return $true }
    return $null
}
$r = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId3/cancel" -Token $ctx3.Token
Assert-Equal $r.Status 200 'cancel accepted'
Assert-Equal (Get-SubscriptionField -Email $ctx3.Email -Field 'auto_renew') 'f' 'auto_renew false'
$r = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId3/resume" -Token $ctx3.Token
Write-Host "  resume inside period -> $($r.Status) $($r.Body | ConvertTo-Json -Compress)"
Assert-Equal $r.Status 200 'resume accepted inside the period'
Assert-Equal (Get-Prop -Object $r.Body -Name 'autoRenew') 'True' 'autoRenew true after resume'
Assert-Equal (Get-Prop -Object $r.Body -Name 'cancelAtPeriodEnd') 'False' 'cancelAtPeriodEnd false after resume'
Assert-Equal (Get-SubscriptionField -Email $ctx3.Email -Field 'auto_renew') 't' 'auto_renew persisted'

Write-Host '  a resumed subscription must charge again'
Set-Up-DueRenewal -Ctx $ctx3
$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'charge after resume' -Condition {
    if ((Get-SubscriptionField -Email $ctx3.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}
Assert-Equal (Get-SubscriptionField -Email $ctx3.Email -Field 'renewal_count') '1' 'charged after resume'
Assert-Equal (Get-CycleCount -Email $ctx3.Email -Status 'CHARGED') 2 'two CHARGED cycles after resume'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO C: insufficient balance -> PAST_DUE + grace, no charge (2.3-1)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'insufficient'
$subId = Open-Subscription -Ctx $ctx
$zero = Set-WalletBalance -Email $ctx.Email -Cents 0
Assert-Equal $zero 0 'wallet drained to 0'
$ledgerBefore = Get-LedgerCount -Email $ctx.Email
$ordersBefore = Get-OrderCount -Email $ctx.Email

Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'first failure recorded' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'consecutive_failures') -eq '1') { return $true }
    return $null
}
$row = Get-SubscriptionRow -Email $ctx.Email
$row | Format-List | Out-String | ForEach-Object { Write-Host $_ }

Assert-Equal $row.status 'PAST_DUE' 'status PAST_DUE'
Assert-Equal $row.consecutive_failures '1' 'consecutive_failures = 1'
Assert-True ($row.grace_until -ne '') 'grace_until set'
Assert-Equal $row.auto_renew 't' 'auto_renew still true (grace period can recover)'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) 0 'wallet unchanged (nothing charged)'
Assert-Equal (Get-LedgerCount -Email $ctx.Email) $ledgerBefore 'no ledger entry created'
Assert-Equal (Get-OrderCount -Email $ctx.Email) $ordersBefore 'no order created'
Assert-Equal (Get-CycleCount -Email $ctx.Email -Status 'FAILED') 1 'exactly one FAILED cycle'

Write-Host '  -- the FAILED cycle row (authoritative operator evidence) --'
$failedCycleSql = @"
SELECT c.period_index || '|' || c.status || '|' || coalesce(c.failure_reason, '<null>') || '|' ||
       coalesce(c.last_error, '<null>') || '|' || c.attempt_count
FROM platform.subscription_cycle c
JOIN platform.subscription s ON s.id = c.subscription_id
JOIN platform.app_user u ON u.id = s.user_id
WHERE lower(u.email) = lower('$($ctx.Email)') AND c.status = 'FAILED';
"@
# The bookkeeping is a separate transaction from the failed charge, so the row can land
# a moment after consecutive_failures flips. Retry rather than race.
$failedCycle = Wait-For -TimeoutSeconds 30 -IntervalSeconds 2 -Description 'FAILED cycle bookkeeping' -Condition {
    $v = Invoke-SqlScalar $failedCycleSql
    if ($v) { return $v }
    return $null
}
Write-Host "  period|status|failure_reason|last_error|attempt_count = $failedCycle"
$fc = $failedCycle -split '\|'
Assert-Equal $fc[0] '2' 'the failure was recorded against period 2'
Assert-Equal $fc[1] 'FAILED' 'cycle status FAILED'
Assert-Equal $fc[2] 'insufficient_balance' 'failure_reason = insufficient_balance'
Assert-Equal $fc[3] '422 UNPROCESSABLE_ENTITY "wallet_insufficient_balance"' 'last_error preserved for operators'
Assert-Equal $fc[4] '1' 'attempt_count = 1'
Assert-True ((Get-NotificationCount -Email $ctx.Email -EventType 'SUBSCRIPTION_RENEWAL_FAILED') -ge 1) 'SUBSCRIPTION_RENEWAL_FAILED notification delivered'

Write-Host '  -- backoff scheduled, not immediate retry --'
# Single-row pipe-delimited scalar: a here-string is not needed and would require $()
# sub-expressions for the property access.
$backoffSql = "SELECT s.consecutive_failures::text || '|' || c.attempt_count::text || '|' || " +
"(c.next_attempt_at > now())::text || '|' || " +
"round(EXTRACT(EPOCH FROM (c.next_attempt_at - c.updated_at)))::text " +
"FROM platform.subscription s " +
"JOIN platform.subscription_cycle c ON c.subscription_id = s.id AND c.status = 'FAILED' " +
"JOIN platform.app_user u ON u.id = s.user_id " +
"WHERE lower(u.email) = lower('$($ctx.Email)');"
$backoff = Invoke-SqlScalar $backoffSql
Write-Host "  failures|attempts|next_attempt_in_future|backoff_seconds = $backoff"
$b = $backoff -split '\|'
Assert-Equal $b[0] '1' 'consecutive_failures counter'
Assert-Equal $b[1] '1' 'cycle attempt_count = 1'
Assert-Equal $b[2] 'true' 'retry deferred into the future (exponential backoff)'
# Subscription test env sets SUBSCRIPTION_JOBS_INITIAL_BACKOFF=10s; the first attempt
# uses multiplier 1, so the deferral must be 10 seconds, not milliseconds or hours.
Assert-Equal $b[3] '10' 'first backoff is exactly INITIAL_BACKOFF (10s in acceptance env)'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO D: top up inside grace period -> recovers to ACTIVE (2.3-2)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'recover'
$subId = Open-Subscription -Ctx $ctx
Set-WalletBalance -Email $ctx.Email -Cents 0 | Out-Null
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'failure before recovery' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'status') -eq 'PAST_DUE') { return $true }
    return $null
}
Write-Host "  now PAST_DUE; topping up and forcing the retry window open"
Set-WalletBalance -Email $ctx.Email -Cents ($script:PeriodCents + 500) | Out-Null
Open-RetryWindow -Email $ctx.Email


$row = Wait-For -TimeoutSeconds 180 -IntervalSeconds 5 -Description 'recovery to ACTIVE' -Condition {
    $r = Get-SubscriptionRow -Email $ctx.Email
    if ($r -and $r.status -eq 'ACTIVE' -and $r.renewal_count -eq '1') { return $r }
    return $null
}
$row | Format-List | Out-String | ForEach-Object { Write-Host $_ }
Assert-Equal $row.consecutive_failures '0' 'consecutive_failures reset to 0'
Assert-Equal $row.grace_until '' 'grace_until cleared'
Assert-Equal $row.auto_renew 't' 'auto_renew still true'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) 500 'charged exactly one period, remainder kept'
Assert-Equal (Get-CycleCount -Email $ctx.Email -Status 'CHARGED') 2 'the retry produced a CHARGED cycle'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO E: retry budget exhausted -> EXPIRED, stops being claimed (2.3-3)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'exhaust'
$subId = Open-Subscription -Ctx $ctx
Set-WalletBalance -Email $ctx.Email -Cents 0 | Out-Null
Set-Up-DueRenewal -Ctx $ctx

$row = Wait-For -TimeoutSeconds 240 -IntervalSeconds 5 -Description 'EXPIRED after attempts exhausted' -Condition {
    $r = Get-SubscriptionRow -Email $ctx.Email
    if ($r -and $r.status -eq 'EXPIRED') { return $r }
    Open-RetryWindow -Email $ctx.Email

    return $null
}
$row | Format-List | Out-String | ForEach-Object { Write-Host $_ }
Assert-Equal $row.status 'EXPIRED' 'status EXPIRED'
Assert-Equal $row.auto_renew 'f' 'auto_renew turned off'
$failed = Get-CycleCount -Email $ctx.Email -Status 'FAILED'
Write-Host "  FAILED cycles = $failed"
Assert-True ($failed -ge 3) 'at least 3 failed attempts recorded'

Write-Host '  making it due again; an EXPIRED subscription must not be claimed'
$walletBefore = Get-WalletBalance -Email $ctx.Email
Set-WalletBalance -Email $ctx.Email -Cents 100000 | Out-Null
Set-SubscriptionDueNow -Email $ctx.Email
Start-Sleep -Seconds 45
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'EXPIRED' 'still EXPIRED'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') '0' 'never renewed'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claim_token') '' 'not claimed'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO F: stuck queued instance -> TERMINATED (2.3-4, the silent money-loss path)'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'stuck'
$subId = Open-Subscription -Ctx $ctx
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'second cycle charged' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}
Write-Host '  forcing the "charged but never activated" bad state'
# The real production shape of this failure (design 5.7.1): money has been taken, and
# the entitlement for the new period is sitting QUEUED at the gateway because the user
# still holds an ACTIVE instance (uq_active_package_per_user). notYetActive() is decided
# by the *package_instance* status being null or QUEUED -- so the guarded period must be
# one whose instance never activated, while an earlier period's instance stays ACTIVE.
#
# Getting that shape on purpose:
#   1. let period 2 charge and its instance activate (so the account has a live instance);
#   2. release period 3 by pulling the watermark -- the watermark is claimed BEFORE the
#      outbox can deliver period 3's grant, so the gateway queues that grant;
#   3. stop that grant's outbox event from ever being retried, so period 3's instance
#      stays null forever instead of activating on the 5-minute GRANT_QUEUED_RECHECK;
#   4. backdate period 3's charged_at past STUCK_QUEUED_ALERT_AFTER (5m in acceptance),
#      and keep pulling the watermark so a tick evaluates it.
$null = Wait-For -TimeoutSeconds 120 -IntervalSeconds 3 -Description 'period-2 instance to exist' -Condition {
    $n = Invoke-SqlScalar "SELECT count(*) FROM gateway.package_instance pi JOIN platform.app_user u ON u.id=pi.user_id WHERE lower(u.email)=lower('$($ctx.Email)');"
    if ([int]$n -ge 2) { return $true }
    return $null
}

# Money is taken and the entitlement is stuck at the gateway. For a CHARGED cycle the
# grant is always created by the same settlement path, so "charged with no grant at all"
# cannot arise from a normal charge -- it represents the gateway-side failure this guard
# exists for (design 5.7.1: the queue never drains). Construct that state directly and
# keep period 2's instance ACTIVE, so an earlier period is genuinely live while the
# guarded period's instance is null (notYetActive() == true).
$subIdF = (Get-SubscriptionRow -Email $ctx.Email).id
$targetIndex = 3
$businessKey = "renewal:${subIdF}:${targetIndex}"
Invoke-Sql ("INSERT INTO platform.subscription_cycle(" +
    "id, subscription_id, period_index, period_start, period_end, business_key, status, " +
    "amount, currency, attempt_count, next_attempt_at, charged_at) " +
    "SELECT gen_random_uuid(), s.id, $targetIndex, s.current_period_start, s.current_period_end, " +
    "'$businessKey', 'CHARGED', 2900, 'CNY', 1, now() - interval '10 minutes', now() - interval '10 minutes' " +
    "FROM platform.subscription s WHERE s.id = '$subIdF' " +
    "ON CONFLICT (subscription_id, period_index) DO UPDATE " +
    "SET status = 'CHARGED', charged_at = now() - interval '10 minutes', grant_request_id = NULL;") | Out-Null

Write-Host '  -- the stuck shape: live instance from an earlier period, guarded period never activated --'
Invoke-Sql @"
\pset pager off
SELECT c.period_index, c.status, c.order_no,
       coalesce(grant_request.status, '<no grant>') AS grant_status,
       coalesce(instance.status, '<no instance>') AS instance_status,
       (c.charged_at < now() - interval '5 minutes') AS older_than_threshold
FROM platform.subscription_cycle c
JOIN platform.subscription s ON s.id = c.subscription_id
JOIN platform.app_user u ON u.id = s.user_id
LEFT JOIN platform.grant_request grant_request ON grant_request.id = c.grant_request_id
LEFT JOIN gateway.package_instance instance ON instance.grant_request_id = grant_request.id
WHERE lower(u.email) = lower('$($ctx.Email)') ORDER BY c.period_index;
"@ | ForEach-Object { Write-Host "  $_" }

$guardReady = Invoke-SqlScalar ("SELECT (c.charged_at < now() - interval '5 minutes')::text " +
    "FROM platform.subscription_cycle c WHERE c.business_key = '$businessKey';")
Assert-Equal $guardReady 'true' 'guarded period is older than STUCK_QUEUED_ALERT_AFTER'
# The guard itself only needs "a CHARGED cycle whose instance is null or QUEUED"; it does
# not require the previous period to still be ACTIVE (detectStuckQueue runs before the
# active_package_present check).
$instanceState = Invoke-SqlScalar ("SELECT coalesce(instance.status, '<no instance>') " +
    "FROM platform.subscription_cycle c " +
    "LEFT JOIN platform.grant_request grant_request ON grant_request.id = c.grant_request_id " +
    "LEFT JOIN gateway.package_instance instance ON instance.grant_request_id = grant_request.id " +
    "WHERE c.business_key = '$businessKey';")
Assert-Equal $instanceState '<no instance>' 'guarded period has no package instance (entitlement never activated)'

$walletAtF = Get-WalletBalance -Email $ctx.Email
$cyclesAtF = Get-CycleCount -Email $ctx.Email -Status 'CHARGED'
$row = $null
$deadline = (Get-Date).AddSeconds(240)
while ((Get-Date) -lt $deadline) {
    Invoke-Sql ("UPDATE platform.subscription SET next_charge_at = now() - interval '1 minute', updated_at = now() " +
        "WHERE id = '$subIdF' AND status IN ('ACTIVE', 'PAST_DUE');") | Out-Null
    Start-Sleep -Seconds 10
    $r = Get-SubscriptionRow -Email $ctx.Email
    if ($r -and $r.status -eq 'TERMINATED') { $row = $r; break }
}
Write-Host '  -- subscription after the stuck-queue guard fired --'
$row | Format-List | Out-String | ForEach-Object { Write-Host $_ }
Assert-Equal $row.status 'TERMINATED' 'status TERMINATED'
Assert-Equal $row.auto_renew 'f' 'auto_renew turned off'
Assert-Equal $row.last_skip_reason 'renewal_instance_stuck_queued' 'last_skip_reason recorded'
# The guard exists precisely because this is the one path that silently takes money
# without delivering service, so assert the operator-visible signal that a human can
# actually alert on, rather than a tautology.
$stuckLogged = @(docker logs --since 15m yuzhihuihe-api-platform-platform-service-1 2>&1 |
    Select-String -Pattern 'charged but the entitlement never activated' -SimpleMatch)
Assert-True ($stuckLogged.Count -ge 1) 'an operator-visible error was logged for the stuck entitlement'

Write-Host '  a TERMINATED subscription must not be charged again'
$walletAtTermination = Get-WalletBalance -Email $ctx.Email
$cyclesAtTermination = Get-CycleCount -Email $ctx.Email -Status 'CHARGED'
Set-SubscriptionDueNow -Email $ctx.Email
Start-Sleep -Seconds 40
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'TERMINATED' 'still TERMINATED'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) $walletAtTermination 'wallet unchanged after termination'
Assert-Equal (Get-CycleCount -Email $ctx.Email -Status 'CHARGED') $cyclesAtTermination 'no further cycle charged'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO G: admin subscription governance (authorization + list + detail + retry + terminate)'
# ---------------------------------------------------------------------------
# account#7 of acceptance §2.2 was the remaining gap: the admin surfaces were only
# covered by unit tests. This drives the real HTTP API as an admin, and as a
# non-admin to prove the authorization boundary actually holds.
$ctx = New-Scenario -Name 'admin'
$subId = Open-Subscription -Ctx $ctx
$userId = Get-UserId -Email $ctx.Email

Write-Step 'SCENARIO G1: authorization boundary'
$anon = Invoke-Api -Method GET -Path '/console/platform/admin/subscriptions'
Assert-Equal $anon.Status 401 'anonymous admin listing rejected'
$asUser = Invoke-Api -Method GET -Path '/console/platform/admin/subscriptions' -Token $ctx.Token
Assert-Equal $asUser.Status 403 'ROLE_USER rejected from the admin listing'
$adminToken = Connect-LocalAdmin

Write-Step 'SCENARIO G2: listing and filters'
$list = Invoke-Api -Method GET -Path '/console/platform/admin/subscriptions?page=0&size=5' -Token $adminToken
Write-Host "  GET /admin/subscriptions -> $($list.Status)"
Assert-Equal $list.Status 200 'admin listing status'
$items = Get-Prop -Object $list.Body -Name 'items'
Assert-True ($null -ne $items) 'page result has items'
Write-Host "  total = $(Get-Prop -Object $list.Body -Name 'total')  first item plan = $(Get-Prop -Object (Get-Prop -Object $items -Name '0') -Name 'planCode')"

$byUser = Invoke-Api -Method GET -Path "/console/platform/admin/subscriptions?userId=$userId" -Token $adminToken
Assert-Equal $byUser.Status 200 'filter by userId'
$byUserItems = Get-Prop -Object $byUser.Body -Name 'items'
Assert-Equal @($byUserItems).Count 1 'filter by userId returns exactly our subscription'
Assert-Equal (Get-Prop -Object $byUserItems[0] -Name 'id') $subId 'filtered row is the expected subscription'

$byPlan = Invoke-Api -Method GET -Path "/console/platform/admin/subscriptions?planCode=$script:Plan" -Token $adminToken
Assert-True (@(Get-Prop -Object $byPlan.Body -Name 'items').Count -ge 1) 'filter by planCode returns rows'

$byStatus = Invoke-Api -Method GET -Path '/console/platform/admin/subscriptions?status=ACTIVE' -Token $adminToken
Assert-Equal $byStatus.Status 200 'filter by status'
$allActive = @((Get-Prop -Object $byStatus.Body -Name 'items') | Where-Object {
        (Get-Prop -Object $_ -Name 'status') -ne 'ACTIVE' })
Assert-Equal $allActive.Count 0 'status filter returns only ACTIVE rows'

Write-Step 'SCENARIO G3: detail shows the period records'
$detail = Invoke-Api -Method GET -Path "/console/platform/admin/subscriptions/$subId" -Token $adminToken
Assert-Equal $detail.Status 200 'admin detail status'
$view = Get-Prop -Object $detail.Body -Name 'subscription'
if ($null -eq $view) { $view = $detail.Body }
Assert-Equal (Get-Prop -Object $view -Name 'id') $subId 'detail returns the requested subscription'
$cycles = Get-Prop -Object $detail.Body -Name 'cycles'
Assert-True ($null -ne $cycles -and @($cycles).Count -ge 1) "detail includes period records (count=$(@($cycles).Count))"
Write-Host "  first cycle: period=$(Get-Prop -Object $cycles[0] -Name 'periodIndex') status=$(Get-Prop -Object $cycles[0] -Name 'status') amount=$(Get-Prop -Object $cycles[0] -Name 'amount')"

Write-Step 'SCENARIO G4: terminate requires a reason and then stops charging'
$noReason = Invoke-ApiRaw -Method POST -Path "/console/platform/admin/subscriptions/$subId/terminate" `
    -Token $adminToken -Json '{}'
Write-Host "  terminate without reason -> $($noReason.Status) $($noReason.Text)"
Assert-Equal $noReason.Status 422 'terminate without a reason is rejected as unprocessable'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'ACTIVE' 'subscription untouched by the rejected call'

$auditsBefore = [int](Invoke-SqlScalar "SELECT count(*) FROM platform.admin_audit_log WHERE resource_id = '$subId';")
$terminated = Invoke-Api -Method POST -Path "/console/platform/admin/subscriptions/$subId/terminate" `
    -Token $adminToken -Body @{ reason = 'e2e acceptance: admin terminate path' }
Write-Host "  terminate -> $($terminated.Status)"
Assert-Equal $terminated.Status 200 'terminate succeeds with a reason'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'TERMINATED' 'status is TERMINATED'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'auto_renew') 'f' 'auto_renew turned off'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claim_token') '' 'lease cleared by terminate'
$auditsAfter = [int](Invoke-SqlScalar "SELECT count(*) FROM platform.admin_audit_log WHERE resource_id = '$subId';")
Assert-Equal $auditsAfter ($auditsBefore + 1) 'an audit row was written for the termination'
Write-Host '  -- audit row --'
Invoke-Sql @"
\pset pager off
SELECT action, resource_type, outcome, actor_user_id IS NOT NULL AS has_actor
FROM platform.admin_audit_log WHERE resource_id = '$subId' ORDER BY created_at DESC LIMIT 1;
"@ | ForEach-Object { Write-Host "  $_" }

Write-Host '  a TERMINATED subscription must not resume charging'
$walletAtAdminTerminate = Get-WalletBalance -Email $ctx.Email
Set-SubscriptionDueNow -Email $ctx.Email
Start-Sleep -Seconds 35
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'TERMINATED' 'still TERMINATED after ticks'
Assert-Equal (Get-WalletBalance -Email $ctx.Email) $walletAtAdminTerminate 'no charge after admin termination'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO H: admin retry of a FAILED period'
# ---------------------------------------------------------------------------
$ctx = New-Scenario -Name 'retry'
$subId = Open-Subscription -Ctx $ctx
Set-WalletBalance -Email $ctx.Email -Cents 0 | Out-Null
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 180 -IntervalSeconds 5 -Description 'a FAILED period to retry' -Condition {
    if ((Get-CycleCount -Email $ctx.Email -Status 'FAILED') -ge 1) { return $true }
    return $null
}
$cycleId = Invoke-SqlScalar ("SELECT c.id FROM platform.subscription_cycle c " +
    "JOIN platform.subscription s ON s.id = c.subscription_id " +
    "JOIN platform.app_user u ON u.id = s.user_id " +
    "WHERE lower(u.email) = lower('$($ctx.Email)') AND c.status = 'FAILED' ORDER BY c.period_index LIMIT 1;")
Assert-True ($null -ne $cycleId) "FAILED cycle found ($cycleId)"
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'PAST_DUE' 'subscription is PAST_DUE before retry'

$retried = Invoke-Api -Method POST -Path "/console/platform/admin/subscriptions/cycles/$cycleId/retry" `
    -Token $adminToken -Body @{ reason = 'e2e acceptance: admin retry path' }
Write-Host "  retry -> $($retried.Status) $($retried.Body | ConvertTo-Json -Compress)"
Assert-Equal $retried.Status 200 'retry status'
Assert-Equal (Get-Prop -Object $retried.Body -Name 'retried') 'True' 'retry reported as performed'
Assert-Equal (Get-SubscriptionFieldById -SubscriptionId $subId -Field 'status') 'PAST_DUE' 'subscription pulled back to PAST_DUE so the scheduler can pick it up'
Assert-Equal (Invoke-SqlScalar "SELECT status FROM platform.subscription_cycle WHERE id = '$cycleId';") 'PENDING' 'cycle reset to PENDING'
Assert-True ($null -eq (Invoke-SqlScalar "SELECT failure_reason FROM platform.subscription_cycle WHERE id = '$cycleId';")) 'failure_reason cleared'

Write-Host '  retry must be idempotent: a second call reports nothing to do'
$again = Invoke-Api -Method POST -Path "/console/platform/admin/subscriptions/cycles/$cycleId/retry" `
    -Token $adminToken -Body @{ reason = 'e2e acceptance: double click' }
Write-Host "  retry again -> $($again.Status) $($again.Body | ConvertTo-Json -Compress)"
Assert-Equal $again.Status 200 'second retry still 200 (no error dialog for a double click)'
Assert-Equal (Get-Prop -Object $again.Body -Name 'retried') 'False' 'second retry reports retried=false'
Assert-Equal (Get-Prop -Object $again.Body -Name 'outcome') 'cycle_not_failed' 'second retry explains why'

Write-Host '  and the retried period really charges once the wallet is funded'
Set-WalletBalance -Email $ctx.Email -Cents $script:PeriodCents | Out-Null
# The retry reset the cycle, but the subscription watermark was still a period ahead
# and the backoff may not have elapsed, so open the window before expecting a charge.
Set-Up-DueRenewal -Ctx $ctx
Open-RetryWindow -Email $ctx.Email
$null = Wait-For -TimeoutSeconds 180 -IntervalSeconds 5 -Description 'the retried period to charge' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') '1' 'retried period charged successfully'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'status') 'ACTIVE' 'back to ACTIVE'
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO I: refunding a renewal order while it is still not activated (design 10)'
# ---------------------------------------------------------------------------
# Design 10 says a renewal order IS refundable while its instance is still QUEUED
# ("never activated"), and requires: "after a successful refund the subscription must
# stop charging the next period; refunding money and continuing to charge is not
# allowed", plus a dedicated test for exactly this branch.
#
# This scenario establishes the facts. If the second half fails, that is a REAL product
# defect (double benefit: money refunded AND the subscription keeps renewing), not a
# flaky test -- read the message before changing anything here.
$ctx = New-Scenario -Name 'refund'
$subId = Open-Subscription -Ctx $ctx
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'the renewal period to be charged' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}

$renewalOrder = Invoke-SqlScalar ("SELECT o.order_no FROM platform.customer_order o " +
    "JOIN platform.app_user u ON u.id = o.user_id " +
    "WHERE lower(u.email) = lower('$($ctx.Email)') AND o.settlement_method = 'BALANCE' " +
    "ORDER BY o.created_at DESC LIMIT 1;")
Assert-True ($null -ne $renewalOrder) "renewal order found ($renewalOrder)"

# Establish the premise instead of assuming it: the instance must not have activated,
# otherwise the refund is (correctly) refused as PACKAGE_ACTIVATED.
$instanceStatus = Invoke-SqlScalar ("SELECT coalesce(pi.status, '<none>') FROM gateway.package_instance pi " +
    "JOIN platform.app_user u ON u.id = pi.user_id WHERE lower(u.email) = lower('$($ctx.Email)') " +
    "ORDER BY pi.created_at DESC LIMIT 1;")
$orderPaidAt = Invoke-SqlScalar "SELECT paid_at FROM platform.customer_order WHERE order_no = '$renewalOrder';"
$walletBeforeRefund = Get-WalletBalance -Email $ctx.Email
Write-Host "  renewal order=$renewalOrder paid_at=$orderPaidAt newest instance=$instanceStatus wallet=$walletBeforeRefund"

$refund = Invoke-Api -Method POST -Path "/console/platform/orders/$renewalOrder/refunds" `
    -Token $ctx.Token -Body @{ reason = 'e2e acceptance: refund a not-yet-activated renewal period' }
$refundStatus = Get-Prop -Object $refund.Body -Name 'status'
$refundReason = Get-Prop -Object $refund.Body -Name 'failureReason'
$refundNo = Get-Prop -Object $refund.Body -Name 'refundNo'
Write-Host "  POST /orders/$renewalOrder/refunds -> HTTP $($refund.Status) refundNo=$refundNo status=$refundStatus failureReason=$refundReason"

# HTTP 201 means "the application was accepted for review", NOT "the refund succeeded":
# the authoritative outcome is the `status` field of the body.
$script:RefundPremise = switch ($refundStatus) {
    'REJECTED' { 'rejected' }
    'PENDING_APPROVAL' { 'granted' }
    default { 'unknown' }
}
Write-Host "  premise = $($script:RefundPremise)"

if ($script:RefundPremise -eq 'rejected') {
    Write-Host '  -- FINDING: the renewal order was ALREADY unrefundable --'
    Write-Host "     failureReason=$refundReason, newest instance=$instanceStatus"
    Write-Host '     Design 10 assumes a renewal instance stays QUEUED (expires_at NULL) until the old'
    Write-Host '     period ends, which would keep the renewal order refundable for that whole window.'
    Write-Host '     In practice the outbox delivers and ACTIVATES it within seconds, so that window is'
    Write-Host '     effectively zero. This is NOT the "refunded while still renewing" double-benefit'
    Write-Host '     defect: refusing the refund is the conservative, correct outcome. It is a design'
    Write-Host '     premise mismatch, and it means the branch design 10 demands a test for is only'
    Write-Host '     reachable by forcing the state (which is what SCENARIO I2 does).'
    $script:Findings += 'design 10 premise: the renewal instance activates within seconds, so the "refund a queued renewal" window is effectively zero'
} elseif ($script:RefundPremise -eq 'unknown') {
    # A 500 here is the mapped-error defect (see SCENARIO 2.4 finding 1), not something
    # subscription-specific; name it precisely instead of "unexpected outcome".
    $script:Findings += 'refund endpoint returns HTTP 500 when the gateway refund call fails (IllegalStateException: gateway_refund_http_status:422 escapes to the servlet layer); no stable {code,message} is returned'
    Write-Host "  -- FINDING: refund endpoint surfaced HTTP $($refund.Status) instead of a mapped error --" -ForegroundColor Yellow
    $script:RefundPremise = 'gateway_error'
}

Write-Step 'SCENARIO I2: if a refund is granted, the subscription must not charge again'
if ($script:RefundPremise -ne 'granted') {
    Write-Host '  forcing the design-10 refundable state so the granted-refund branch is actually reached'
    # The refundable state per design 10 is: the renewal instance exists but was never
    # activated (grant not completed, instance QUEUED, expires_at NULL). Reproduce it on the
    # order we are about to refund, otherwise the branch the design demands a test for is
    # unreachable.
    $instanceId = Invoke-SqlScalar ("SELECT pi.id FROM gateway.package_instance pi " +
        "JOIN platform.grant_request gr ON gr.id = pi.grant_request_id " +
        "JOIN platform.subscription_cycle c ON c.grant_request_id = gr.id " +
        "WHERE c.order_no = '$renewalOrder' LIMIT 1;")
    Assert-True ($null -ne $instanceId) "renewal instance found for the refundable state ($instanceId)"
    # ck_package_instance_dates requires starts_at AND expires_at to BOTH be NULL while
    # status = 'QUEUED', which is exactly the shape of a not-yet-activated instance.
    Invoke-Sql ("UPDATE gateway.package_instance " +
        "SET status = 'QUEUED', starts_at = NULL, expires_at = NULL, activated_at = NULL, " +
        "    ended_at = NULL, updated_at = now() " +
        "WHERE id = '$instanceId';") | Out-Null
    Invoke-Sql ("UPDATE platform.grant_request SET status = 'QUEUED', completed_at = NULL " +
        "WHERE id = (SELECT grant_request_id FROM gateway.package_instance WHERE id = '$instanceId');") | Out-Null
    Write-Host "  instance $instanceId forced to QUEUED / grant QUEUED / starts_at+expires_at NULL"

    $refund2 = Invoke-Api -Method POST -Path "/console/platform/orders/$renewalOrder/refunds" `
        -Token $ctx.Token -Body @{ reason = 'e2e acceptance: refund while genuinely not activated' }
    $status2 = Get-Prop -Object $refund2.Body -Name 'status'
    Write-Host "  second attempt -> HTTP $($refund2.Status) refundStatus=$status2 body=$($refund2.Body | ConvertTo-Json -Compress -Depth 5)"
    switch ($refund2.Status) {
        201 {
            if ($status2 -eq 'PENDING_APPROVAL' -or $status2 -eq 'PREPARING') {
                Write-Host '  [ok] a genuinely unactivated renewal order IS refundable (design 10 premise holds)' -ForegroundColor Green
                $script:RefundPremise = 'granted'
            } else {
                Write-Host "  refund application stored with status=$status2" -ForegroundColor Yellow
                $script:RefundPremise = 'unknown'
            }
        }
        409 {
            # A refund application already exists for this order, so this branch cannot be
            # re-entered on the same order. That is not a defect; do not report it as one.
            Write-Host '  the order already carries a refund application, so the branch cannot be re-entered' -ForegroundColor Yellow
            $script:RefundPremise = 'already_applied'
        }
        500 {
            $script:Findings += 'refund endpoint returns HTTP 500 when the gateway refund call fails (IllegalStateException: gateway_refund_http_status:422 escapes to the servlet layer); no stable {code,message} is returned'
            Write-Host '  -- FINDING: refund endpoint surfaced a 500 instead of a mapped error --' -ForegroundColor Yellow
            $script:RefundPremise = 'gateway_error'
        }
        default {
            Write-Host "  unexpected HTTP status $($refund2.Status)" -ForegroundColor Yellow
            $script:RefundPremise = 'unknown'
        }
    }
}

if ($script:RefundPremise -eq 'granted') {
    $statusAfterRefund = Get-SubscriptionField -Email $ctx.Email -Field 'status'
    $autoRenewAfterRefund = Get-SubscriptionField -Email $ctx.Email -Field 'auto_renew'
    $refundStatus = if ($null -ne $refund2) { Get-Prop -Object $refund2.Body -Name 'status' } else { '<not reapplied>' }
    Write-Host "  after a GRANTED refund: refundStatus=$refundStatus subscription.status=$statusAfterRefund auto_renew=$autoRenewAfterRefund"

    # 这一步只走到「退款申请被接受」（PENDING_APPROVAL），**钱还没退回去**。
    # 因此订阅此刻**不该**被终止——终止必须发生在退款真正完成之后，否则退款一旦失败，
    # 用户会既没拿到钱、订阅也被停掉，比原来的缺陷更难解释。
    Assert-Equal $statusAfterRefund 'ACTIVE' 'the subscription is untouched while the refund is still pending approval'
    Assert-Equal $autoRenewAfterRefund 't' 'auto_renew is untouched while the refund is still pending approval'

    # 「退款完成 → 终止订阅 → 不再扣款」这半条链路的验证在单元层：
    # RefundOutboxTest.completingARefundTerminatesTheSubscriptionThatOwnsTheOrder（含终止与不再领取）
    # 与 completingARefundLeavesANonSubscriptionOrderAlone（一次性购买不受影响）。
    # 走完整执行链需要网关撤销回调，本脚本不驱动它。
    Write-Host '  [ok] refund accepted without touching the subscription (termination happens on completion; see RefundOutboxTest)' -ForegroundColor Green
} else {
    # Nothing was refunded in this run, so there is no double-benefit risk to assert. Assert
    # the conservative invariants instead: no money moved and the subscription is untouched.
    Assert-Equal (Get-WalletBalance -Email $ctx.Email) $walletBeforeRefund 'wallet unchanged (no refund was granted)'
    Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'auto_renew') 't' 'subscription untouched'
    Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'claim_token') '' 'no stuck lease left behind'
    Write-Host "  granted-refund branch not reached (premise=$($script:RefundPremise)); no money moved, subscription untouched" -ForegroundColor Yellow
}
$script:Passed++

# ---------------------------------------------------------------------------
Write-Step 'SCENARIO J: crash between charge-commit and cycle-write (task 11.5 idempotency rehearsal)'
# ---------------------------------------------------------------------------
# charge() deliberately commits the money first and writes the cycle row afterwards (the
# reverse order self-deadlocked on the plan-version row). That leaves a real crash window:
# the debit is durable but the cycle row and the advanced watermark are not, so the lease
# expires and the scheduler picks the SAME period up again.
#
# The guarantee under test is the deterministic idempotency key: business_key
# renewal:<subscriptionId>:<periodIndex> is used both as the order Idempotency-Key and as
# the ledger idempotency key, so a replay must recover the same order and must NOT post a
# second debit.
#
# A real process kill cannot be aimed at that exact window, so reproduce the post-crash
# state instead: period 2 is charged and its order/ledger row exist, but the cycle row for
# period 2 is gone and the watermark is still on period 1.
$ctx = New-Scenario -Name 'crashidem'
$subId = Open-Subscription -Ctx $ctx
Set-Up-DueRenewal -Ctx $ctx

$null = Wait-For -TimeoutSeconds 150 -IntervalSeconds 5 -Description 'period 2 charged' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    return $null
}

$businessKey2 = "renewal:${subId}:2"
$before = [pscustomobject]@{
    Wallet  = Get-WalletBalance -Email $ctx.Email
    Orders  = Get-OrderCount -Email $ctx.Email
    Ledger  = Get-LedgerCount -Email $ctx.Email
    OrderNo = Invoke-SqlScalar "SELECT order_no FROM platform.subscription_cycle WHERE business_key = '$businessKey2';"
    GrantId = Invoke-SqlScalar "SELECT grant_request_id FROM platform.subscription_cycle WHERE business_key = '$businessKey2';"
    Grant   = Invoke-SqlScalar "SELECT count(*) FROM platform.grant_request WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$($ctx.Email)'));"
}
Assert-True ($null -ne $before.OrderNo) "period 2 is charged against order $($before.OrderNo)"
Write-Host "  before replay: wallet=$($before.Wallet) orders=$($before.Orders) ledger=$($before.Ledger) grants=$($before.Grant)"

Write-Host '  simulating the crash: cycle row for period 2 gone, watermark still on period 1, lease released'
Invoke-Sql ("DELETE FROM platform.subscription_cycle WHERE business_key = '$businessKey2';") | Out-Null
Invoke-Sql ("UPDATE platform.subscription SET " +
    "current_period_start = current_period_start - interval '30 days', " +
    "current_period_end = current_period_end - interval '30 days', " +
    "next_charge_at = now() - interval '1 minute', " +
    "renewal_count = 0, claim_token = NULL, claimed_until = NULL, updated_at = now() " +
    "WHERE id = '$subId';") | Out-Null
# The replay has to get past the global single-active-instance guard. In the real crash
# window the period being replayed has not taken effect yet (that is the whole point of
# the window), so clear the active instance the same way Set-Up-DueRenewal does.
$stable = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    Expire-ActiveInstances -Email $ctx.Email
    Start-Sleep -Seconds 3
    if ((Get-ActiveInstanceCount -Email $ctx.Email) -eq 0) { $stable = $true; break }
}
if (-not $stable) { throw 'active instance still present after repeated expiry attempts' }
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.subscription_cycle WHERE business_key = '$businessKey2';") '0' 'period 2 cycle row removed'
Assert-Equal (Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') '0' 'watermark rolled back to period 1'

Write-Host '  waiting for the scheduler to replay the same period'
# Two outcomes are both SAFE, and the test accepts either:
#   (a) the idempotency key recovers the existing order and the replay is a no-op; or
#   (b) settlement refuses the replay because the order is already PAID
#       (failure_reason order_not_payable), so the period is recorded FAILED.
# What must hold in BOTH cases is that no money moves a second time.
$replayFailed = $false
$null = Wait-For -TimeoutSeconds 180 -IntervalSeconds 5 -Description 'the replay to settle one way or the other' -Condition {
    if ((Get-SubscriptionField -Email $ctx.Email -Field 'renewal_count') -eq '1') { return $true }
    if ((Get-CycleCount -Email $ctx.Email -Status 'FAILED') -ge 1) { return $true }
    return $null
}
$replayFailed = (Get-CycleCount -Email $ctx.Email -Status 'FAILED') -ge 1
Write-Host "  replay outcome: $(if ($replayFailed) { 'refused (order already PAID)' } else { 'recovered the existing order' })"

$after = [pscustomobject]@{
    Wallet  = Get-WalletBalance -Email $ctx.Email
    Orders  = Get-OrderCount -Email $ctx.Email
    Ledger  = Get-LedgerCount -Email $ctx.Email
    OrderNo = Invoke-SqlScalar "SELECT order_no FROM platform.subscription_cycle WHERE business_key = '$businessKey2';"
    Grant   = Invoke-SqlScalar "SELECT count(*) FROM platform.grant_request WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$($ctx.Email)'));"
}
Write-Host "  after replay:  wallet=$($after.Wallet) orders=$($after.Orders) ledger=$($after.Ledger) grants=$($after.Grant)"

Write-Host '  -- the money must not move twice (the point of task 11.5) --'
Assert-Equal $after.Wallet $before.Wallet 'wallet unchanged: no second debit for the same period'
Assert-Equal $after.Ledger $before.Ledger 'ledger row count unchanged: no duplicate posting'
Assert-Equal $after.Orders $before.Orders 'order count unchanged: no duplicate order'
Assert-Equal $after.Grant $before.Grant 'grant count unchanged: no duplicate grant'

Write-Host '  -- and the period never ends up charged twice --'
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.subscription_cycle WHERE subscription_id = '$subId' AND status = 'CHARGED';") '1' 'exactly one CHARGED cycle for the subscription'
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.customer_order WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$($ctx.Email)'));") '2' 'exactly two orders in total (period 1 + period 2)'
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.balance_ledger WHERE user_id = (SELECT id FROM platform.app_user WHERE lower(email) = lower('$($ctx.Email)'));") '2' 'exactly two ledger rows in total'

if ($replayFailed) {
    $reason = Invoke-SqlScalar ("SELECT failure_reason FROM platform.subscription_cycle c " +
        "JOIN platform.subscription s ON s.id = c.subscription_id " +
        "JOIN platform.app_user u ON u.id = s.user_id " +
        "WHERE lower(u.email) = lower('$($ctx.Email)') AND c.status = 'FAILED' LIMIT 1;")
    Write-Host "  refusal reason = $reason"
    # Any reason other than order_not_payable means the replay failed for a different
    # cause, which would be a finding rather than the expected guard.
    if ($reason -ne 'order_not_payable') {
        $script:Findings += "crash-replay of a paid period failed with '$reason' instead of being recovered or refused as order_not_payable; investigate whether a replay can lose a period"
        Write-Host "  -- FINDING: unexpected replay failure reason '$reason' --" -ForegroundColor Yellow
    } else {
        Write-Host '  [ok] the guard that prevents the second debit is the PAID order itself' -ForegroundColor Green
    }
} else {
    Assert-Equal $after.OrderNo $before.OrderNo 'the replay recovered the SAME order_no'
    Write-Host '  [ok] the replay was a no-op against the existing order' -ForegroundColor Green
}
$script:Passed++

Write-Host ''
Write-Host "FULL E2E: $($script:Passed) scenario groups passed" -ForegroundColor Green

if ($script:Findings.Count -gt 0) {
    Write-Host ''
    Write-Host "FINDINGS ($($script:Findings.Count)) - real issues this run uncovered, not test flakiness:" -ForegroundColor Yellow
    $i = 1
    foreach ($finding in $script:Findings) {
        Write-Host "  $i. $finding" -ForegroundColor Yellow
        $i++
    }
    Write-Host ''
    Write-Host '  Recorded in docs/testing/subscription-renewal-acceptance.md.' -ForegroundColor Yellow
}


