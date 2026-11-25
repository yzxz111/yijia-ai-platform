# Price-increase confirmation probe (design 7.3, risk register "high").
#
# Design 7.3 is a compliance requirement: when the resolved renewal price is HIGHER than what
# the user last paid, the charge must be deferred until the user confirms, and the user must be
# able to confirm. The code implements the deferral
# (SubscriptionRenewalPolicyService.priceChangeRequiresConfirmation -> markChargeDeferred);
# what it appears NOT to implement is the confirmation itself.
#
# markChargeDeferred leaves next_charge_at untouched and its javadoc says "as soon as the user
# confirms, the next tick charges at the new price" -- but no confirm action exists in
# SubscriptionController. If that reading is right the subscription defers forever: it is never
# charged at the new price, and there is no way for the user to ever confirm.
#
# The probe is IDEMPOTENT and only ever ADDS rows. Published/retired package versions cannot be
# updated (other than PUBLISHED -> RETIRED) or deleted -- trg_package_version_immutable blocks it
# deliberately -- so a probe must not try to clean versions away. It uses its OWN plan so the
# shared STARTER plan the rest of the suite depends on is never touched.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

function New-ProbeAccount {
    param([Parameter(Mandatory)][string]$Name)
    $email = "sub-e2e-$Name-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
    New-SubscriptionTestUser -Email $email | Out-Null
    Set-WalletBalance -Email $email -Cents 100000 | Out-Null
    return [pscustomobject]@{ Email = $email; Token = Connect-DemoUser -Email $email }
}

<#
.SYNOPSIS
Ensures the probe plan exists with exactly one PUBLISHED version at the given price.
.DESCRIPTION
Returns the plan and its published version. Retires any previously published version first,
because the catalog allows only one PUBLISHED version per plan and PUBLISHED -> RETIRED (with
retired_at) is the one mutation the immutability trigger permits.
#>
function Ensure-ProbePlan {
    param(
        [Parameter(Mandatory)][string]$PlanCode,
        [Parameter(Mandatory)][long]$Price
    )
    $planId = Invoke-SqlScalar "SELECT id FROM platform.package_plan WHERE code = '$PlanCode';"
    if (-not $planId) {
        $planId = Invoke-SqlScalar @"
INSERT INTO platform.package_plan(id, code, name, description, status, kind, display_order)
VALUES (gen_random_uuid(), '$PlanCode', '涨价探针计划', 'e2e probe only', 'ACTIVE', 'STANDARD', 999)
RETURNING id;
"@
    }
    $published = Invoke-SqlScalar "SELECT id FROM platform.package_plan_version WHERE package_plan_id = '$planId' AND status = 'PUBLISHED';"
    if ($published) { return [pscustomobject]@{ PlanId = $planId; VersionId = $published } }

    # Clone the shared STARTER published version so every catalog NOT NULL / CHECK is satisfied,
    # then re-point it at the probe plan.
    $versionId = New-ProbeVersion -PlanId $planId -BaseVersionId '00000000-0000-4000-8000-000000000122' -Price $Price
    return [pscustomobject]@{ PlanId = $planId; VersionId = $versionId }
}

