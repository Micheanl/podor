#Requires -Version 7.0
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$config = Get-Content release/publishing.json -Raw | ConvertFrom-Json
$version = [regex]::Match((Get-Content gradle/libs.versions.toml -Raw), '(?m)^podor = "([0-9]+\.[0-9]+\.[0-9]+)"').Groups[1].Value
if (-not $version) { throw '缺少有效版本号' }
if (git ls-files --error-unmatch AGENTS.md 2>$null) { throw 'AGENTS.md 必须保留在本地，不能提交' }
if (git status --porcelain -- . ':(exclude)AGENTS.md') { throw '请先提交当前修改，再发布' }
$giteeToken = $env:GITEE_TOKEN
if (-not $giteeToken -and $IsWindows) { $giteeToken = [Environment]::GetEnvironmentVariable('GITEE_TOKEN', 'User') }
if (-not $giteeToken) { throw '请在本机配置 GITEE_TOKEN，不要将凭据写入仓库' }
$githubToken = $env:GITHUB_TOKEN
if (-not $githubToken) {
    $env:GCM_INTERACTIVE = 'never'
    $env:GIT_TERMINAL_PROMPT = '0'
    $credential = "protocol=https`nhost=github.com`n`n" | git -c credential.interactive=never credential fill 2>$null
    $githubToken = ($credential | Where-Object { $_.StartsWith('password=') } | Select-Object -First 1) -replace '^password=', ''
}
if (-not $githubToken) { throw '请先登录 GitHub Git，或配置 GITHUB_TOKEN' }
$tokens = @{ github = $githubToken; gitee = $giteeToken }
$apis = @{ github = "https://api.github.com/repos/$($config.github)"; gitee = "https://gitee.com/api/v5/repos/$($config.gitee)" }

function Invoke-PublishApi($Service, $Method, $Url, $Body = $null, $File = $null, [switch]$AllowMissing) {
    $headers = @{ Authorization = "Bearer $($tokens[$Service])"; 'User-Agent' = 'podor-release'; Accept = 'application/json' }
    $request = @{ Uri = $Url; Method = $Method; Headers = $headers; SkipHttpErrorCheck = $true; TimeoutSec = 300 }
    if ($File) {
        if ($Service -eq 'gitee') { $request.Form = @{ file = Get-Item -LiteralPath $File } }
        else { $request.InFile = $File; $request.ContentType = 'application/octet-stream' }
    } elseif ($null -ne $Body) { $request.Body = $Body | ConvertTo-Json -Depth 8 -Compress; $request.ContentType = 'application/json; charset=utf-8' }
    try { $response = Invoke-WebRequest @request } catch { throw "$Service 发布连接失败" }
    if ($AllowMissing -and $response.StatusCode -eq 404) { return $null }
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        if ($Service -eq 'gitee' -and $response.StatusCode -eq 400 -and $response.Content -match '文件大小已超出仓库附件配额') { throw 'Gitee 仓库附件配额已满，需要整理旧安装包或更换下载仓库' }
        throw "$Service $Method 发布失败，HTTP $($response.StatusCode)，路径 $(([uri]$Url).AbsolutePath)"
    }
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}

function Undo-NewGiteeRelease($ReleaseId, $ExpectedTag) {
    $url = "$($apis.gitee)/releases/$ReleaseId"
    $release = Invoke-PublishApi gitee GET $url -AllowMissing
    if (-not $release) { return }
    if ($release.tag_name -ne $ExpectedTag) { throw '新建发行记录不匹配，停止清理' }
    if ($release.prerelease) {
        $null = Invoke-PublishApi gitee DELETE $url
        Write-Output '已撤下本次未完成的 Gitee 发行记录，保留之前的稳定更新入口'
    }
}

function Push-Repository($Service, $Ref) {
    $username = if ($Service -eq 'github') { 'x-access-token' } else { ($config.gitee -split '/')[0] }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${username}:$($tokens[$Service])"))
    $previous = @{}
    foreach ($name in @('GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_0', 'GIT_CONFIG_VALUE_0')) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        $env:GIT_CONFIG_COUNT = '1'
        $env:GIT_CONFIG_KEY_0 = "http.https://$Service.com/.extraheader"
        $env:GIT_CONFIG_VALUE_0 = "Authorization: Basic $encoded"
        git push "https://$Service.com/$($config.$Service).git" $Ref *> $null
        if ($LASTEXITCODE) { throw "$Service 代码同步失败" }
        Write-Output "$Service 代码同步完成"
    } finally {
        foreach ($name in $previous.Keys) {
            if ($null -eq $previous[$name]) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
        }
    }
}

