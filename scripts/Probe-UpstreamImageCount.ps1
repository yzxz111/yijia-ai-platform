# 上游「一次出多张」实测探针（天翼云 → doubao-seedream）
#
# 目的：回答一个今天没有答案的问题 —— **厂商到底受不受理 n（一次要 N 张）**。
#   网关目前不向上游转发 n（见 TianYiYunImageGenerationAdapter 的载荷构造），
#   并在 n > 1 时以 422 image_count_not_supported 拒绝。这个限制是网关的选择，
#   而不是厂商契约的结论：代码注释写的是「厂商从未确认接受该参数」。本脚本就是去确认。
#
# 结论决定长期架构：
#   * 厂商受理 n        → 走「一次调用出 N 张」，网关只需透传 + 放开 MAX_IMAGES，
#                         厂商侧并行，成本最低、计费最简单。
#   * 厂商忽略 n        → 透传无意义，只能网关侧并发扇出 N 次调用（改动大得多）。
#   * 厂商拒绝 n        → 同上，且错误码应被映射成稳定的客户端可见原因。
#
# 用法（在能直连上游的机器上执行）：
#   pwsh -File scripts/Probe-UpstreamImageCount.ps1 -ApiKey 'sk-...'
#   pwsh -File scripts/Probe-UpstreamImageCount.ps1 -ApiKey 'sk-...' -ReferenceImageUrl 'https://你的图床/ref.jpg'
#   pwsh -File scripts/Probe-UpstreamImageCount.ps1 -DryRun
#
# 注意：
#   * **每一次调用都是一次真实的付费厂商调用**（约 30~170 秒、按张计费）。
#     默认矩阵最多 6 次；-DryRun 只打印将要发送的载荷，不发出任何请求。
#   * 密钥只在内存中使用：不打印、不落盘。请通过 -ApiKey 或环境变量
#     TIANYI_IMAGE_KEY 传入，不要写进命令行历史或提交到仓库。
#   * 本脚本**只直连上游**，不经过本网关，因此不受 image_count_not_supported 拦截；
#     它测的是厂商行为，不是网关行为。
#   * 约定与 scripts/Verify-ImageChain.ps1 一致：默认英文提示词，中文用 -PromptFile
#     （Windows PowerShell 5.1 会把中文 body 按 Latin-1 发出去）。

[CmdletBinding()]
param(
    # 上游基址。适配器自己补 /v1（见 TianYiYunImageGenerationAdapter.API_ROOT），
    # 因此这里给裸域名，与 V81 里 tianyi-image-primary 的 base_url 一致。
    [string]$UpstreamBaseUrl = 'https://ai.ctaigw.cn',
    [string]$ApiKey = $env:TIANYI_IMAGE_KEY,
    [string]$Model = 'doubao-seedream-5.0-pro',
    [string]$Size = '2K',

    # 图生图用的参考图：必须是**厂商拉得取的公网 http(s) 地址**。
    # 传了它才会跑 image-to-image 用例；不传则只跑文生图用例。
    [string]$ReferenceImageUrl = '',

    # 要验证的 n 取值。不带 n 的基线由 -SkipBaseline 控制。
    [int[]]$Counts = @(2, 4),

    # 中文提示词请指向一个 UTF-8(无 BOM) 文件，内容为完整的请求体 JSON。
    [string]$PromptFile = '',

    [string]$OutDir = 'test-results/upstream-image-count-probe',
    [int]$TimeoutSeconds = 300,
    [switch]$SkipBaseline,

    # 跳过参考图可达性预检（默认不跳）。预检失败不影响退出码，只影响图生图用例是否发出。
    [switch]$SkipReferenceImageCheck,

    # 只打印将要发送的载荷与用法，不发出任何请求（不花钱）。
    # 名字刻意不叫 -WhatIf：那在 PowerShell 里是通用参数，容易被误以为会自动生效。
    [Alias('WhatIf')]
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'
$UpstreamBaseUrl = $UpstreamBaseUrl.TrimEnd('/')
$endpoint = "$UpstreamBaseUrl/v1/images/generations"
$results = New-Object System.Collections.Generic.List[object]
$dryRun = [bool]$DryRun

function Add-Result([string]$case, [string]$expect, [string]$actual, [string]$verdict) {
    $results.Add([pscustomobject]@{
        case = $case; expect = $expect; actual = $actual; verdict = $verdict
    })
}

# 与 Verify-ImageChain.ps1 同一套写法：body 走文件，避免 5.1 的编码坑与命令行引号问题。
function Send-Upstream([string]$bodyJson, [string]$caseName) {
    $file = Join-Path $OutDir "body-$caseName.json"
    [IO.File]::WriteAllText($file, $bodyJson, (New-Object Text.UTF8Encoding($false)))

    $headers = @{ Authorization = "Bearer $ApiKey" }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $status = 0
    $content = ''
    $headersOut = $null
    try {
        $r = Invoke-WebRequest -Method POST -Uri $endpoint -Headers $headers `
            -InFile $file -ContentType 'application/json' `
            -TimeoutSec $TimeoutSeconds -UseBasicParsing
        $status = [int]$r.StatusCode
        $content = $r.Content
        $headersOut = $r.Headers
    } catch {
        $resp = $_.Exception.Response
        $status = $(if ($resp) { [int]$resp.StatusCode } else { 0 })
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $content = $_.ErrorDetails.Message }
        if ($resp) { $headersOut = $resp.Headers }
    }
    $sw.Stop()

    $raw = Join-Path $OutDir "response-$caseName.json"
    [IO.File]::WriteAllText($raw, "$content", (New-Object Text.UTF8Encoding($false)))

    return @{
        status  = $status
        content = "$content"
        raw     = $raw
        headers = $headersOut
        seconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }
}

