param(
    [Parameter(Mandatory = $true)]
    [string]$SourceDir,

    [Parameter(Mandatory = $true)]
    [string]$OutputFile
)

$resolvedSource = Resolve-Path -LiteralPath $SourceDir
$manifest = Join-Path $resolvedSource "manifest.json"
if (-not (Test-Path -LiteralPath $manifest)) {
    throw "manifest.json not found in $resolvedSource"
}

$parent = Split-Path -Parent $OutputFile
if ($parent -and -not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}

$tempZip = if ($OutputFile.EndsWith(".zip")) { $OutputFile } else { "$OutputFile.zip" }
if (Test-Path -LiteralPath $tempZip) {
    Remove-Item -LiteralPath $tempZip -Force
}

Compress-Archive -Path (Join-Path $resolvedSource "*") -DestinationPath $tempZip -Force

if ($tempZip -ne $OutputFile) {
    if (Test-Path -LiteralPath $OutputFile) {
        Remove-Item -LiteralPath $OutputFile -Force
    }
    Move-Item -LiteralPath $tempZip -Destination $OutputFile
}

Write-Host "Created $OutputFile"
