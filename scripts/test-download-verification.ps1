$ErrorActionPreference='Stop'
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'publish.ps1'),[ref]$null,[ref]$null)
$definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Verify-PublicDownload'},$true).Extent.Text
Add-Type -TypeDefinition @"
using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
public sealed class DownloadFixtureHandler : HttpClientHandler {
 public Stream Body;
 protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
  return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { RequestMessage=request, Content=new StreamContent(Body) });
 }
}
public sealed class StalledDownload : MemoryStream {
 public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken token) {
  await Task.Delay(Timeout.Infinite, token); return 0;
 }
}
"@
$definition=$definition.Replace('$handler = [Net.Http.HttpClientHandler]::new()','$handler = $script:fixture').Replace('FromSeconds(30)','FromMilliseconds(50)')
. ([scriptblock]::Create($definition))
$bytes=[Text.Encoding]::UTF8.GetBytes('podor-release-test')
$sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
function Fixture([IO.Stream]$stream) { $script:fixture=[DownloadFixtureHandler]::new(); $script:fixture.Body=$stream }
Fixture ([IO.MemoryStream]::new($bytes))
Verify-PublicDownload 'https://example.invalid/test' $sha $bytes.Length
foreach ($case in @('hash','size','stall')) {
 if ($case -eq 'stall') {Fixture ([StalledDownload]::new())} else {Fixture ([IO.MemoryStream]::new($bytes))}
 $failed=$false
 $watch=[Diagnostics.Stopwatch]::StartNew()
 try { Verify-PublicDownload 'https://example.invalid/test' $(if($case -eq 'hash') {'0'*64} else {$sha}) $(if($case -eq 'size') {1} else {$bytes.Length}) } catch {$failed=$_.Exception.Message -eq '匿名直连下载校验失败，更新通道不会发布'}
 if (-not $failed) {throw "Missing rejection: $case"}
 if ($case -eq 'stall' -and $watch.Elapsed.TotalSeconds -gt 3) {throw 'Stalled read did not cancel'}
}
'Integrity, size and stalled read cancellation passed; no network requests.'
