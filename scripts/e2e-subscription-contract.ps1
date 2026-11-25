# Read-only contract walk of the user-facing subscription endpoints.
#
# The E2E suite drives charge / cancel / resume and asserts database state. What it does not do is
# walk the *response bodies* a user actually receives and check them against what
# docs/接口文档.md 6.5 promises. This script does only that: it opens one subscription and then
# reads, printing every field it checks.
#
# Style note: all message strings below are plain ASCII with no parentheses. PowerShell parses a
# bare word followed by "(" inside a string argument as a command, which turns a harmless label
# into a CommandNotFoundException.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

$script:Failures = 0
function Check {
    param([string]$What, $Actual, $Expected)
    $ok = "$Actual" -eq "$Expected"
    if (-not $ok) { $script:Failures++ }
    Write-Host "  $(if ($ok) { '[ok]' } else { '[FAIL]' }) $What = $Actual$(if (-not $ok) { "  expected $Expected" })"
}
function CheckTrue {
    param([string]$What, [bool]$Condition, [string]$Note = '')
    if (-not $Condition) { $script:Failures++ }
    Write-Host "  $(if ($Condition) { '[ok]' } else { '[FAIL]' }) $What$(if ($Note) { "  $Note" })"
}

$email = "sub-e2e-readonly-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
New-SubscriptionTestUser -Email $email | Out-Null
Set-WalletBalance -Email $email -Cents 100000 | Out-Null
$token = Connect-DemoUser -Email $email

Write-Step '1. GET /console/platform/plans - the catalog advertises a subscribable plan'
$plans = Invoke-Api -Method GET -Path '/console/platform/plans' -Token $token
Check 'plans status' $plans.Status 200
# The endpoint returns a bare JSON array of published plans. Use .Where rather than
# Where-Object: under Set-StrictMode a single surviving element is unwrapped and has no .Count.
$starter = @($plans.Body).Where({ (Get-Prop -Object $_ -Name 'code') -eq 'STARTER' })
CheckTrue 'STARTER appears exactly once in the catalog' ($starter.Count -eq 1) "count=$($starter.Count)"
$starterPlan = $starter[0]
$versionId = Get-PublishedVersionId -PlanCode 'STARTER'
Check 'catalog versionId equals the PUBLISHED version the subscription binds to' `
    (Get-Prop -Object $starterPlan -Name 'versionId') $versionId
Check 'usageDimension, the column the renewal guard now scopes by' `
    (Get-Prop -Object $starterPlan -Name 'usageDimension') 'TOKEN'
Check 'durationDays, which drives the period length' `
    (Get-Prop -Object $starterPlan -Name 'durationDays') '30'
Check 'priceAmount in fen, never a float' (Get-Prop -Object $starterPlan -Name 'priceAmount') '2900'

Write-Step '2. POST /console/platform/subscriptions - first period with an Idempotency-Key'
$open = Invoke-Api -Method POST -Path '/console/platform/subscriptions' -Token $token `
    -IdempotencyKey ([guid]::NewGuid().ToString()) -Body @{ packageVersionId = $versionId }
Check 'open status' $open.Status 201
$view = Get-Prop -Object $open.Body -Name 'subscription'
if (-not $view) { $view = $open.Body }
Check 'status' (Get-Prop -Object $view -Name 'status') 'ACTIVE'
Check 'autoRenew' (Get-Prop -Object $view -Name 'autoRenew') 'True'
Check 'cancelAtPeriodEnd' (Get-Prop -Object $view -Name 'cancelAtPeriodEnd') 'False'
Check 'renewalCount - the first period is not a renewal' (Get-Prop -Object $view -Name 'renewalCount') '0'
Check 'consecutiveFailures' (Get-Prop -Object $view -Name 'consecutiveFailures') '0'
Check 'lastSkipReason is empty on a healthy subscription' (Get-Prop -Object $view -Name 'lastSkipReason') ''
Check 'lastChargedAmount in fen, so 2900 means 29.00 CNY' `
    (Get-Prop -Object $view -Name 'lastChargedAmount') '2900'
$subId = Get-SubscriptionId -Payload $open.Body
CheckTrue 'a subscription id came back' ($null -ne $subId) "$subId"

Write-Step '3. the documented charge lead time and period length'
$periodStart = [datetime]::Parse((Get-Prop -Object $view -Name 'currentPeriodStart'))
$periodEnd = [datetime]::Parse((Get-Prop -Object $view -Name 'currentPeriodEnd'))
$chargeAt = [datetime]::Parse((Get-Prop -Object $view -Name 'nextChargeAt'))
$leadHours = [math]::Round(($periodEnd - $chargeAt).TotalHours, 2)
Check 'nextChargeAt sits CHARGE_LEAD_TIME before currentPeriodEnd' $leadHours 24
CheckTrue 'currentPeriodEnd is about 30 days after currentPeriodStart' `
    ([math]::Abs(($periodEnd - $periodStart).TotalDays - 30) -lt 0.1) `
    "days=$([math]::Round(($periodEnd - $periodStart).TotalDays, 2))"
CheckTrue 'nextChargeAt is still in the future' ($chargeAt -gt [datetime]::UtcNow) "$chargeAt"

Write-Step '4. GET /console/platform/subscriptions - the list projection'
$list = Invoke-Api -Method GET -Path '/console/platform/subscriptions' -Token $token
Check 'list status' $list.Status 200
$mine = @($list.Body).Where({ (Get-Prop -Object $_ -Name 'id') -eq $subId })
CheckTrue 'the new subscription appears in the list' ($mine.Count -eq 1) "matches=$($mine.Count)"
# Deliberately NOT asserted here: usageDimension. The subscription projection carries no quota
# fields at all, so the "counter unit follows usageDimension" caveat in the API doc has nothing
# to attach to. The guard reads that column server-side.

Write-Step '5. GET subscription detail - the cycle ledger'
$detail = Invoke-Api -Method GET -Path "/console/platform/subscriptions/$subId" -Token $token
Check 'detail status' $detail.Status 200
$cycles = @(Get-Prop -Object $detail.Body -Name 'cycles')
CheckTrue 'at least one cycle row is returned' ($cycles.Count -ge 1) "cycles=$($cycles.Count)"
$first = @($cycles).Where({ [int](Get-Prop -Object $_ -Name 'periodIndex') -eq 1 })[0]
Check 'periodIndex starts at 1, as ck_subscription_cycle_index requires' `
    (Get-Prop -Object $first -Name 'periodIndex') '1'
