# 上游「清晰度档位是否真的受理」实测探针（天翼云 → doubao-seedream）
#
# 目的：回答一个代码里**推断**出来、但没有实测过的问题——厂商到底受理哪些 `size`。
#
# 背景：网关 V115 把 `1.5K` 做成了一等档位（词表、归约阈值、费率卡、任务表、
# 平台控制台五处一起放开），依据是厂商文档口径写的 `1K / 1.5K / 2K`。文档口径
# 与实际受理**不是一回事**（V114 的教训：文档没写 `n`，实测发现厂商受理但静默
# 忽略它）。本脚本把一个档位一个档位地打过去，并且**下载返回的图量它的实际边长**，
# 因此结论不是「HTTP 200」，而是「1.5K 真的出了一张 1536 边长的图」。
#
# 结论决定代码怎么改：
#   * ACCEPTED            → 保留在 TianYiYunImageGenerationAdapter.MODEL_TIERS 里。
#   * REJECTED（归属 size）→ 从 MODEL_TIERS 里删掉该档位（一行改动）。目录会立刻
#                           把它移出 submittableSizeTiers，客户端不再能选到一个
#                           提交必然失败的档位；`1K/1.5K/2K/4K` 的词表本身不用回退。
#   * INVALID             → 这次**没有测到 size**（错误归属别的参数，例如参考图取不到）。
#                           不要把它读成「厂商拒绝这一档」，换条件重跑。
#
# 用法（在能直连上游的机器上执行）：
#   pwsh -File scripts/Probe-UpstreamImageSize.ps1 -DryRun
#   pwsh -File scripts/Probe-UpstreamImageSize.ps1 -ApiKey 'sk-...'
#   pwsh -File scripts/Probe-UpstreamImageSize.ps1 -ApiKey 'sk-...' -Sizes 1K,1.5K,2K,1536x864
#
# 已跑过一次（2026-10-13，用生产渠道凭证直连厂商），结论：
#   size=1K   → 200, 1152x864    受理
#   size=1.5K → 200, 1872x1248   受理   ← 网关 V115 的受理清单据此成立
#   size=2K   → 200, 2496x1664   受理
#   size=4K   → 400 InvalidParameter(param=size)  **不受理** → 已从 MODEL_TIERS 删除
#   1536x864  → 200, 1536x864    逐像素精确
#   2048x1152 → 200, 2048x1152   逐像素精确
#   原始应答与图片落在 test-results/upstream-image-size-probe/。
#   两条附带结论：① 显式像素尺寸被逐位执行（目录的 aspectRatioSizes 因此可靠）；
#   ② 厂商的档位预设与平台档位区间**不是同一把尺子**（1K 出 1152 边、2K 出 2496 边），
#      平台按**请求**归约出的档位计费，因此不影响计费口径。
#   换模型或换厂商（例如 lite、另一家聚合网关）时重跑本脚本，不要照着文档猜。
#
# 注意：
#   * **每一个用例都是一次真实的付费厂商调用**（约 30~120 秒、按张计费）。
#     默认矩阵 6 次调用；-DryRun 只打印将要发送的载荷，不发出任何请求。
#   * 密钥只在内存中使用：不打印、不落盘。请通过 -ApiKey 或环境变量
#     TIANYI_IMAGE_KEY 传入，不要写进命令行历史或提交到仓库。
#   * 本脚本**只直连上游**，不经过本网关，因此它测的是厂商行为，不是网关行为；
#     网关侧的准入边界由 ImageSizeTierVocabularyTest 与 ImageGenerationBillingTest 覆盖。
#   * 约定与前两个脚本一致（Probe-UpstreamImageCount.ps1、Verify-ImageChain.ps1）：
#     默认英文提示词，中文用 -PromptFile（Windows PowerShell 5.1 会把中文 body
#     按 Latin-1 发出去）。

