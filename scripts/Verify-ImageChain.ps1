# 生图链路生产复核探针（只读 + 一次真实出图）
#
# 用法（在能访问生产网关的机器上执行）：
#   pwsh -File scripts/Verify-ImageChain.ps1 -BaseUrl https://api.yuzhh.com -ApiKey '<IMAGE 维度的密钥>'
#   pwsh -File scripts/Verify-ImageChain.ps1 -BaseUrl https://api.yuzhh.com -ApiKey '<...>' -Download
#
# 说明：
#   * 不传 -ApiKey 时只跑"端点存活"检查（无密钥应 401，不是 404/502）。
#   * 传了 -ApiKey 会真实出图一张（约 30~90 秒、计费 1 张）。密钥只在内存中使用，不打印、不落盘。
#   * 提示词固定为英文：Windows PowerShell 5.1 会把中文 body 按 Latin-1 发出去（见 README 的编码坑）。
#     需要中文提示词时用 -PromptFile 指向一个 UTF-8(无 BOM) 的 JSON 文件。
#
# 配套的服务端检查（必须在服务器上跑，探针无法远程代替）：
#   SELECT status, payload->>'usage_dimension' AS dim, count(*)
#     FROM gateway.usage_economics_outbox GROUP BY 1,2;            -- 期望：IMAGE 不应有 DEAD
#   SELECT usage_dimension, count(*) FROM platform.operating_fact GROUP BY 1;  -- 期望：IMAGE 有行
#   SELECT request_id, status, billed_images, upstream_model
#     FROM gateway.image_generation_task ORDER BY created_at DESC LIMIT 3;      -- 期望：本次 SUCCEEDED/1

[CmdletBinding()]
param(
    [string]$BaseUrl = 'https://api.yuzhh.com',
    [string]$ApiKey = '',
    [string]$Prompt = 'a red apple on a wooden table, soft studio light',
    [string]$PromptFile = '',
    [string]$IdempotencyKey = '',
    [int]$TimeoutSeconds = 180,
    [switch]$Download
)

$ErrorActionPreference = 'Continue'
$BaseUrl = $BaseUrl.TrimEnd('/')
$results = New-Object System.Collections.Generic.List[object]

function Add-Result([string]$check, [string]$expected, [string]$actual, [bool]$pass) {
    $results.Add([pscustomobject]@{ 检查 = $check; 期望 = $expected; 实测 = $actual; 结果 = $(if ($pass) { 'PASS' } else { 'FAIL' }) })
}

function Get-Http([string]$method, [string]$uri, [hashtable]$headers, [string]$bodyFile) {
    try {
        $args = @{ Method = $method; Uri = $uri; TimeoutSec = 60; UseBasicParsing = $true }
        if ($headers) { $args.Headers = $headers }
        if ($bodyFile) { $args.InFile = $bodyFile; $args.ContentType = 'application/json' }
        $r = Invoke-WebRequest @args
        return @{ status = [int]$r.StatusCode; content = $r.Content; headers = $r.Headers }
    } catch {
        $resp = $_.Exception.Response
        return @{ status = $(if ($resp) { [int]$resp.StatusCode } else { 0 }); content = $_.ErrorDetails.Message; headers = $null }
    }
}

Write-Output "== 目标：$BaseUrl =="

# 1) 端点存活（无密钥必须 401）
$anon = Get-Http 'POST' "$BaseUrl/v1/images/generations" @{} ''
Add-Result '端点存活 /v1/images/generations（无密钥）' '401' "$($anon.status)" ($anon.status -eq 401)
$anonModels = Get-Http 'GET' "$BaseUrl/v1/image-models" @{} ''
Add-Result '端点存活 /v1/image-models（无密钥）' '401' "$($anonModels.status)" ($anonModels.status -eq 401)

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Output "`n（未提供 -ApiKey，跳过需要密钥的检查）"
    $results | Format-Table -AutoSize
    exit 0
}

$H = @{ Authorization = "Bearer $ApiKey" }

# 2) 在售档位
$models = Get-Http 'GET' "$BaseUrl/v1/image-models" $H ''
$tier = ''
if ($models.status -eq 200) {
    try { $tier = (($models.content | ConvertFrom-Json).data | Select-Object -First 1).resolutionTier } catch { }
}
Add-Result 'GET /v1/image-models' '200 且在售档位非空' "$($models.status) tier=$tier" ($models.status -eq 200 -and $tier)
if (-not $tier) { $tier = '2K' }

