$ErrorActionPreference = 'Stop'
$file = Join-Path $PSScriptRoot 'publish.ps1'
$syntaxErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($file, [ref]$null, [ref]$syntaxErrors)
if ($syntaxErrors) { throw '发布脚本语法无效' }
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
Write-Output 'Publish rollback passed; no remote requests were sent.'