[CmdletBinding()]
param(
    # 上游基址。适配器自己补 /v1（见 TianYiYunImageGenerationAdapter.API_ROOT），
    # 因此这里给裸域名，与 V81 里 tianyi-image-primary 的 base_url 一致。
    [string]$UpstreamBaseUrl = 'https://ai.ctaigw.cn',
    [string]$ApiKey = $env:TIANYI_IMAGE_KEY,
    [string]$Model = 'doubao-seedream-5.0-pro',

    # 要测的 size 取值。前三项是档位字符串（厂商文档口径 1K/1.5K/2K），
    # 第 4~6 项是显式像素：1536x864 应归约到 1.5K、2048x1152 归约到 2K。
    # 4K 一并测：它在提交口径里有、文档里没有，是另一条待收敛的缺口。
    [string[]]$Sizes = @('1K', '1.5K', '2K', '4K', '1536x864', '2048x1152'),

    # 中文提示词请指向一个 UTF-8(无 BOM) 文件，内容为完整的请求体 JSON。
    [string]$PromptFile = '',

    [string]$OutDir = 'test-results/upstream-image-size-probe',
    [int]$TimeoutSeconds = 300,

    # 下载返回的图并量它的实际边长（默认开）。它是"真的出了 1.5K"的唯一证据。
    [switch]$SkipDimensionCheck,

    # 只打印将要发送的载荷与用法，不发出任何请求（不花钱）。
    [Alias('WhatIf')]
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$UpstreamBaseUrl = $UpstreamBaseUrl.TrimEnd('/')
$endpoint = "$UpstreamBaseUrl/v1/images/generations"
$results = New-Object System.Collections.Generic.List[object]
$dryRun = [bool]$DryRun

# 档位 → 单边像素上限；与 ImageGenerationService.tierMaxEdge 同源。
# 判定「返回的图属于这一档」用它：最长边必须不大于本档上限、且**大于**下一档的下限。
$tierEdges = [ordered]@{ '1K' = 1024; '1.5K' = 1536; '2K' = 2048; '4K' = 4096 }
$tierOrder = @('1K', '1.5K', '2K', '4K')

function Add-Result([string]$case, [string]$expect, [string]$actual, [string]$verdict) {
    $results.Add([pscustomobject]@{
        case = $case; expect = $expect; actual = $actual; verdict = $verdict
    })
}

function Send-Upstream([string]$bodyJson, [string]$caseName) {
    $file = Join-Path $OutDir "body-$caseName.json"
    [IO.File]::WriteAllText($file, $bodyJson, (New-Object Text.UTF8Encoding($false)))

    $headers = @{ Authorization = "Bearer $ApiKey" }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $status = 0
    $content = ''
    try {
        $r = Invoke-WebRequest -Method POST -Uri $endpoint -Headers $headers `
            -InFile $file -ContentType 'application/json' `
            -TimeoutSec $TimeoutSeconds -UseBasicParsing
        $status = [int]$r.StatusCode
        $content = $r.Content
    } catch {
        $resp = $_.Exception.Response
        $status = $(if ($resp) { [int]$resp.StatusCode } else { 0 })
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $content = $_.ErrorDetails.Message }
    }
    $sw.Stop()

    $raw = Join-Path $OutDir "response-$caseName.json"
    [IO.File]::WriteAllText($raw, "$content", (New-Object Text.UTF8Encoding($false)))

    return @{
        status  = $status
        content = "$content"
        raw     = $raw
        seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
}

# 从一次应答里抽出判定需要的东西：图片 URL、错误归属的参数、错误码与消息。
# error.param 必须抽出来：上游对「参考图下载超时」回的是 HTTP 400 InvalidParameter，
# 与「size 不被受理」是完全不同的 400，只看状态码会把基础设施故障误判成拒绝。
function Read-Answer($response) {
    $url = ''
    $code = ''
    $param = ''
    $message = ''
    try {
        $j = $response.content | ConvertFrom-Json
        if ($j.data) { $url = "$(@($j.data)[0].url)" }
        $code = $j.error.code
        $param = $j.error.param
        $message = $j.error.message
    } catch { }
    $flat = ("$message" -replace '\s+', ' ').Trim()
    if ($flat.Length -gt 160) { $flat = $flat.Substring(0, 160) + '...' }
    return @{ url = $url; code = "$code"; param = "$param"; message = $flat }
}

# 把返回的图下载下来量边长。返回 @{ width; height; note }；
# 量不到时 width/height 为 0 并给出 note —— 判定随后降级为「只看 HTTP」，并明确标注。
function Measure-Image([string]$url, [string]$caseName) {
    if (-not $url) { return @{ width = 0; height = 0; note = 'no image url' } }
    $file = Join-Path $OutDir "image-$caseName.bin"
    try {
        Invoke-WebRequest -Uri $url -OutFile $file -TimeoutSec 120 -UseBasicParsing
    } catch {
        return @{ width = 0; height = 0; note = "download failed: $($_.Exception.Message)" }
    }
    try {
        Add-Type -AssemblyName System.Drawing -ErrorAction Stop
        $img = [System.Drawing.Image]::FromFile($file)
        try { return @{ width = $img.Width; height = $img.Height; note = '' } }
        finally { $img.Dispose() }
    } catch {
        return @{ width = 0; height = 0; note = "dimension check unavailable: $($_.Exception.Message)" }
    }
}

function New-Body([string]$prompt, [string]$size) {
    $o = [ordered]@{
        model           = $Model
        prompt          = $prompt
        response_format = 'url'
        size            = $size
        stream          = $false
        watermark       = $true
    }
    return ($o | ConvertTo-Json -Compress -Depth 5)
}

# 一张图属于哪一档：最长边落在 (上一档上限, 本档上限] 区间里。
# 落在任何区间之外（例如要 1.5K 却回了 2048 边）判为 MISMATCH —— 那不是"受理"，
# 是"客户按 1.5K 付费、拿到 2K"或反之，两者都要在结论里说清楚。
function TierOf([int]$width, [int]$height) {
    $edge = [math]::Max($width, $height)
    for ($i = 0; $i -lt $tierOrder.Count; $i++) {
        $tier = $tierOrder[$i]
        $lower = $(if ($i -eq 0) { 0 } else { $tierEdges[$tierOrder[$i - 1]] })
        if ($edge -gt $lower -and $edge -le $tierEdges[$tier]) { return $tier }
    }
    return ''
}

# ── 前置 ──────────────────────────────────────────────────────────────────────
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

Write-Output '== 上游清晰度档位探针 =='
Write-Output "  endpoint : POST $endpoint"
Write-Output "  model    : $Model"
Write-Output "  sizes    : $($Sizes -join ', ')"
if ($PromptFile) {
    Write-Output "  body     : 取自 -PromptFile $PromptFile（中文提示词请走这条路）"
} else {
    Write-Output '  body     : 内置英文提示词'
}
Write-Output "  evidence : $((Resolve-Path $OutDir).Path)"
Write-Output ''
Write-Output '  [!] 每个用例都是一次真实的付费厂商调用（约 30~120 秒）。'
if (-not $SkipDimensionCheck) {
    Write-Output '      每个用例还会下载返回的图量实际边长 —— 这是"1.5K 真的出了 1.5K"的唯一证据。'
}
Write-Output ''

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Output '未提供密钥：请用 -ApiKey 传入，或设置环境变量 TIANYI_IMAGE_KEY。'
    Write-Output "   pwsh -File scripts/Probe-UpstreamImageSize.ps1 -ApiKey 'sk-...'"
    Write-Output ''
    Write-Output '（不传密钥时只打印载荷矩阵，不发出任何请求。）'
    $dryRun = $true
}