# 从一次应答里抽出判定所需的几件事：返回了几张、厂商自报几张、错误码、
# 错误归属的参数、错误消息、请求 id。
#
# error.param 与 error.message 必须抽出来：2026-10-12 实测踩到过——
# 上游对「参考图下载超时」回的是 HTTP 400 InvalidParameter，与「n 不被受理」
# 是完全不同的 400。只看状态码会把基础设施故障误判成「厂商拒绝 n」。
function Read-Answer($response) {
    $imageCount = 0
    $usageCount = $null
    $code = ''
    $param = ''
    $message = ''
    $requestId = ''
    try {
        $j = $response.content | ConvertFrom-Json
        if ($j.data) { $imageCount = @($j.data).Count }
        $code = $j.error.code
        $param = $j.error.param
        $message = $j.error.message
        foreach ($field in @('generated_images', 'images', 'total_images', 'image_count')) {
            $v = $j.usage.$field
            if ($null -ne $v) { $usageCount = $v; break }
        }
    } catch { }
    if ($response.headers) {
        foreach ($name in @('x-request-id', 'X-Request-Id', 'x-tt-logid')) {
            try {
                if ($response.headers[$name]) { $requestId = "$($response.headers[$name])"; break }
            } catch { }
        }
    }
    # 错误消息压成一行，只留判定与排障需要的前 160 字符。
    $flat = ("$message" -replace '\s+', ' ').Trim()
    if ($flat.Length -gt 160) { $flat = $flat.Substring(0, 160) + '...' }
    return @{
        images = $imageCount; usage = $usageCount; code = "$code"
        param = "$param"; message = $flat; requestId = $requestId
    }
}

function New-Body([string]$prompt, [string]$image, $n) {
    # 字段顺序与适配器一致；n 只在明确给定时才出现，因为「省略」本身就是一个用例。
    $o = [ordered]@{ model = $Model }
    if ($prompt) { $o.prompt = $prompt }
    if ($image) { $o.image = $image }
    $o.response_format = 'url'
    $o.size = $Size
    $o.stream = $false
    $o.watermark = $true
    if ($null -ne $n) { $o.n = $n }
    return ($o | ConvertTo-Json -Compress -Depth 5)
}