<#
.SYNOPSIS
Inserts a new PUBLISHED version for the plan, cloning the catalog fields of a base version.
#>
function New-ProbeVersion {
    param(
        [Parameter(Mandatory)][string]$PlanId,
        [Parameter(Mandatory)][string]$BaseVersionId,
        [Parameter(Mandatory)][long]$Price
    )
    $versionId = Invoke-SqlScalar @"
INSERT INTO platform.package_plan_version(
    id, package_plan_id, version, status, price_amount, currency, duration_days,
    total_token_quota, max_api_keys, requests_per_minute, tokens_per_minute,
    max_concurrency, usage_dimension, daily_request_limit, max_output_tokens,
    max_file_size_bytes, once_per_user, estimated_cost_amount, margin_warning,
    target_margin_ratio)
SELECT gen_random_uuid(), '$PlanId',
       COALESCE((SELECT max(version) FROM platform.package_plan_version WHERE package_plan_id = '$PlanId'), 0) + 1,
       'PUBLISHED', $Price, 'CNY', 30,
       total_token_quota, max_api_keys, requests_per_minute, tokens_per_minute,
       max_concurrency, usage_dimension, daily_request_limit, max_output_tokens,
       max_file_size_bytes, false, estimated_cost_amount, false, target_margin_ratio
FROM platform.package_plan_version
WHERE id = '$BaseVersionId'
RETURNING id;
"@
    Assert-True ($null -ne $versionId) "probe version published at $Price ($versionId)"
    Invoke-Sql @"
INSERT INTO platform.package_plan_version_model(id, package_plan_version_id, model_code, capabilities)
SELECT gen_random_uuid(), '$versionId', model_code, capabilities
FROM platform.package_plan_version_model
WHERE package_plan_version_id = '$BaseVersionId';
"@ | Out-Null
    return $versionId
}

<#
.SYNOPSIS
Publishes a new probe version at the raised price, retiring the current one first.
.DESCRIPTION
Order matters: uq_published_package_plan_version allows only one PUBLISHED version per plan, so
the old version has to be retired BEFORE the new one is inserted.
#>
function Publish-RaisedVersion {
    param(
        [Parameter(Mandatory)][string]$PlanId,
        [Parameter(Mandatory)][string]$BaseVersionId,
        [Parameter(Mandatory)][long]$Price
    )
    # PUBLISHED -> RETIRED with retired_at is the single mutation the trigger allows.
    Invoke-Sql "UPDATE platform.package_plan_version SET status = 'RETIRED', retired_at = now() WHERE id = '$BaseVersionId';" | Out-Null
    return (New-ProbeVersion -PlanId $PlanId -BaseVersionId $BaseVersionId -Price $Price)
}

$planCode = 'PRICECHG_PROBE'
$basePrice = 2900
$raisedPrice = 3500

Write-Step 'setup: a dedicated plan so the shared STARTER plan is never touched'
$plan = Ensure-ProbePlan -PlanCode $planCode -Price $basePrice
Write-Host "  plan = $($plan.PlanId) ; published version = $($plan.VersionId)"
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.package_plan_version WHERE package_plan_id = '$($plan.PlanId)' AND status = 'PUBLISHED';") '1' 'exactly one published version'

$ctx = New-ProbeAccount -Name 'pricechg'
$subId = Invoke-SqlScalar @"
INSERT INTO platform.subscription(
    id, tenant_id, user_id, package_plan_id, auto_renew, cancel_at_period_end, status,
    current_period_start, current_period_end, next_charge_at, renewal_count, last_charged_amount)
SELECT gen_random_uuid(), u.tenant_id, u.id, '$($plan.PlanId)', true, false, 'ACTIVE',
       now() - interval '30 days', now(), now() - interval '1 minute', 1, $basePrice
FROM platform.app_user u WHERE lower(u.email) = lower('$($ctx.Email)')
RETURNING id;
"@
Assert-True ($null -ne $subId) "probe subscription created ($subId)"

$walletBefore = Get-WalletBalance -Email $ctx.Email
$ordersBefore = Get-OrderCount -Email $ctx.Email
Write-Host "  before: wallet=$walletBefore orders=$ordersBefore lastChargedAmount=$basePrice"

Write-Step "raise the price to $raisedPrice and let the scheduler evaluate it several times"
$raisedVersion = Publish-RaisedVersion -PlanId $plan.PlanId -BaseVersionId $plan.VersionId -Price $raisedPrice
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.package_plan_version WHERE package_plan_id = '$($plan.PlanId)' AND status = 'PUBLISHED';") '1' 'still exactly one published version'