# ── 用例矩阵 ──────────────────────────────────────────────────────────────────
# 设计：除了 size 之外，所有字段逐字相同。差别只允许来自 size 这一个变量，
# 否则「这一档被拒」可能只是提示词或水印不同导致的。
$cases = New-Object System.Collections.Generic.List[object]
$prompt = 'a red apple on a wooden table, soft studio light'

if ($PromptFile) {
    $cases.Add(@{ name = 'promptfile'; size = '(from file)'; body = [IO.File]::ReadAllText($PromptFile) })
    Write-Output '  （使用 -PromptFile：只跑该请求体一个用例，不做档位对比）'
} else {
    foreach ($size in $Sizes) {
        $name = 'size-' + ($size -replace '[^0-9A-Za-z]', '_')
        $cases.Add(@{ name = $name; size = $size; body = (New-Body $prompt $size) })
    }
}

if ($dryRun) {
    Write-Output '== 将要发送的载荷（dry run，不发出请求）=='
    foreach ($c in $cases) { Write-Output "  [$($c.name)] $($c.body)" }
    Write-Output ''
    Write-Output "用例数 $($cases.Count)，全部为付费调用。去掉 -DryRun 并传入密钥即开始实测。"
    return
}

# ── 执行 ──────────────────────────────────────────────────────────────────────
foreach ($case in $cases) {
    Write-Output "-- [$($case.name)] size=$($case.size)"
    $resp = Send-Upstream $case.body $case.name
    $a = Read-Answer $resp
    $dim = @{ width = 0; height = 0; note = 'not checked' }
    if ($resp.status -eq 200 -and $a.url -and -not $SkipDimensionCheck) {
        $dim = Measure-Image $a.url $case.name
    }

    if ($resp.status -eq 200) {
        if ($dim.width -gt 0) {
            Write-Output "   200  $($resp.seconds)s  image=$($dim.width)x$($dim.height)"
        } else {
            Write-Output "   200  $($resp.seconds)s  imageUrl=$($a.url)  尺寸未量（$($dim.note)）"
        }
    } else {
        Write-Output "   HTTP $($resp.status)  $($resp.seconds)s  code=$($a.code)"
        $snippet = "$($resp.content)"
        if ($snippet.Length -gt 300) { $snippet = $snippet.Substring(0, 300) + '...' }
        Write-Output "   $snippet"
    }
    if ($a.message) { Write-Output "   upstream: $($a.message)" }

    $actual = "HTTP $($resp.status) size=$($case.size) image=$($dim.width)x$($dim.height) " +
        "param=$(if ($a.param) { $a.param } else { '-' }) code=$($a.code) $($a.message)"
    Add-Result $case.name $case.size $actual 'RECORDED'
    Write-Output ''
}