# ── 前置 ──────────────────────────────────────────────────────────────────────
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# 参考图可达性预检。
#
# 2026-10-12 实测踩到的坑：用 Wikimedia 的图当参考图，上游三个图生图用例全部
# 返回 `400 InvalidParameter: Timeout while downloading url` —— 厂商取不到那张图，
# 于是**一个图生图用例都没有真正测到 n**，三次付费调用全部作废。
# 因此这里先本地取一次：取不到就直接判失败并说清理由，而不是把付费调用打出去。
$refImageUsable = $true
if ($ReferenceImageUrl -and -not $SkipReferenceImageCheck -and -not $dryRun) {
    Write-Output '-- 参考图可达性预检（本地取一次）...'
    try {
        $head = Invoke-WebRequest -Uri $ReferenceImageUrl -Method Head -TimeoutSec 30 -UseBasicParsing
        $ctype = "$($head.Headers['Content-Type'])"
        if ($head.StatusCode -eq 200 -and $ctype -like 'image/*') {
            Write-Output "   OK  HTTP 200  $ctype"
            Write-Output '   注意：这只证明**你**能取到，不保证厂商能取到；厂商侧失败仍按 INVALID 判定。'
        } else {
            Write-Output "   FAIL  HTTP $($head.StatusCode)  Content-Type=$ctype"
            $refImageUsable = $false
        }
    } catch {
        # HEAD 被拒不代表 GET 不行，退一步用 GET 重试一次。
        try {
            $get = Invoke-WebRequest -Uri $ReferenceImageUrl -Method GET -TimeoutSec 30 -UseBasicParsing
            $ctype = "$($get.Headers['Content-Type'])"
            if ($get.StatusCode -eq 200 -and $ctype -like 'image/*') {
                Write-Output "   OK  HTTP 200  $ctype（HEAD 不支持，GET 通过）"
            } else {
                Write-Output "   FAIL  HTTP $($get.StatusCode)  Content-Type=$ctype"
                $refImageUsable = $false
            }
        } catch {
            Write-Output "   FAIL  $($_.Exception.Message)"
            $refImageUsable = $false
        }
    }
    if (-not $refImageUsable) {
        Write-Output ''
        Write-Output '   这张图取不到，图生图用例（也就是真正要测的那条）无法成立。'
        Write-Output '   换一张公网可访问的图，或改用你们自己的图床 / OSS 地址后重跑。'
    }
    Write-Output ''
}

Write-Output '== 上游批量出图探针 =='
Write-Output "  endpoint : POST $endpoint"
Write-Output "  model    : $Model   size: $Size"
if ($PromptFile) {
    Write-Output "  body     : 取自 -PromptFile $PromptFile（中文提示词请走这条路）"
} else {
    Write-Output '  body     : 内置英文提示词'
}
Write-Output "  refImage : $(if ($ReferenceImageUrl) { $ReferenceImageUrl } else { '(未提供，只跑文生图用例)' })"
Write-Output "  evidence : $((Resolve-Path $OutDir).Path)"
Write-Output ''
Write-Output '  [!] 每个用例都是一次真实的付费厂商调用（约 30~170 秒）。'
Write-Output ''

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Output '未提供密钥：请用 -ApiKey 传入，或设置环境变量 TIANYI_IMAGE_KEY。'
    Write-Output "   pwsh -File scripts/Probe-UpstreamImageCount.ps1 -ApiKey 'sk-...'"
    Write-Output ''
    Write-Output '（不传密钥时只打印载荷矩阵，不发出任何请求。）'
    $dryRun = $true
}

# ── 用例矩阵 ──────────────────────────────────────────────────────────────────
# 设计：基线（不带 n）与变量（带 n）必须在**其他字段完全相同**的条件下对比，
# 否则「返回 1 张」可能只是参考图或提示词不同导致的，而不是 n 被忽略。
$cases = New-Object System.Collections.Generic.List[object]
$t2iPrompt = 'a red apple on a wooden table, soft studio light'
$i2iPrompt = 'restyle this photo as a watercolor painting'

if ($PromptFile) {
    # 调用方给了完整请求体：只跑这一个用例，不猜它的内容。
    $body = [IO.File]::ReadAllText($PromptFile)
    $cases.Add(@{ name = 'promptfile'; body = $body })
    Write-Output '  （使用 -PromptFile：只跑该请求体一个用例，不做基线对比）'
} else {
    if (-not $SkipBaseline) {
        $cases.Add(@{ name = 't2i-no-n'; body = (New-Body $t2iPrompt $null $null) })
    }
    foreach ($n in $Counts) {
        $cases.Add(@{ name = "t2i-n$n"; body = (New-Body $t2iPrompt $null $n) })
    }
    # 图生图才是「一张参考图生成多张照片」的正题：基线 + 带 n，其余字段完全相同。
    # 但参考图取不到时整族都不发：2026-10-12 实测这三个付费用例全部因为
    # 「厂商下载参考图超时」返回 400，钱花了却一个都没测到 n。
    if ($ReferenceImageUrl -and -not $refImageUsable) {
        Write-Output '  （参考图预检失败：跳过全部图生图用例，只跑文生图。换一张可达的图后重跑。）'
    } elseif ($ReferenceImageUrl) {
        if (-not $SkipBaseline) {
            $cases.Add(@{ name = 'i2i-no-n'; body = (New-Body $i2iPrompt $ReferenceImageUrl $null) })
        }
        foreach ($n in $Counts) {
            $cases.Add(@{ name = "i2i-n$n"; body = (New-Body $i2iPrompt $ReferenceImageUrl $n) })
        }
    }
}