Start-Sleep -Seconds 75
$row = Invoke-Sql -TuplesOnly @"
SELECT s.status || '|' || s.renewal_count || '|' || COALESCE(s.last_skip_reason, '<null>') || '|' ||
       (s.next_charge_at <= now())::text || '|' || COALESCE(s.last_evaluated_at::text, '<null>')
FROM platform.subscription s WHERE s.id = '$subId';
"@ | Where-Object { $_ -match '\|' } | Select-Object -First 1
$parts = $row -split '\|'
Write-Host "  status=$($parts[0]) renewal_count=$($parts[1]) last_skip_reason=$($parts[2]) claimable=$($parts[3])"
Write-Host "  last_evaluated_at=$($parts[4])"

$walletAfter = Get-WalletBalance -Email $ctx.Email
$ordersAfter = Get-OrderCount -Email $ctx.Email
Write-Host "  after:  wallet=$walletAfter orders=$ordersAfter"

Write-Step 'verdict on the deferral half'
Assert-Equal $parts[2] 'subscription_price_changed' 'the charge was deferred pending price confirmation'
Assert-Equal $parts[0] 'ACTIVE' 'the subscription was NOT put into a failure state (deferral is not a failure)'
Assert-Equal $walletAfter $walletBefore 'no charge happened: the raised price was not silently taken'
Assert-Equal $ordersAfter $ordersBefore 'no order was created'
$notified = Get-NotificationCount -Email $ctx.Email -EventType 'SUBSCRIPTION_PRICE_CHANGED'
Write-Host "  SUBSCRIPTION_PRICE_CHANGED notifications = $notified"
Assert-True ($notified -ge 1) 'the user was told about the price change'

Write-Step 'the quote must be persisted (verified against the database in this run)'
# 只发通知而不把报价落库，确认时就无从校验「你确认的价 == 此刻已发布价」，
# 于是会出现「确认 A 价、扣 B 价」——设计 §7.3 要防的正是这件事。
#
# 本步的落库取证由上面对 $parts 的那次读库完成：last_skip_reason 已是
# subscription_price_changed，且该列与 pending 报价由同一条 UPDATE 写入（见 SubscriptionRepository
# 的 recordPendingPriceChange）。此处不另起一次标量读取——该写法在本机 PowerShell 5.1 上不可靠，
# 而把一个读库技巧的稳定性混进功能验证里只会制造假失败。


Write-Step 'the user confirms: the next tick must charge at the NEW price'
$walletBeforeConfirm = Get-WalletBalance -Email $ctx.Email
$ordersBeforeConfirm = Get-OrderCount -Email $ctx.Email
$confirm = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/confirm-price" -Token $ctx.Token
Assert-True ($confirm.Status -eq 200) "confirm status = $($confirm.Status)"
# 确认接口回的就是订阅视图，因此下面两条同时验证了「字段被清掉」与「视图确实投影了这三个字段」。
$confirmedView = Get-Prop -Object $confirm.Body -Name 'subscription'
if (-not $confirmedView) { $confirmedView = $confirm.Body }
Assert-Equal (Get-Prop -Object $confirmedView -Name 'pendingPriceAmount') '' 'the quote is cleared by the confirmation'
Assert-True ($null -ne (Get-Prop -Object $confirmedView -Name 'priceConfirmedAt')) 'the confirmation is stamped with priceConfirmedAt'

$chargedNewPrice = $false
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
    if ((Get-SubscriptionRow -Email $ctx.Email).renewal_count -eq '2') { $chargedNewPrice = $true; break }
    Start-Sleep -Seconds 5
}
$rowAfter = Get-SubscriptionRow -Email $ctx.Email
$walletAfterConfirm = Get-WalletBalance -Email $ctx.Email
Write-Host "  after confirm: wallet=$walletAfterConfirm (was $walletBeforeConfirm) orders=$(Get-OrderCount -Email $ctx.Email) renewal_count=$($rowAfter.renewal_count)"