# ── 判定 ──────────────────────────────────────────────────────────────────────
Write-Output '== 判定 =='
$verdicts = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    if ($case.name -eq 'promptfile') { continue }
    $r = $results | Where-Object { $_.case -eq $case.name } | Select-Object -First 1
    $size = $case.size
    $status = [int]([regex]::Match($r.actual, 'HTTP (\d+)').Groups[1].Value)
    $w = [int]([regex]::Match($r.actual, 'image=(\d+)x').Groups[1].Value)
    $h = [int]([regex]::Match($r.actual, 'image=\d+x(\d+)').Groups[1].Value)
    $param = [regex]::Match($r.actual, 'param=([^\s]*)').Groups[1].Value
    $code = [regex]::Match($r.actual, 'code=([^\s]*)').Groups[1].Value

    $verdict = 'RECORDED'
    $detail = ''

    if ($status -ne 200) {
        # 非 200：先分清是谁的错。错误归属 size（或 invalid_parameter 且消息提到 size）
        # = 厂商真的不受理这一档；归属别的参数 = 这次没测到 size。
        if ($param -eq 'size' -or ($code -match 'size' ) -or ($r.actual -match 'size')) {
            if ($param -and $param -ne 'size' -and $param -ne '-') {
                $verdict = 'INVALID'
                $detail = "did NOT test size=$size : upstream rejected param=$param instead"
            } else {
                $verdict = 'REJECTED'
                $detail = "size=$size rejected by upstream : $($r.actual)"
            }
        } else {
            $verdict = 'INVALID'
            $detail = "did NOT test size=$size : HTTP $status with error owner '$param' : $($r.actual)"
        }
    } elseif ($w -le 0) {
        $verdict = 'ACCEPTED_HTTP_ONLY'
        $detail = "size=$size accepted (HTTP 200) but the returned image was not measured"
    } else {
        $expectedTier = $(if ($tierEdges.Contains($size)) { $size } else { '' })
        if ($expectedTier) {
            $actualTier = TierOf $w $h
            if ($actualTier -eq $expectedTier) {
                $verdict = 'ACCEPTED'
                $detail = "size=$size -> ${w}x${h} (tier $actualTier)"
            } else {
                $verdict = 'MISMATCH'
                $detail = "size=$size -> ${w}x${h} which is tier '$actualTier', not $expectedTier"
            }
        } else {
            # 显式像素：要求逐位精确。
            $parts = $size -split '[xX]'
            if ([int]$parts[0] -eq $w -and [int]$parts[1] -eq $h) {
                $verdict = 'ACCEPTED'
                $detail = "size=$size -> ${w}x${h} exact"
            } else {
                $verdict = 'MISMATCH'
                $detail = "size=$size -> ${w}x${h} (expected exactly $size)"
            }
        }
    }

    Write-Output "  $($case.name.PadRight(14)) $verdict - $detail"
    $verdicts.Add(@{ size = $size; verdict = $verdict; detail = $detail })
}

