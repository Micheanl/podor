$ErrorActionPreference = 'Stop'
$file = Join-Path $PSScriptRoot 'publish.ps1'
$syntaxErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($file, [ref]$null, [ref]$syntaxErrors)
if ($syntaxErrors) { throw '发布脚本语法无效' }
$historyCommands = $ast.FindAll({ param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'git' -and $node.CommandElements[1].Extent.Text -in @('add', 'commit', 'reset', 'rebase') }, $true)
if ($historyCommands.Count) { throw '发布附件不能修改源码提交历史' }
$definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Undo-NewGiteeRelease' }, $true)
. ([scriptblock]::Create($definition.Extent.Text))
$apis = @{ gitee = 'https://example.invalid/api' }
$calls = [Collections.Generic.List[string]]::new()
function Invoke-PublishApi($Service, $Method, $Url, [switch]$AllowMissing) {
    $calls.Add("$Method $Url")
    if ($Method -eq 'GET') { return $script:release }
}
$script:release = @{ tag_name = 'v0.2.17'; prerelease = $true }
$null = Undo-NewGiteeRelease 17 'v0.2.17'
if (($calls -join ',') -ne 'GET https://example.invalid/api/releases/17,DELETE https://example.invalid/api/releases/17') { throw '未撤下本次未完成发行' }
$calls.Clear()
$script:release.prerelease = $false
Undo-NewGiteeRelease 17 'v0.2.17'
if ($calls.Count -ne 1) { throw '不应删除已完成发行' }
$calls.Clear()
$script:release = $null
Undo-NewGiteeRelease 17 'v0.2.17'
if ($calls.Count -ne 1) { throw '不应删除不存在的发行' }
$calls.Clear()
$script:release = @{ tag_name = 'v0.2.16'; prerelease = $true }
$rejected = $false
try { Undo-NewGiteeRelease 17 'v0.2.17' } catch { $rejected = $true }
if (-not $rejected -or $calls.Count -ne 1) { throw '不应删除其他版本' }
$definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Push-Repository' }, $true)
. ([scriptblock]::Create($definition.Extent.Text))
$config = @{ github = 'example/test' }
$tokens = @{ github = '<TOKEN>' }
$originalHeader = $env:GIT_CONFIG_VALUE_0
$originalCount = $env:GIT_CONFIG_COUNT
function git {
    Write-Output 'Set-Cookie: <COOKIE>'
    Write-Output 'Authorization: <TOKEN>'
    $global:LASTEXITCODE = $script:pushExit
}
$script:pushExit = 0
$pushOutput = @(Push-Repository github 'HEAD:refs/heads/main') -join "`n"
if ($pushOutput -ne 'github 代码同步完成') { throw '同步日志不应包含 HTTP 响应头' }
$script:pushExit = 1
$rejected = $false
try { Push-Repository github 'HEAD:refs/heads/main' } catch { $rejected = $_.Exception.Message -eq 'github 代码同步失败' }
if (-not $rejected) { throw '同步失败时提示不正确' }
if ($env:GIT_CONFIG_VALUE_0 -ne $originalHeader) { throw '同步失败后没有还原请求头环境变量' }
if ($env:GIT_CONFIG_COUNT -ne $originalCount) { throw '同步失败后没有还原配置数量' }
$global:LASTEXITCODE = 0
$definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Restore-PendingRelease' }, $true)
. ([scriptblock]::Create($definition.Extent.Text))
$publishTry = $ast.EndBlock.Statements | Where-Object { $_ -is [Management.Automation.Language.TryStatementAst] } | Select-Object -Last 1
$loops = @($publishTry.Body.Statements | Where-Object { $_ -is [Management.Automation.Language.ForEachStatementAst] })
$latestCheck = $publishTry.Body.Statements | Where-Object { $_.Extent.Text.StartsWith('$latestGithub =') } | Select-Object -First 1
$workflow = [scriptblock]::Create("$($loops[0].Extent.Text)`n$($loops[-1].Extent.Text)`n$($latestCheck.Extent.Text)")
$catchBody = $publishTry.CatchClauses[0].Body.Extent.Text
$recovery = [scriptblock]::Create($catchBody.Substring(1, $catchBody.Length - 2))
function Invoke-PublishApi($Service, $Method, $Url, $Body = $null, $File = $null, [switch]$AllowMissing) {
    $script:apiCalls.Add("$Service $Method $Url")
    if ($Url.EndsWith('/releases/latest')) { throw 'final channel verification failed' }
    if ($Method -eq 'POST') {
        $script:releaseStates[$Service] = @{ id = 17; tag_name = $Body.tag_name; prerelease = $true }
    }
    if ($Method -eq 'PATCH') { $script:releaseStates[$Service].prerelease = $Body.prerelease }
    if ($Method -eq 'DELETE') { $script:releaseStates[$Service] = $null; return }
    if ($Url.EndsWith('/assets') -or $Url.EndsWith('/attach_files')) {
        return @{ name = 'podor-0.4.0.msi'; browser_download_url = 'https://download.example.invalid/podor-0.4.0.msi' }
    }
    return $script:releaseStates[$Service]
}
function Verify-PublicDownload($Url, $ExpectedHash, $ExpectedSize) {}
function Test-FinalVerificationRollback($GithubPrerelease, $GiteePrerelease, [switch]$NewGitee) {
    $apis = @{ github = 'https://example.invalid/github'; gitee = 'https://example.invalid/gitee' }
    $script:apiCalls = [Collections.Generic.List[string]]::new()
    $script:releaseStates = @{
        github = @{ id = 41; tag_name = 'v0.4.0'; prerelease = $GithubPrerelease }
        gitee = if ($NewGitee) { $null } else { @{ id = 17; tag_name = 'v0.4.0'; prerelease = $GiteePrerelease } }
    }
    $version = '0.4.0'
    $tag = 'v0.4.0'
    $sha = 'test-source'
    $notes = 'test release'
    $installer = 'test.msi'
    $installerHash = 'test-hash'
    $installerSize = 1
    $releaseIds = @{}
    $pendingReleaseIds = @{}
    $assets = @{}
    $createdGiteeRelease = $null
    $failureMessage = $null
    try { . $workflow | Out-Null } catch {
        try { . $recovery | Out-Null } catch { $failureMessage = $_.Exception.Message }
    }
    if ($failureMessage -ne 'final channel verification failed') { throw '最终验证失败应保留原始错误' }
    if ($script:releaseStates.github.prerelease -ne $GithubPrerelease) { throw 'GitHub 最终验证失败后没有恢复原始预发布状态' }
    if ($NewGitee) {
        if ($script:releaseStates.gitee) { throw '本次新建 Gitee 发行应在回退后清理' }
    } elseif ($script:releaseStates.gitee.prerelease -ne $GiteePrerelease) { throw 'Gitee 最终验证失败后没有恢复原始预发布状态' }
    foreach ($service in @('github', 'gitee')) {
        $initiallyPending = if ($service -eq 'github') { $GithubPrerelease } else { $GiteePrerelease -or $NewGitee }
        $patches = @($script:apiCalls | Where-Object { $_ -eq "$service PATCH $($apis[$service])/releases/$($releaseIds[$service])" })
        if ($patches.Count -ne $(if ($initiallyPending) { 2 } else { 1 })) { throw '已稳定的同版本重试不应被降级' }
    }
}
Test-FinalVerificationRollback $true $true
Test-FinalVerificationRollback $false $false
Test-FinalVerificationRollback $true $false
Test-FinalVerificationRollback $false $true
Test-FinalVerificationRollback $true $true -NewGitee
$definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-AdditionalPublishFiles' }, $true)
. ([scriptblock]::Create($definition.Extent.Text))
if (@(Get-AdditionalPublishFiles @() 'podor-0.4.0.msi').Count) { throw '无额外附件时应返回空列表' }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $file = Join-Path $temporary 'podor-0.4.0.dmg'
    [IO.File]::WriteAllText($file, 'test')
    $files = @(Get-AdditionalPublishFiles @($file) 'podor-0.4.0.msi')
    if ($files.Count -ne 1 -or $files[0].FullName -ne $file) { throw '额外附件未保留实际文件' }
    foreach ($paths in @(@($file, $file), @($temporary))) {
        $rejected = $false
        try { Get-AdditionalPublishFiles $paths 'podor-0.4.0.msi' | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw '重复附件和目录应在发布前拒绝' }
    }
    $rejected = $false
    try { Get-AdditionalPublishFiles @($file) 'podor-0.4.0.dmg' | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw '额外附件不能覆盖主安装包' }
} finally {
    Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temporary
}
Write-Output 'Publish rollback passed; no remote requests were sent.'