Check 'first cycle status' (Get-Prop -Object $first -Name 'status') 'CHARGED'
Check 'first cycle amount in fen' (Get-Prop -Object $first -Name 'amount') '2900'
Check 'first cycle currency' (Get-Prop -Object $first -Name 'currency') 'CNY'
CheckTrue 'first cycle carries an orderNo, so the charge really produced an order' `
    ($null -ne (Get-Prop -Object $first -Name 'orderNo')) "orderNo=$(Get-Prop -Object $first -Name 'orderNo')"

Write-Step '6. cancel then resume round trip - design 7.5 semantics'
$cancel = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/cancel" -Token $token `
    -IdempotencyKey ([guid]::NewGuid().ToString()) -Body @{}
Check 'cancel status' $cancel.Status 200
$c = Get-Prop -Object $cancel.Body -Name 'subscription'; if (-not $c) { $c = $cancel.Body }
Check 'after cancel autoRenew is off' (Get-Prop -Object $c -Name 'autoRenew') 'False'
Check 'after cancel cancelAtPeriodEnd is set' (Get-Prop -Object $c -Name 'cancelAtPeriodEnd') 'True'
Check 'after cancel status stays ACTIVE, so the paid period is used up and nothing is refunded' `
    (Get-Prop -Object $c -Name 'status') 'ACTIVE'

$resume = Invoke-Api -Method POST -Path "/console/platform/subscriptions/$subId/resume" -Token $token `
    -IdempotencyKey ([guid]::NewGuid().ToString()) -Body @{}
Check 'resume status' $resume.Status 200
$r = Get-Prop -Object $resume.Body -Name 'subscription'; if (-not $r) { $r = $resume.Body }
Check 'after resume autoRenew is back on' (Get-Prop -Object $r -Name 'autoRenew') 'True'
Check 'after resume cancelAtPeriodEnd is cleared' (Get-Prop -Object $r -Name 'cancelAtPeriodEnd') 'False'

Write-Step '7. error contract - Idempotency-Key is mandatory and unknown ids are 404'
# Invoke-Api -Raw rethrows, which is how a 4xx is observed here.
$noKeyStatus = 0
try {
    $null = Invoke-Api -Method POST -Path '/console/platform/subscriptions' -Token $token `
        -Body @{ packageVersionId = $versionId } -Raw
    $noKeyStatus = 200
} catch {
    if ($_.Exception.Response) { $noKeyStatus = [int]$_.Exception.Response.StatusCode.value__ }
}
CheckTrue 'a missing Idempotency-Key is refused with a 4xx rather than a 500' `
    ($noKeyStatus -ge 400 -and $noKeyStatus -lt 500) "status=$noKeyStatus"

$missing = Invoke-Api -Method GET -Path "/console/platform/subscriptions/$([guid]::NewGuid())" -Token $token
Check 'an unknown subscription id reads as 404' $missing.Status 404

$missingCancel = Invoke-Api -Method POST `
    -Path "/console/platform/subscriptions/$([guid]::NewGuid())/cancel" -Token $token `
    -IdempotencyKey ([guid]::NewGuid().ToString()) -Body @{}
Check 'cancelling an unknown subscription is 404' $missingCancel.Status 404

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host 'READ-ONLY CONTRACT WALK: all checks passed' -ForegroundColor Green
    exit 0
}
Write-Host "READ-ONLY CONTRACT WALK: $($script:Failures) check(s) FAILED" -ForegroundColor Red
exit 1
