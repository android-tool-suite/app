[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'help',
        'status',
        'list-plugins',
        'import-plugin',
        'export-plugin',
        'delete-plugin',
        'set-plugin-enabled',
        'set-widget-visible',
        'navigate',
        'reset-state'
    )]
    [string]$Command,

    [string]$Plugin,
    [string]$Widget,
    [string]$Destination,
    [string]$Path,
    [string]$PluginFile,
    [string]$OutputFile,
    [bool]$Enabled,
    [bool]$Visible,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.example.shizukuaccessibilitygrant'
$action = "$packageName.DEBUG_COMMAND"
$component = "$packageName/.debug.DebugCommandReceiver"

function Find-Adb {
    $installed = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $installed) {
        return $installed.Source
    }

    $localProperties = Join-Path $PSScriptRoot '..\local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -like 'sdk.dir=*' } |
            Select-Object -First 1
        if ($sdkLine) {
            $sdk = $sdkLine.Substring('sdk.dir='.Length).Replace('/', '\')
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate) {
                return $candidate
            }
        }
    }

    throw '找不到 adb。请把 Android SDK platform-tools 加入 PATH，或在 local.properties 配置 sdk.dir。'
}

function Invoke-Adb([string[]]$Arguments) {
    $prefix = @()
    if ($Serial) {
        $prefix += @('-s', $Serial)
    }
    $output = & $script:adb @prefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($output -join [Environment]::NewLine)
    }
    return @($output)
}

function Require-Value([string]$Name, $Value) {
    if ($null -eq $Value -or ($Value -is [string] -and [string]::IsNullOrWhiteSpace($Value))) {
        throw "命令 $Command 缺少参数 -$Name"
    }
}

function Boolean-Text([bool]$Value) {
    return $Value.ToString().ToLowerInvariant()
}

$adb = Find-Adb
$exportTarget = $null
$exportInboxName = $null

if ($Command -eq 'navigate') {
    Require-Value 'Destination' $Destination
    if ($Destination -notin @('dashboard', 'plugins', 'manager') -and -not $Destination.StartsWith('plugin:')) {
        throw '-Destination 必须是 dashboard、plugins、manager 或 plugin:<id>'
    }
    Invoke-Adb @(
        'shell', 'am', 'start', '-W',
        '-n', "$packageName/.host.MainActivity",
        '--es', 'debug_destination', $Destination
    )
    exit 0
}

$extras = @()
switch ($Command) {
    'import-plugin' {
        if ($PluginFile) {
            $resolvedPlugin = (Resolve-Path -LiteralPath $PluginFile).Path
            $inboxName = [IO.Path]::GetFileName($resolvedPlugin)
            if ([string]::IsNullOrWhiteSpace($inboxName)) {
                throw '无法从 -PluginFile 得到文件名'
            }
            Invoke-Adb @('push', $resolvedPlugin, "/data/local/tmp/$inboxName") | Out-Null
            Invoke-Adb @('shell', 'run-as', $packageName, 'mkdir', '-p', 'files/debug-inbox') | Out-Null
            Invoke-Adb @(
                'shell', 'run-as', $packageName, 'cp',
                "/data/local/tmp/$inboxName",
                "files/debug-inbox/$inboxName"
            ) | Out-Null
            $Path = $inboxName
        }
        Require-Value 'Path or -PluginFile' $Path
        $extras += @('--es', 'path', $Path)
    }
    'delete-plugin' {
        Require-Value 'Plugin' $Plugin
        $extras += @('--es', 'plugin', $Plugin)
    }
    'export-plugin' {
        Require-Value 'Plugin' $Plugin
        if (-not $OutputFile) {
            $OutputFile = Join-Path (Get-Location) "$Plugin.atsplugin"
        }
        $exportTarget = [IO.Path]::GetFullPath($OutputFile)
        $exportInboxName = [IO.Path]::GetFileName($exportTarget)
        if ([string]::IsNullOrWhiteSpace($exportInboxName)) {
            throw '无法从 -OutputFile 得到文件名'
        }
        $extras += @('--es', 'plugin', $Plugin, '--es', 'path', $exportInboxName)
    }
    'set-plugin-enabled' {
        Require-Value 'Plugin' $Plugin
        if (-not $PSBoundParameters.ContainsKey('Enabled')) {
            throw "命令 $Command 缺少参数 -Enabled"
        }
        $extras += @('--es', 'plugin', $Plugin, '--ez', 'enabled', (Boolean-Text $Enabled))
    }
    'set-widget-visible' {
        Require-Value 'Widget' $Widget
        if (-not $PSBoundParameters.ContainsKey('Visible')) {
            throw "命令 $Command 缺少参数 -Visible"
        }
        $extras += @('--es', 'widget', $Widget, '--ez', 'visible', (Boolean-Text $Visible))
    }
}

$output = Invoke-Adb (@(
    'shell', 'am', 'broadcast', '-W',
    '-a', $action,
    '-n', $component,
    '--es', 'command', $Command
) + $extras)

$completion = $output | Where-Object { $_ -match '^Broadcast completed:' } | Select-Object -Last 1
if (-not $completion -or $completion -notmatch 'data="(.*)"$') {
    throw ($output -join [Environment]::NewLine)
}

$response = $Matches[1] | ConvertFrom-Json
$response | ConvertTo-Json -Depth 20
if (-not $response.ok) {
    exit 2
}

if ($Command -eq 'export-plugin') {
    $targetParent = Split-Path -Parent $exportTarget
    if ($targetParent -and -not (Test-Path -LiteralPath $targetParent)) {
        New-Item -ItemType Directory -Path $targetParent -Force | Out-Null
    }
    Invoke-Adb @('pull', $response.data.devicePath, $exportTarget) | Out-Null
    Write-Output "Exported: $exportTarget"
}