if ($dryRun) {
    Write-Output '== 将要发送的载荷（dry run，不发出请求）=='
    foreach ($c in $cases) {
        Write-Output "  [$($c.name)] $($c.body)"
    }
    Write-Output ''
    Write-Output "用例数 $($cases.Count)，全部为付费调用。去掉 -DryRun 并传入密钥即开始实测。"
    # 用 return 而不是 exit：本脚本可能被 dot-source 或包成 scriptblock 运行，
    # 那时 exit 会连带**结束调用方的会话**（静默、无输出），而 return 只是结束本脚本。
    return
}

# ── 执行 ──────────────────────────────────────────────────────────────────────
foreach ($case in $cases) {
    Write-Output "-- [$($case.name)] $($case.body)"
    $resp = Send-Upstream $case.body $case.name
    $a = Read-Answer $resp
    $usageText = $(if ($null -eq $a.usage) { 'usage 无张数字段' } else { "usage=$($a.usage)" })

    if ($resp.status -eq 200) {
        Write-Output "   200  $($resp.seconds)s  returned=$($a.images)  $usageText"
    } else {
        Write-Output "   HTTP $($resp.status)  $($resp.seconds)s  code=$($a.code)"
        $snippet = "$($resp.content)"
        if ($snippet.Length -gt 300) { $snippet = $snippet.Substring(0, 300) + '...' }
        Write-Output "   $snippet"
    }
    if ($a.requestId) { Write-Output "   x-request-id: $($a.requestId)" }
    if ($a.message) { Write-Output "   upstream: $($a.message)" }

    # 错误归属（param / message）必须进 actual：判定要靠它区分
    # 「n 被拒绝」与「参考图取不到」这两种截然不同的 400。
    $paramText = $(if ($a.param) { $a.param } else { '-' })
    Add-Result $case.name 'record (HTTP status + returned count + error owner)' `
        "HTTP $($resp.status) images=$($a.images) $usageText param=$paramText $($a.message)" `
        'RECORDED'
    Write-Output ''
}

# ── 判定 ──────────────────────────────────────────────────────────────────────
# 判定规则只看**同一族**（同 prompt、同参考图）的基线 vs 带 n 对比：
#   * 带 n 返回的条数 > 基线  → 厂商受理 n（一次调用真的出多张）。
#   * 带 n 条数与基线相同且 200 → 厂商**静默忽略** n。这条最危险：
#     透传它不会报错，但客户按 N 张付费却只拿到 1 张。
#   * 带 n 非 200，且错误归属 n  → 厂商拒绝 n。
#   * 带 n 非 200，但错误归属**别的参数**（典型：error.param = "image"，
#     参考图下载超时）→ INVALID：本次**没有测到 n**，是无用数据，必须换图重跑。
function Assess([string]$baseName, [string]$withName, [int]$expected) {
    $b = $results | Where-Object { $_.case -eq $baseName } | Select-Object -First 1
    $w = $results | Where-Object { $_.case -eq $withName } | Select-Object -First 1
    if (-not $b -or -not $w) { return $null }
    $bi = [int]([regex]::Match($b.actual, 'images=(\d+)').Groups[1].Value)
    $wi = [int]([regex]::Match($w.actual, 'images=(\d+)').Groups[1].Value)
    if ($w.actual -like 'HTTP 200*') {
        if ($wi -gt $bi) {
            return @{ verdict = 'ACCEPTED'; detail = "n=$expected works: baseline $bi -> with n $wi" }
        }
        return @{ verdict = 'IGNORED'; detail = "n=$expected silently ignored: baseline $bi -> with n $wi (HTTP 200, no error)" }
    }
    # 非 200：先分清是谁的错。错误归属 image（或其他非 n 参数）= 这次根本没测到 n。
    $param = [regex]::Match($w.actual, 'param=([^\s]*)').Groups[1].Value
    if ($param -and $param -ne '-' -and $param -ne 'n') {
        return @{ verdict = 'INVALID'; detail = "did NOT test n=${expected}: upstream rejected param=$param instead - $($w.actual)" }
    }
    if ($w.actual -match 'download|Timeout while downloading') {
        return @{ verdict = 'INVALID'; detail = "did NOT test n=${expected}: reference image was unreachable - $($w.actual)" }
    }
    return @{ verdict = 'REJECTED'; detail = "n=$expected rejected: $($w.actual)" }
}

