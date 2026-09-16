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
        'list-permissions',
        'set-permission',
        'run-task',
        'last-task-run',
        'list-datasets',
        'verify-datasets',
        'inspect-backup',
        'restore-datasets',
        'set-widget-visible',
        'navigate',
        'reset-state',
        'set-dev-server',
        'clear-dev-server'
    )]
    [string]$Command,

    [string]$Plugin,
    [string]$Capability,
    [string]$Task,
    [string]$Widget,
    [string]$Destination,
    [string]$Path,
    [string]$PluginFile,
    [string]$BackupFile,
    [string[]]$DatasetKeys,
    [string]$PasswordFile,
    [string]$OutputFile,
    [string]$DevUrl,
    [bool]$Enabled,
    [bool]$Visible,
    [switch]$ReplaceSameVersion,
    [switch]$AllowFailure,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'

# adb 的输出是 UTF-8，而 PowerShell 默认按系统代码页解码（中文 Windows 是 GBK）。
# 不改的话，响应里任何中文都会被解坏：多字节序列错位时会把紧随其后的引号一起吃掉，
# 于是 data="{...}" 里的 JSON 变成非法，ConvertFrom-Json 直接失败。
# 插件标题几乎都是中文，所以 status / list-plugins / import-plugin 全都会挂。
$previousOutputEncoding = [Console]::OutputEncoding
$uploadedBackupName = $null
$uploadedPasswordName = $null
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {

$packageName = 'com.androidtoolsuite.app.debug'
$action = "$packageName.DEBUG_COMMAND"
$component = "$packageName/com.androidtoolsuite.app.debug.DebugCommandReceiver"
$mainActivity = 'com.androidtoolsuite.app.host.MainActivity'

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
    if ($Destination -notin @('dashboard', 'plugins', 'manager', 'store', 'settings', 'about') -and -not $Destination.StartsWith('plugin:')) {
        throw '-Destination 必须是 dashboard、plugins、manager、store、settings、about 或 plugin:<id>'
    }
    Invoke-Adb @(
        'shell', 'am', 'start', '-S', '-W',
        '-n', "$packageName/$mainActivity",
        '--es', 'debug_destination', $Destination
    )
    return
}

$extras = @()
switch ($Command) {
    'verify-datasets' {
        Require-Value 'Plugin' $Plugin
        $extras += @('--es', 'plugin', $Plugin)
    }
    'list-datasets' {
        if ($Plugin) { $extras += @('--es', 'plugin', $Plugin) }
    }
    { $_ -in @('inspect-backup', 'restore-datasets') } {
        if ($BackupFile) {
            $resolvedBackup = (Resolve-Path -LiteralPath $BackupFile).Path
            $inboxName = "backup-$([Guid]::NewGuid().ToString('N')).atsbackup"
            Invoke-Adb @('push', $resolvedBackup, "/data/local/tmp/$inboxName") | Out-Null
            try {
                Invoke-Adb @('shell', 'run-as', $packageName, 'mkdir', '-p', 'files/debug-inbox') | Out-Null
                Invoke-Adb @('shell', 'run-as', $packageName, 'cp', "/data/local/tmp/$inboxName", "files/debug-inbox/$inboxName") | Out-Null
            } finally {
                Invoke-Adb @('shell', 'rm', '-f', "/data/local/tmp/$inboxName") | Out-Null
            }
            $Path = $inboxName
            $uploadedBackupName = $inboxName
        }
        Require-Value 'Path or -BackupFile' $Path
        $extras += @('--es', 'path', $Path)
        if ($Command -eq 'restore-datasets') {
            if (-not $DatasetKeys -or $DatasetKeys.Count -eq 0) { throw 'restore-datasets 必须显式提供 -DatasetKeys' }
            $extras += @('--es', 'keys', ($DatasetKeys -join ','))
            if ($PasswordFile) {
                $resolvedPassword = (Resolve-Path -LiteralPath $PasswordFile).Path
                $passwordName = "password-$([Guid]::NewGuid().ToString('N'))"
                Invoke-Adb @('push', $resolvedPassword, "/data/local/tmp/$passwordName") | Out-Null
                try {
                    Invoke-Adb @('shell', 'run-as', $packageName, 'cp', "/data/local/tmp/$passwordName", "files/debug-inbox/$passwordName") | Out-Null
                } finally {
                    Invoke-Adb @('shell', 'rm', '-f', "/data/local/tmp/$passwordName") | Out-Null
                }
                $extras += @('--es', 'password_path', $passwordName)
                $uploadedPasswordName = $passwordName
            }
        }
    }
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
        if ($ReplaceSameVersion) {
            $extras += @('--ez', 'replace_same_version', 'true')
        }
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
    'list-permissions' {
        Require-Value 'Plugin' $Plugin
        $extras += @('--es', 'plugin', $Plugin)
    }
    'set-permission' {
        Require-Value 'Plugin' $Plugin
        Require-Value 'Capability' $Capability
        if (-not $PSBoundParameters.ContainsKey('Enabled')) {
            throw "命令 $Command 缺少参数 -Enabled"
        }
        $extras += @(
            '--es', 'plugin', $Plugin,
            '--es', 'capability', $Capability,
            '--ez', 'enabled', (Boolean-Text $Enabled)
        )
    }
    { $_ -in @('run-task', 'last-task-run') } {
        Require-Value 'Plugin' $Plugin
        Require-Value 'Task' $Task
        $extras += @('--es', 'plugin', $Plugin, '--es', 'task', $Task)
    }
    'set-widget-visible' {
        Require-Value 'Widget' $Widget
        if (-not $PSBoundParameters.ContainsKey('Visible')) {
            throw "命令 $Command 缺少参数 -Visible"
        }
        $extras += @('--es', 'widget', $Widget, '--ez', 'visible', (Boolean-Text $Visible))
    }
    'set-dev-server' {
        Require-Value 'Plugin' $Plugin
        Require-Value 'DevUrl' $DevUrl
        $extras += @('--es', 'plugin', $Plugin, '--es', 'url', $DevUrl)
    }
    'clear-dev-server' {
        Require-Value 'Plugin' $Plugin
        $extras += @('--es', 'plugin', $Plugin)
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
    if ($AllowFailure) {
        return
    }
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

}
finally {
    foreach ($temporaryName in @($uploadedBackupName, $uploadedPasswordName)) {
        if ($temporaryName) {
            Invoke-Adb @('shell', 'run-as', $packageName, 'rm', '-f', "files/debug-inbox/$temporaryName") | Out-Null
        }
    }
    # 直接 .\adb-debug.ps1 在交互式会话里跑时，不要把改过的编码留给后续命令。
    [Console]::OutputEncoding = $previousOutputEncoding
}
