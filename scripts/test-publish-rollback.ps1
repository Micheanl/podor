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
Write-Output 'Publish rollback passed; no remote requests were sent.'