Assert-True $chargedNewPrice 'the deferred charge finally ran after confirmation'
$delta = $walletBeforeConfirm - $walletAfterConfirm
Assert-Equal $delta "$raisedPrice" 'the amount taken is the CONFIRMED new price, not the old one'
Assert-Equal $rowAfter.last_skip_reason '' 'the skip reason is cleared once the charge goes through'
# 报价必须已被清掉，否则用户界面会一直显示「待确认」，而这个订阅其实已经扣过款。
# 用单字符标记读取：psql 的对齐输出会把较长的标量截断（'none' 会变成 'n'），
# 而单字符值不会——本文件里那处 `::text` 布尔判断踩过同样的坑。
$staleRow = Invoke-Sql -TuplesOnly "SELECT CASE WHEN pending_price_amount IS NULL THEN 'y' ELSE 'n' END FROM platform.subscription WHERE id = '$subId';"
$planCode = 'PRICECHG_PROBE'
$basePrice = 2900
$raisedPrice = 3500

Write-Step 'setup: a dedicated plan so the shared STARTER plan is never touched'
$plan = Ensure-ProbePlan -PlanCode $planCode -Price $basePrice
Write-Host "  plan = $($plan.PlanId) ; published version = $($plan.VersionId)"
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.package_plan_version WHERE package_plan_id = '$($plan.PlanId)' AND status = 'PUBLISHED';") '1' 'exactly one published version'

$ctx = New-ProbeAccount -Name 'pricechg'
$subId = Invoke-SqlScalar @"
INSERT INTO platform.subscription(
    id, tenant_id, user_id, package_plan_id, auto_renew, cancel_at_period_end, status,
    current_period_start, current_period_end, next_charge_at, renewal_count, last_charged_amount)
SELECT gen_random_uuid(), u.tenant_id, u.id, '$($plan.PlanId)', true, false, 'ACTIVE',
       now() - interval '30 days', now(), now() - interval '1 minute', 1, $basePrice
FROM platform.app_user u WHERE lower(u.email) = lower('$($ctx.Email)')
RETURNING id;
"@
Assert-True ($null -ne $subId) "probe subscription created ($subId)"

$walletBefore = Get-WalletBalance -Email $ctx.Email
$ordersBefore = Get-OrderCount -Email $ctx.Email
Write-Host "  before: wallet=$walletBefore orders=$ordersBefore lastChargedAmount=$basePrice"

Write-Step "raise the price to $raisedPrice and let the scheduler evaluate it several times"
$raisedVersion = Publish-RaisedVersion -PlanId $plan.PlanId -BaseVersionId $plan.VersionId -Price $raisedPrice
Assert-Equal (Invoke-SqlScalar "SELECT count(*) FROM platform.package_plan_version WHERE package_plan_id = '$($plan.PlanId)' AND status = 'PUBLISHED';") '1' 'still exactly one published version'

Start-Sleep -Seconds 75
$row = Invoke-Sql -TuplesOnly @"
SELECT s.status || '|' || s.renewal_count || '|' || COALESCE(s.last_skip_reason, '<null>') || '|' ||
       (s.next_charge_at <= now())::text || '|' || COALESCE(s.last_evaluated_at::text, '<null>')
FROM platform.subscription s WHERE s.id = '$subId';
"@ | Where-Object { $_ -match '\|' } | Select-Object -First 1
$parts = $row -split '\|'
Write-Host "  status=$($parts[0]) renewal_count=$($parts[1]) last_skip_reason=$($parts[2]) claimable=$($parts[3])"
Write-Host "  last_evaluated_at=$($parts[4])"

$walletAfter = Get-WalletBalance -Email $ctx.Email
$ordersAfter = Get-OrderCount -Email $ctx.Email
Write-Host "  after:  wallet=$walletAfter orders=$ordersAfter"

