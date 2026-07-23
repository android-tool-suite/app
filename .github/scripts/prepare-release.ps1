[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$ArtifactPath,
    [Parameter(Mandatory)]
    [string]$OutputDirectory,
    [Parameter(Mandatory)]
    [string]$ExpectedTag
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$buildFile = Join-Path $repositoryRoot 'app\build.gradle'
$changelogFile = Join-Path $repositoryRoot 'CHANGELOG.md'
$artifact = Get-Item -LiteralPath $ArtifactPath
$buildText = Get-Content -Raw -LiteralPath $buildFile

if ($buildText -notmatch 'versionName\s+"([^"]+)"') {
    throw '无法从 app/build.gradle 读取 versionName'
}
$versionName = $Matches[1]
if ($buildText -notmatch 'versionCode\s+(\d+)') {
    throw '无法从 app/build.gradle 读取 versionCode'
}
$versionCode = [int]$Matches[1]
if ($ExpectedTag -ne "v$versionName") {
    throw "标签 $ExpectedTag 与应用版本 $versionName 不一致"
}
if (-not (Select-String -LiteralPath $changelogFile -SimpleMatch "## $versionName" -Quiet)) {
    throw "CHANGELOG.md 缺少 $versionName"
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$targetArtifact = Join-Path $OutputDirectory 'android-tool-suite.apk'
Copy-Item -LiteralPath $artifact.FullName -Destination $targetArtifact -Force
$targetFile = Get-Item -LiteralPath $targetArtifact
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetArtifact).Hash.ToLowerInvariant()

$metadata = [ordered]@{
    schemaVersion = 1
    type = 'app'
    packageName = 'com.androidtoolsuite.app'
    versionName = $versionName
    versionCode = $versionCode
    minSdk = 24
    artifactName = $targetFile.Name
}
$metadata | ConvertTo-Json -Depth 6 |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'release-metadata.json') -Encoding utf8
"$hash  $($targetFile.Name)" |
    Set-Content -LiteralPath (Join-Path $OutputDirectory 'SHA256SUMS.txt') -Encoding ascii

Write-Host "Prepared app release $versionName ($versionCode): $targetArtifact"
