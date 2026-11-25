# Verifies the deployed path a browser actually takes: user -> nginx:18081 -> platform.
#
# The subscription suites talk to the platform directly or through the driver's base URL; this
# checks the reverse-proxied route and the SPA router entries the sidebar links to.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-subscription-lib.ps1')

$failures = 0
function Ok { param([string]$What, [bool]$C, [string]$Note = '')
    if (-not $C) { $script:failures++ }
    Write-Host "  $(if ($C) { '[ok]' } else { '[FAIL]' }) $What$(if ($Note) { "  $Note" })" }

Write-Host "base url under test: $script:BaseUrl"

Write-Step 'authenticated read through nginx'
$email = "sub-e2e-nginx-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$([guid]::NewGuid().ToString('N').Substring(0,6))@yuzhi.test"
New-SubscriptionTestUser -Email $email | Out-Null
Set-WalletBalance -Email $email -Cents 100000 | Out-Null
$token = Connect-DemoUser -Email $email
Ok 'logged in through the proxied auth route' ($null -ne $token)

$list = Invoke-Api -Method GET -Path '/console/platform/subscriptions' -Token $token
Ok 'GET /console/platform/subscriptions via nginx' ($list.Status -eq 200) "status=$($list.Status)"
Ok 'the response is a JSON array' ($list.Body -is [System.Array]) "type=$($list.Body.GetType().Name)"

$plans = Invoke-Api -Method GET -Path '/console/platform/plans' -Token $token
Ok 'GET /console/platform/plans via nginx' ($plans.Status -eq 200) "status=$($plans.Status)"
$starter = @($plans.Body).Where({ (Get-Prop -Object $_ -Name 'code') -eq 'STARTER' })
Ok 'the subscriber-facing catalog still lists STARTER' ($starter.Count -eq 1) "count=$($starter.Count)"

Write-Step 'the routes the sidebar and router link to'
# Route records store relative segments and the parents are '/app' and '/admin', so assert both
# halves: checking only the segment would pass even if the mount point were wrong.
$routerSource = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) 'web-console/src/router/index.ts') -Raw
Ok "router mounts a user area at '/app'" ($routerSource -match "path:\s*'/app'")
Ok "router mounts an admin area at '/admin'" ($routerSource -match "path:\s*'/admin'")
Ok 'router declares the subscriptions segment under the user area' `
    ($routerSource -match "path:\s*'subscriptions',\s*name:\s*'subscriptions'")
Ok 'router declares the subscriptions segment under the admin area' `
    ($routerSource -match "path:\s*'subscriptions',\s*name:\s*'admin-subscriptions'")
$sidebar = Get-Content (Join-Path (Split-Path $PSScriptRoot -Parent) 'web-console/src/components/layout/SidebarNav.vue') -Raw
Ok 'the sidebar links to /app/subscriptions' ($sidebar -match [regex]::Escape("path: '/app/subscriptions'"))
Ok 'the admin sidebar links to /admin/subscriptions' ($sidebar -match [regex]::Escape("path: '/admin/subscriptions'"))

Write-Step 'SPA deep links served by nginx'
foreach ($path in @('/app/subscriptions', '/admin/subscriptions')) {
    $resp = Invoke-WebRequest -Uri "$script:BaseUrl$path" -Method GET -UseBasicParsing -TimeoutSec 20
    $shell = $resp.Content -match '<div id="app"'
    Ok "GET $path returns the SPA shell" ($resp.StatusCode -eq 200 -and $shell) "status=$($resp.StatusCode)"
}

Write-Step 'the shipped bundle carries the subscription API paths'
$distDir = Join-Path (Split-Path $PSScriptRoot -Parent) 'web-console/dist/assets'
$bundles = Get-ChildItem $distDir -Filter '*.js' -ErrorAction SilentlyContinue
$needles = @('/console/platform/subscriptions', 'app/subscriptions')
foreach ($needle in $needles) {
    $hit = @($bundles).Where({ (Get-Content $_.FullName -Raw) -match [regex]::Escape($needle) })
    Ok "bundle contains '$needle'" ($hit.Count -ge 1) "bundles=$($hit.Count)"
}

Write-Host ''
if ($failures -eq 0) {
    Write-Host 'PROXY AND ROUTE WALK: all checks passed' -ForegroundColor Green
    exit 0
}
Write-Host "PROXY AND ROUTE WALK: $failures check(s) FAILED" -ForegroundColor Red
exit 1