function Verify-PublicDownload($Url, $ExpectedHash, $ExpectedSize) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(8)
    $response = $null
    try {
        $response = $client.GetAsync($Url, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode -or $response.RequestMessage.RequestUri.Scheme -ne 'https') { throw '下载不可用' }
        $stream = $response.Content.ReadAsStream()
        $hash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
        try {
            $buffer = [byte[]]::new(65536)
            $received = 0L
            while ($true) {
                $readTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(30))
                try { $count = $stream.ReadAsync($buffer, 0, $buffer.Length, $readTimeout.Token).GetAwaiter().GetResult() }
                finally { $readTimeout.Dispose() }
                if ($count -eq 0) { break }
                $received += $count
                if ($received -gt $ExpectedSize) { throw '大小不符' }
                $hash.AppendData($buffer, 0, $count)
            }
            $actual = [Convert]::ToHexString($hash.GetHashAndReset()).ToLowerInvariant()
            if ($received -ne $ExpectedSize -or $actual -ne $ExpectedHash) { throw '校验不符' }
        } finally { $stream.Dispose(); $hash.Dispose() }
    } catch { throw '匿名直连下载校验失败，更新通道不会发布' }
    finally { if ($response) { $response.Dispose() }; $client.Dispose() }
}

foreach ($service in @('github', 'gitee')) { $null = Invoke-PublishApi $service GET $apis[$service] }
if (-not $SkipBuild) {
    & "$PSScriptRoot/check.ps1"
    if ($LASTEXITCODE) { throw '检查未通过' }
    ./gradlew.bat :desktopApp:packageMsi --console plain
    if ($LASTEXITCODE) { throw '安装包构建失败' }
}
$installer = Join-Path $root "desktopApp/build/release/$version/main/msi/podor-$version.msi"
if (-not (Test-Path -LiteralPath $installer)) { throw '找不到当前版本安装包' }
$notes = Get-Content "release/notes-$version.md" -Raw
$tag = "v$version"
$sha = (git rev-parse HEAD).Trim()
$existingTag = git rev-parse --verify "refs/tags/$tag" 2>$null
if ($existingTag -and $existingTag.Trim() -ne $sha) {
    git diff --quiet $tag HEAD -- . ':(exclude)release/latest.json' ':(exclude)scripts/publish.ps1' ':(exclude)scripts/check.ps1' ':(exclude)scripts/test-publish-rollback.ps1' ':(exclude)docs/DEVELOPMENT.md'
    if ($LASTEXITCODE) { throw '版本标签已指向其他源码，请增加版本号' }
    $sha = $existingTag.Trim()
}
if (-not $existingTag) { git tag $tag; if ($LASTEXITCODE) { throw '创建标签失败' } }
foreach ($service in @('github', 'gitee')) { Push-Repository $service 'HEAD:refs/heads/main'; Push-Repository $service "refs/tags/$tag" }
$installerHash = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()
$installerSize = (Get-Item -LiteralPath $installer).Length
$releaseIds = @{}
$assets = @{}
$createdGiteeRelease = $null
try {
foreach ($service in @('github', 'gitee')) {
    $api = $apis[$service]
    $release = Invoke-PublishApi $service GET "$api/releases/tags/$tag" -AllowMissing
    if (-not $release) {
        $release = Invoke-PublishApi $service POST "$api/releases" @{ tag_name = $tag; target_commitish = $sha; name = "podor $version"; body = $notes; prerelease = $true }
        if ($service -eq 'gitee') { $createdGiteeRelease = $release.id }
    }
    $releaseIds[$service] = $release.id
    $assetApi = if ($service -eq 'gitee') { "$api/releases/$($release.id)/attach_files" } else { "$api/releases/$($release.id)/assets" }
    $existing = @(Invoke-PublishApi $service GET $assetApi)
    $asset = $existing | Where-Object { $_.name -eq "podor-$version.msi" } | Select-Object -First 1
    if (-not $asset) {
        $upload = if ($service -eq 'gitee') { $assetApi } else { "https://uploads.github.com/repos/$($config.github)/releases/$($release.id)/assets?name=podor-$version.msi" }
        $asset = Invoke-PublishApi $service POST $upload -File $installer
    }
    $assets[$service] = $asset
    Verify-PublicDownload $asset.browser_download_url $installerHash $installerSize
    Write-Output "$service 安装包已发布并通过匿名下载校验"
}
$manifest = [ordered]@{ version = $version; notes = $notes; platform = 'windows-x64'; url = $assets.gitee.browser_download_url; sha256 = $installerHash; size = $installerSize }
$manifestPath = Join-Path (Split-Path $installer) 'latest.json'
$checksumPath = Join-Path (Split-Path $installer) 'SHA256SUMS.txt'
[IO.File]::WriteAllText($manifestPath, (($manifest | ConvertTo-Json -Depth 4) -replace "`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
"$installerHash  podor-$version.msi" | Set-Content -LiteralPath $checksumPath -Encoding ascii
foreach ($service in @('github', 'gitee')) {
    $assetApi = if ($service -eq 'gitee') { "$($apis[$service])/releases/$($releaseIds[$service])/attach_files" } else { "$($apis[$service])/releases/$($releaseIds[$service])/assets" }
    $existing = @(Invoke-PublishApi $service GET $assetApi)
    foreach ($file in @($manifestPath, $checksumPath)) {
        $name = Split-Path $file -Leaf
        $asset = $existing | Where-Object { $_.name -eq $name } | Select-Object -First 1
        if (-not $asset) {
            $upload = if ($service -eq 'gitee') { $assetApi } else { "https://uploads.github.com/repos/$($config.github)/releases/$($releaseIds[$service])/assets?name=$name" }
            $asset = Invoke-PublishApi $service POST $upload -File $file
        }
        Verify-PublicDownload $asset.browser_download_url (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() (Get-Item $file).Length
    }
}
foreach ($service in @('github', 'gitee')) {
    $body = @{ tag_name = $tag; target_commitish = $sha; name = "podor $version"; body = $notes; prerelease = $false }
    if ($service -eq 'github') { $body.make_latest = 'true' }
    $null = Invoke-PublishApi $service PATCH "$($apis[$service])/releases/$($releaseIds[$service])" $body
}
$latestGithub = Invoke-PublishApi github GET "$($apis.github)/releases/latest"
if ($latestGithub.tag_name -ne $tag -or $latestGithub.prerelease) { throw 'GitHub 最新稳定版未更新' }
Copy-Item -LiteralPath $manifestPath -Destination release/latest.json
git add release/latest.json
git diff --cached --quiet
if ($LASTEXITCODE -eq 1) { git commit -m "[发布] 同步 podor $version 更新通道"; if ($LASTEXITCODE) { throw '更新通道提交失败' } }
foreach ($service in @('github', 'gitee')) { Push-Repository $service 'HEAD:refs/heads/main' }
$channel = ((Get-Content release/channel.properties | Where-Object { $_.StartsWith('manifestUrl=') }) -replace '^manifestUrl=', '')
try {
    $published = Invoke-RestMethod -Uri $channel -NoProxy -TimeoutSec 30
    if ($published.tag_name -ne $tag -or $published.prerelease) { throw '版本不符' }
    $publishedManifest = $published.assets | Where-Object { $_.name -eq 'latest.json' } | Select-Object -First 1
    Verify-PublicDownload $publishedManifest.browser_download_url (Get-FileHash $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant() (Get-Item $manifestPath).Length
} catch { throw '公开发行接口校验失败，请检查更新通道' }
Write-Output "podor $version 已同步发布，应用更新通道已验证"
} catch {
    $failure = $_
    if ($createdGiteeRelease) {
        try { Undo-NewGiteeRelease $createdGiteeRelease $tag }
        catch { Write-Warning '未完成的 Gitee 发行记录清理失败，请检查更新入口' }
    }
    throw $failure
}