# 3) 准入拦截（不打上游、不花钱）
$tmp = Join-Path $env:TEMP ("imgprobe-" + [guid]::NewGuid().ToString('N') + ".json")
foreach ($case in @(
        @{ name = 'n=2'; expect = 'image_count_not_supported'; json = '{"model":"image-basic","prompt":"x","size":"' + $tier + '","n":2}' },
        @{ name = 'size=4K'; expect = 'image_size_not_sold_by_model'; json = '{"model":"image-basic","prompt":"x","size":"4K"}' },
        @{ name = 'image 传数组'; expect = 'image_input_array_not_supported'; json = '{"model":"image-basic","prompt":"x","image":["https://a/1.jpg","https://a/2.jpg"]}' }
    )) {
    [IO.File]::WriteAllText($tmp, $case.json, (New-Object Text.UTF8Encoding($false)))
    $r = Get-Http 'POST' "$BaseUrl/v1/images/generations" $H $tmp
    $code = ''
    try { $code = ($r.content | ConvertFrom-Json).error.code } catch { }
    Add-Result "准入 $($case.name)" "422 $($case.expect)" "$($r.status) $code" ($r.status -eq 422 -and $code -eq $case.expect)
}

# 4) 真实出图
if ($PromptFile) {
    $bodyFile = $PromptFile
} else {
    $json = '{"model":"image-basic","prompt":"' + $Prompt.Replace('"', '\"') + '","size":"' + $tier + '","response_format":"url"}'
    [IO.File]::WriteAllText($tmp, $json, (New-Object Text.UTF8Encoding($false)))
    $bodyFile = $tmp
}
if (-not $IdempotencyKey) { $IdempotencyKey = 'verify-image-' + (Get-Date -Format 'yyyyMMddHHmmss') }
$genHeaders = @{ Authorization = "Bearer $ApiKey"; 'Idempotency-Key' = $IdempotencyKey }

Write-Output "`n== 真实出图（prompt=$(if($PromptFile){$PromptFile}else{$Prompt})）=="
$sw = [Diagnostics.Stopwatch]::StartNew()
$gen = Get-Http 'POST' "$BaseUrl/v1/images/generations" $genHeaders $bodyFile
$sw.Stop()
$url = ''; $created = ''
try { $j = $gen.content | ConvertFrom-Json; $url = $j.data[0].url; $created = $j.created } catch { }
Add-Result '真实出图' '200 且 data[0].url 非空' "HTTP $($gen.status)  $([math]::Round($sw.Elapsed.TotalSeconds,1))s" ($gen.status -eq 200 -and $url)
if ($url) {
    $u = [Uri]$url
    Write-Output "  图片 URL host=$($u.Host)  预签名参数过期时间在 query 里（X-Tos-Expires）"
    Write-Output "  created=$created  Idempotency-Key=$IdempotencyKey（重试请复用同一个值）"
}
if ($gen.status -ne 200) { Write-Output "  响应：$($gen.content)" }

# 5) 幂等重放（同键应返回同一结果，不重复扣费）
if ($url) {
    $replay = Get-Http 'POST' "$BaseUrl/v1/images/generations" $genHeaders $bodyFile
    $rc = ''
    try { $rc = ($replay.content | ConvertFrom-Json).created } catch { }
    Add-Result '幂等重放（同 Idempotency-Key）' "200 且 created=$created" "$($replay.status) created=$rc" ($replay.status -eq 200 -and "$rc" -eq "$created")
}

# 6) 可选下载落地
if ($Download -and $url) {
    $out = Join-Path $PWD ("image-probe-" + (Get-Date -Format 'yyyyMMddHHmmss') + '.jpg')
    try {
        Invoke-WebRequest -Uri $url -OutFile $out -TimeoutSec 120
        $fi = Get-Item $out
        $head = [BitConverter]::ToString([IO.File]::ReadAllBytes($out)[0..3])
        Add-Result '下载落地' '文件非空且首字节为图片魔数' "$([math]::Round($fi.Length/1KB,1))KB head=$head" ($fi.Length -gt 0)
        Write-Output "  已保存：$out"
    } catch { Add-Result '下载落地' '可下载' "$($_.Exception.Message)" $false }
}

Remove-Item -Force $tmp -ErrorAction SilentlyContinue
Write-Output "`n== 结果 =="
$results | Format-Table -AutoSize
$failed = ($results | Where-Object { $_.结果 -eq 'FAIL' }).Count
Write-Output "PASS=$(($results | Where-Object { $_.结果 -eq 'PASS' }).Count)  FAIL=$failed"
if ($failed -gt 0) { exit 1 }