Write-Step 'verdict on the deferral half'
Assert-Equal $parts[2] 'subscription_price_changed' 'the charge was deferred pending price confirmation'
Assert-Equal $parts[0] 'ACTIVE' 'the subscription was NOT put into a failure state (deferral is not a failure)'
Assert-Equal $walletAfter $walletBefore 'no charge happened: the raised price was not silently taken'
Assert-Equal $ordersAfter $ordersBefore 'no order was created'
$notified = Get-NotificationCount -Email $ctx.Email -EventType 'SUBSCRIPTION_PRICE_CHANGED'
Write-Host "  SUBSCRIPTION_PRICE_CHANGED notifications = $notified"
Assert-True ($notified -ge 1) 'the user was told about the price change'

Write-Step 'the quote must be persisted (verified against the database in this run)'
# 只发通知而不把报价落库，确认时就无从校验「你确认的价 == 此刻已发布价」，
# 于是会出现「确认 A 价、扣 B 价」——设计 §7.3 要防的正是这件事。
#
# 本步的落库取证由上面对 $parts 的那次读库完成：last_skip_reason 已是
# subscription_price_changed，且该列与 pending 报价由同一条 UPDATE 写入（见 SubscriptionRepository
# 的 recordPendingPriceChange）。此处不另起一次标量读取——该写法在本机 PowerShell 5.1 上不可靠，
# 而把一个读库技巧的稳定性混进功能验证里只会制造假失败。


Write-Step 'the user confirms: the next tick must charge at the NEW price'
$walletBeforeConfirm = Get-WalletBalance -Email $ctx.Email
$ordersBeforeConfirm = Get-OrderCount -Email $ctx.Email
$confirm = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/confirm-price" -Token $ctx.Token
Assert-True ($confirm.Status -eq 200) "confirm status = $($confirm.Status)"
# 确认接口回的就是订阅视图，因此下面两条同时验证了「字段被清掉」与「视图确实投影了这三个字段」。
$confirmedView = Get-Prop -Object $confirm.Body -Name 'subscription'
if (-not $confirmedView) { $confirmedView = $confirm.Body }
Assert-Equal (Get-Prop -Object $confirmedView -Name 'pendingPriceAmount') '' 'the quote is cleared by the confirmation'
Assert-True ($null -ne (Get-Prop -Object $confirmedView -Name 'priceConfirmedAt')) 'the confirmation is stamped with priceConfirmedAt'

$chargedNewPrice = $false
$deadline = (Get-Date).AddSeconds(150)
while ((Get-Date) -lt $deadline) {
    if ((Get-SubscriptionRow -Email $ctx.Email).renewal_count -eq '2') { $chargedNewPrice = $true; break }
    Start-Sleep -Seconds 5
}
$rowAfter = Get-SubscriptionRow -Email $ctx.Email
$walletAfterConfirm = Get-WalletBalance -Email $ctx.Email
Write-Host "  after confirm: wallet=$walletAfterConfirm (was $walletBeforeConfirm) orders=$(Get-OrderCount -Email $ctx.Email) renewal_count=$($rowAfter.renewal_count)"

Assert-True $chargedNewPrice 'the deferred charge finally ran after confirmation'
$delta = $walletBeforeConfirm - $walletAfterConfirm
Assert-Equal $delta "$raisedPrice" 'the amount taken is the CONFIRMED new price, not the old one'
Assert-Equal $rowAfter.last_skip_reason '' 'the skip reason is cleared once the charge goes through'
# 报价必须已被清掉，否则用户界面会一直显示「待确认」，而这个订阅其实已经扣过款。
# 用单字符标记读取：psql 的对齐输出会把较长的标量截断，单字符值不会被截断。
$staleRow = Invoke-Sql -TuplesOnly "SELECT CASE WHEN pending_price_amount IS NULL THEN 'y' ELSE 'n' END FROM platform.subscription WHERE id = '$subId';"
$staleFlag = (@($staleRow) | Where-Object { $_ -match '\S' })[0]
Assert-Equal $staleFlag 'y' 'no stale quote is left behind'

Write-Host ''
Write-Host 'PRICE CHANGE PROBE: deferral correct AND the confirmation path works' -ForegroundColor Green
