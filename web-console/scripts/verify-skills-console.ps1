# Independent check of the console skill endpoints using a real JWT.
# ASCII only.

param(
  [string]$Base = 'http://127.0.0.1:18081',
  [string]$Identifier = 'skilltest-1789184820@local.test',
  [string]$Password = 'SkillTest!2026'
)

Add-Type -AssemblyName System.Net.Http

$handler = New-Object System.Net.Http.HttpClientHandler
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(30)

$bytes = New-Object byte[] 16
(New-Object System.Random).NextBytes($bytes)
$deviceId = -join ($bytes | ForEach-Object { $_.ToString('x2') })

# 1. login
$json = '{"identifier":"' + $Identifier + '","password":"' + $Password + '"}'
$loginContent = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
$loginRequest = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "$Base/console/platform/auth/login")
$loginRequest.Content = $loginContent
$loginRequest.Headers.Add('Accept', 'application/json')
$loginRequest.Headers.Add('X-Device-Id', $deviceId)
$loginResponse = $client.SendAsync($loginRequest).GetAwaiter().GetResult()
$loginBody = $loginResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
Write-Output ("login: " + [int]$loginResponse.StatusCode)

$token = $null
if ($loginBody -match '"accessToken":"([^"]+)"') { $token = $matches[1] }
if (-not $token) {
  Write-Output 'no access token; aborting'
  $client.Dispose()
  exit 1
}

function Get-Console {
  param([string]$Path)
  $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$Base$Path")
  $request.Headers.Add('Accept', 'application/json')
  $request.Headers.Add('Authorization', "Bearer $token")
  $response = $client.SendAsync($request).GetAwaiter().GetResult()
  [pscustomobject]@{
    Path   = $Path
    Status = [int]$response.StatusCode
    Body   = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
  }
}

function Show-FirstMatch {
  param([string]$Text, [string]$Pattern, [string]$Label)
  $m = [regex]::Match($Text, $Pattern)
  if ($m.Success) { Write-Output ("  " + $Label + "=" + $m.Groups[1].Value) }
}

$list = Get-Console '/console/gateway/skills'
Write-Output ("GET " + $list.Path + " -> " + $list.Status)
Show-FirstMatch -Text $list.Body -Pattern '"object":"([^"]+)"' -Label 'object'
$names = [regex]::Matches($list.Body, '"name":"([^"]+)"')
Write-Output ("  data entries=" + $names.Count)
$titles = [regex]::Matches($list.Body, '"title":"([^"]+)"')
for ($i = 0; $i -lt $names.Count; $i++) {
  $title = if ($i -lt $titles.Count) { $titles[$i].Groups[1].Value } else { '?' }
  Write-Output ("    - " + $names[$i].Groups[1].Value + " (" + $title + ")")
}

$detail = Get-Console '/console/gateway/skills/content-writer'
Write-Output ("GET " + $detail.Path + " -> " + $detail.Status)
Show-FirstMatch -Text $detail.Body -Pattern '"object":"([^"]+)"' -Label 'object'
Show-FirstMatch -Text $detail.Body -Pattern '"name":"([^"]+)"' -Label 'name'
Show-FirstMatch -Text $detail.Body -Pattern '"encoding":"([^"]+)"' -Label 'encoding'
Show-FirstMatch -Text $detail.Body -Pattern '"title":"([^"]+)"' -Label 'title'
Show-FirstMatch -Text $detail.Body -Pattern '"category":"([^"]+)"' -Label 'category'

# 2. anonymous access must be refused
$anon = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$Base/console/gateway/skills")
$anonResponse = $client.SendAsync($anon).GetAwaiter().GetResult()
Write-Output ("anonymous GET /console/gateway/skills -> " + [int]$anonResponse.StatusCode)

# 3. /v1 must still demand an API key
$v1 = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "$Base/v1/skills")
$v1Response = $client.SendAsync($v1).GetAwaiter().GetResult()
$v1Body = $v1Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
Write-Output ("no-key GET /v1/skills -> " + [int]$v1Response.StatusCode + " " + $v1Body.Substring(0, [Math]::Min(120, $v1Body.Length)))

$client.Dispose()