# ── 结论与下一步 ──────────────────────────────────────────────────────────────
Write-Output ''
Write-Output '== 结论与下一步 =='
$accepted = @($verdicts | Where-Object { $_.verdict -eq 'ACCEPTED' })
$httpOnly = @($verdicts | Where-Object { $_.verdict -eq 'ACCEPTED_HTTP_ONLY' })
$mismatch = @($verdicts | Where-Object { $_.verdict -eq 'MISMATCH' })
$rejected = @($verdicts | Where-Object { $_.verdict -eq 'REJECTED' })
$invalid = @($verdicts | Where-Object { $_.verdict -eq 'INVALID' })

$tier15 = $verdicts | Where-Object { $_.size -eq '1.5K' } | Select-Object -First 1
if ($tier15) {
    switch ($tier15.verdict) {
        'ACCEPTED' {
            Write-Output '  [OK] 1.5K 真的出图：网关 V115 的受理清单有实测依据，保持现状。'
        }
        'ACCEPTED_HTTP_ONLY' {
            Write-Output '  [??] 1.5K 返回 200，但本次没量到图的边长 —— 请重跑并保留 -SkipDimensionCheck 关闭状态。'
        }
        'MISMATCH' {
            Write-Output '  [!!] 1.5K 返回 200 但**出的不是 1.5K**。客户按 1.5K 付费却拿到别的档位。'
            Write-Output '       处理：要么按实际产出的档位定价，要么把 1.5K 从 MODEL_TIERS 里删掉。'
        }
        'REJECTED' {
            Write-Output '  [NO] 1.5K 被厂商拒绝。请从 TianYiYunImageGenerationAdapter.MODEL_TIERS 的'
            Write-Output '       doubao-seedream-5.0-pro 集合里删掉 "1.5K"（一行改动）。'
            Write-Output '       删掉之后：目录会把它移出 submittableSizeTiers（客户端不再能选），'
            Write-Output '       operator 会重新上报 documented_size_tier_not_supported_by_gateway:1.5K；'
            Write-Output '       词表、费率卡、平台控制台都不用回退（它们只是"允许"，不承诺厂商受理）。'
        }
        default {
            Write-Output "  [?] 1.5K 本次判定为 $($tier15.verdict) —— 见上面的明细，不要据此下结论。"
        }
    }
}

if ($mismatch.Count -gt 0) {
    Write-Output ''
    Write-Output "  [!] 有 $($mismatch.Count) 个 size 返回 200 但产出档位不符："
    foreach ($m in $mismatch) { Write-Output "      - $($m.detail)" }
}

if ($rejected.Count -gt 0) {
    Write-Output ''
    Write-Output "  [!] 被拒绝的档位 $($rejected.Count) 个，逐个核对 MODEL_TIERS："
    foreach ($r in $rejected) { Write-Output "      - $($r.detail)" }
}

if ($httpOnly.Count -gt 0) {
    Write-Output ''
    Write-Output "  [?] $($httpOnly.Count) 个用例只拿到 HTTP 200、没量到边长（判定证据较弱）。"
}

if ($invalid.Count -gt 0) {
    Write-Output ''
    Write-Output "  [!] 有 $($invalid.Count) 个用例是 INVALID —— 它们**没有测到 size**，不要当成拒绝。"
    Write-Output '      典型原因：错误归属别的参数（参考图取不到、提示词被审核拦下）。换条件重跑。'
}

Write-Output ''
$results | Format-Table -AutoSize
Write-Output "证据已落盘：$((Resolve-Path $OutDir).Path)"
Write-Output '  * response-*.json 是上游原始应答，body-*.json 是实际发出的载荷（只差 size 一个字段）。'
Write-Output '  * image-*.bin 是下载回来量过边长的图；结论要连同这些一起归档。'