Write-Output '== 判定 =='
$verdicts = New-Object System.Collections.Generic.List[object]

if ($cases.Count -eq 1 -and $cases[0].name -eq 'promptfile') {
    Write-Output '  （-PromptFile 单用例模式：请人工比对返回张数与载荷里的 n，见上面的实测行。）'
} else {
    $first = $Counts[0]
    $t2i = Assess 't2i-no-n' "t2i-n$first" $first
    if ($t2i) {
        Write-Output "  text-to-image : $($t2i.verdict) - $($t2i.detail)"
        $verdicts.Add($t2i)
    }
    if ($ReferenceImageUrl) {
        $i2i = Assess 'i2i-no-n' "i2i-n$first" $first
        if ($i2i) {
            Write-Output "  image-to-image (1 ref -> N photos): $($i2i.verdict) - $($i2i.detail)"
            $verdicts.Add($i2i)
        }
    }
}

Write-Output ''
Write-Output '== 结论与下一步 =='
$accepted = @($verdicts | Where-Object { $_.verdict -eq 'ACCEPTED' }).Count
$ignored = @($verdicts | Where-Object { $_.verdict -eq 'IGNORED' }).Count
$rejected = @($verdicts | Where-Object { $_.verdict -eq 'REJECTED' }).Count
$invalid = @($verdicts | Where-Object { $_.verdict -eq 'INVALID' }).Count

if ($accepted -gt 0) {
    Write-Output '  [OK] 厂商受理 n：可以走「一次调用出 N 张」。'
    Write-Output '       改造面：适配器透传 n -> 放开 ImageGenerationController.imageCount 与'
    Write-Output '       ImageGenerationService.MAX_IMAGES -> reserve/chargedImages 按 N 计费。'
    Write-Output '       这条路厂商侧并行，网关不需要扇出，成本与计费都最简单。'
} elseif ($ignored -gt 0) {
    Write-Output '  [!!] 厂商**静默忽略** n：透传它比拒绝更危险。'
    Write-Output '       客户按 N 张付费只会拿到 1 张，且没有任何错误可分支。'
    Write-Output '       结论：不要把 n 透传当真；要「一次请求出 N 张」只能网关侧并发扇出 N 次调用'
    Write-Output '       （需新增子任务幂等、预留按 N、部分失败逐张退款、并发上限）。'
} elseif ($rejected -gt 0) {
    Write-Output '  [NO] 厂商拒绝 n：与今天的网关行为一致，但原因是厂商契约而非网关选择。'
    Write-Output '       应把厂商的拒绝码映射成稳定的客户端可见原因（现在是网关自己的 422）。'
    Write-Output '       要「一次请求出 N 张」同样只能网关侧并发扇出。'
} else {
    Write-Output "  （用例不完整，无法判定：请查看上面的实测行与 $OutDir 下的原始响应。）"
}

# INVALID 必须单独喊出来：它意味着「这次没测到 n」，把它的 400 读成
# 「厂商拒绝 n」会得出一个错误结论，进而选错实现路线。
if ($invalid -gt 0) {
    Write-Output ''
    Write-Output "  [!] 有 $invalid 个用例是 INVALID —— 它们**没有测到 n**，不要当成拒绝。"
    Write-Output '      典型原因：厂商取不到参考图（error.param = "image"）。'
    Write-Output '      处理：换一张厂商能取到的图（最好用自有图床/OSS），重跑图生图那三个用例。'
    Write-Output '      **在只测到文生图的情况下，不要对「一张参考图出多张」下结论。**'
}

Write-Output ''
$results | Format-Table -AutoSize
Write-Output "证据已落盘：$((Resolve-Path $OutDir).Path)"
Write-Output '  * response-*.json 是上游原始应答（含 usage 与错误体），请连同结论一起附到文档。'
Write-Output '  * body-*.json 是实际发出的载荷，用于证明 baseline 与带 n 用例只差 n 一个字段。'
