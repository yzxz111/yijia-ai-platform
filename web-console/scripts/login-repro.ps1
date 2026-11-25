# Replays the browser login request with full browser-like headers.
# ASCII only.

param(
  [string]$Base = 'http://127.0.0.1:18081',
  [string]$Identifier = 'skilltest-1789184820@local.test',
  [string]$Password = 'SkillTest!2026',
  [string]$DeviceId = '',
  [switch]$Minimal
)

Add-Type -AssemblyName System.Net.Http

if ([string]::IsNullOrWhiteSpace($DeviceId)) {
  $bytes = New-Object byte[] 16
  (New-Object System.Random).NextBytes($bytes)
  $DeviceId = -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

$json = '{"identifier":"' + $Identifier + '","password":"' + $Password + '"}'
$content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')

$request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$Base/console/platform/auth/login")
$request.Content = $content
$request.Headers.Add('Accept', 'application/json, text/plain, */*')
$request.Headers.Add('X-Device-Id', $DeviceId)
$request.Headers.Add('Accept-Language', 'zh-CN,zh;q=0.9,en;q=0.8')

if (-not $Minimal) {
  $request.Headers.Add('Origin', $Base)
  $request.Headers.Add('Referer', "$Base/login")
  $request.Headers.Add('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
  $request.Headers.Add('Sec-Fetch-Site', 'same-origin')
  $request.Headers.Add('Sec-Fetch-Mode', 'cors')
  $request.Headers.Add('Sec-Fetch-Dest', 'empty')
  $request.Headers.Add('X-Requested-With', 'XMLHttpRequest')
}

$label = if ($Minimal) { 'MINIMAL headers (no Origin/Referer/UA)' } else { 'BROWSER-LIKE headers (Origin/Referer/UA/sec-fetch)' }
Write-Output "=== $label ==="
Write-Output "POST $Base/console/platform/auth/login"
Write-Output "identifier=$Identifier deviceId=$DeviceId"

try {
  $response = $client.SendAsync($request).GetAwaiter().GetResult()
  $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  Write-Output ("status: " + [int]$response.StatusCode + " " + $response.StatusCode)
  Write-Output ("body: " + $body.Substring(0, [Math]::Min(700, $body.Length)))
} catch {
  Write-Output ("EXCEPTION: " + $_.Exception.Message)
  if ($_.Exception.InnerException) { Write-Output ("inner: " + $_.Exception.InnerException.Message) }
}

$client.Dispose()
