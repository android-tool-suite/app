[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$appRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $appRoot
$adbDebug = Join-Path $PSScriptRoot 'adb-debug.ps1'
$sampleRoot = Join-Path $appRoot 'examples\plugins\background-summary'
$samplePackage = Join-Path $workspaceRoot 'temp\background-summary-regression.atsplugin'
$webSampleRoot = Join-Path $appRoot 'examples\plugins\hello-web'
$webSamplePackage = Join-Path $workspaceRoot 'temp\hello-web-regression.atsplugin'
$hostApk = Join-Path $appRoot 'artifacts\android-tool-suite-debug.apk'
$packageName = 'com.androidtoolsuite.app.debug'
$sampleId = 'sample.background_summary'
$webSampleId = 'sample.hello_web'
$taskId = 'manual-probe'

function Invoke-Debug([string]$Command, [hashtable]$Arguments = @{}) {
    $parameters = @{ Command = $Command }
    if ($Serial) { $parameters.Serial = $Serial }
    foreach ($entry in $Arguments.GetEnumerator()) { $parameters[$entry.Key] = $entry.Value }
    $raw = & $adbDebug @parameters
    if ($Command -eq 'navigate') { return }
    return ($raw -join [Environment]::NewLine | ConvertFrom-Json)
}

function Invoke-Adb([string[]]$Arguments) {
    $prefix = @()
    if ($Serial) { $prefix += @('-s', $Serial) }
    $output = & adb @prefix @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($output -join [Environment]::NewLine) }
    return @($output)
}

function Wait-TaskTerminal([string]$Task, [string]$RunId, [int]$Attempts = 60) {
    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $response = Invoke-Debug 'last-task-run' @{ Plugin = $sampleId; Task = $Task }
        $status = [string]$response.data.status
        if ($response.data.runId -eq $RunId -and $status -in @('succeeded', 'failed', 'cancelled', 'coalesced')) {
            return $response.data
        }
        Start-Sleep -Milliseconds 500
    }
    throw "后台任务 $Task 没有在限定时间内进入终态"
}

function Assert-HostRunning {
    $hostPid = (Invoke-Adb @('shell', 'pidof', $packageName) | Select-Object -Last 1).Trim()
    if (-not $hostPid) { throw '宿主进程未在生命周期操作后恢复' }
}

function Wait-UiText([string]$Expected) {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/ats-runtime-regression.xml') | Out-Null
        $xml = Invoke-Adb @('shell', 'cat', '/sdcard/ats-runtime-regression.xml')
        if (($xml -join [Environment]::NewLine).Contains($Expected)) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "界面在 10 秒内没有出现：$Expected"
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw '找不到 adb' }
if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $hostApk)) { throw "缺少集中 APK：$hostApk" }
    Invoke-Adb @('install', '--no-streaming', '-r', '-t', $hostApk) | Out-Null
}
Invoke-Adb @('logcat', '-c') | Out-Null

$python = (Get-Command python -ErrorAction Stop).Source
New-Item -ItemType Directory -Path (Split-Path -Parent $samplePackage) -Force | Out-Null
& $python (Join-Path $appRoot 'tools\plugin\ats.py') pack $sampleRoot --output $samplePackage
if ($LASTEXITCODE -ne 0) { throw '后台任务示例打包失败' }
& $python (Join-Path $appRoot 'tools\plugin\ats.py') pack $webSampleRoot --output $webSamplePackage
if ($LASTEXITCODE -ne 0) { throw 'WebView 示例打包失败' }

$originalRotation = (Invoke-Adb @('shell', 'settings', 'get', 'system', 'user_rotation') | Select-Object -Last 1).Trim()
$originalAccelerometer = (Invoke-Adb @('shell', 'settings', 'get', 'system', 'accelerometer_rotation') | Select-Object -Last 1).Trim()
try {
    Invoke-Debug 'import-plugin' @{ PluginFile = $samplePackage; ReplaceSameVersion = $true } | Out-Null
    Invoke-Debug 'import-plugin' @{ PluginFile = $webSamplePackage; ReplaceSameVersion = $true } | Out-Null
    Invoke-Debug 'set-plugin-enabled' @{ Plugin = $sampleId; Enabled = $true } | Out-Null
    Invoke-Debug 'set-plugin-enabled' @{ Plugin = $webSampleId; Enabled = $true } | Out-Null
    Invoke-Debug 'set-permission' @{ Plugin = $sampleId; Capability = 'scheduler'; Enabled = $true } | Out-Null

    $queued = Invoke-Debug 'run-task' @{ Plugin = $sampleId; Task = $taskId }
    $terminal = Wait-TaskTerminal $taskId $queued.data.runId
    if ($terminal.status -ne 'succeeded') { throw "后台任务未成功：$($terminal.status)" }
    if (@($terminal.input.PSObject.Properties).Count -ne 0) { throw '回归任务意外记录了输入载荷' }

    $timeoutQueued = Invoke-Debug 'run-task' @{ Plugin = $sampleId; Task = 'timeout-probe' }
    $timeoutRun = Wait-TaskTerminal 'timeout-probe' $timeoutQueued.data.runId
    if ($timeoutRun.status -ne 'failed' -or $timeoutRun.error.code -ne 'TIMEOUT') {
        throw '超时任务没有以 TIMEOUT 终止'
    }
    $retryQueued = Invoke-Debug 'run-task' @{ Plugin = $sampleId; Task = 'retry-probe' }
    $retryRun = Wait-TaskTerminal 'retry-probe' $retryQueued.data.runId 90
    if ($retryRun.status -ne 'failed' -or [int]$retryRun.attempt -ne 2) {
        throw '可重试任务没有在第二次尝试后按上限终止'
    }

    $heapQueued = Invoke-Debug 'run-task' @{ Plugin = $sampleId; Task = 'heap-limit-probe' }
    $heapRun = Wait-TaskTerminal 'heap-limit-probe' $heapQueued.data.runId
    if ($heapRun.status -ne 'failed' -or $heapRun.error.code -ne 'RESOURCE_LIMIT') {
        throw '堆限制任务没有以 RESOURCE_LIMIT 终止'
    }
    $recoveryQueued = Invoke-Debug 'run-task' @{ Plugin = $sampleId; Task = $taskId }
    $recoveryRun = Wait-TaskTerminal $taskId $recoveryQueued.data.runId
    if ($recoveryRun.status -ne 'succeeded') {
        throw 'JavaScriptSandbox 在堆限制终止后没有恢复'
    }

    Invoke-Debug 'navigate' @{ Destination = "plugin:$webSampleId" } | Out-Null
    Assert-HostRunning
    Invoke-Adb @('shell', 'input', 'keyevent', 'HOME') | Out-Null
    Invoke-Debug 'navigate' @{ Destination = "plugin:$webSampleId" } | Out-Null
    Assert-HostRunning
    Invoke-Adb @('shell', 'am', 'force-stop', $packageName) | Out-Null
    Invoke-Debug 'navigate' @{ Destination = "plugin:$webSampleId" } | Out-Null
    Assert-HostRunning

    Invoke-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '1') | Out-Null
    Invoke-Debug 'navigate' @{ Destination = "plugin:$webSampleId" } | Out-Null
    Assert-HostRunning
    Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null

    Invoke-Debug 'navigate' @{ Destination = "plugin:$sampleId" } | Out-Null
    Assert-HostRunning

    Invoke-Debug 'set-permission' @{ Plugin = $sampleId; Capability = 'scheduler'; Enabled = $false } | Out-Null
    $denied = Invoke-Debug 'run-task' @{
        Plugin = $sampleId
        Task = $taskId
        AllowFailure = $true
    }
    if ($denied.ok) { throw '撤销后台运行权限后任务仍可启动' }
    Invoke-Debug 'navigate' @{ Destination = "plugin:$sampleId" } | Out-Null
    Assert-HostRunning
    Invoke-Debug 'set-permission' @{ Plugin = $sampleId; Capability = 'scheduler'; Enabled = $true } | Out-Null
    Invoke-Debug 'navigate' @{ Destination = "plugin:$sampleId" } | Out-Null
    Assert-HostRunning

    $shizuku = (Invoke-Debug 'list-plugins').data.plugins | Where-Object id -eq 'shizuku_auth'
    if ($shizuku) {
        $originalShizukuEnabled = [bool]$shizuku.enabled
        Invoke-Debug 'set-plugin-enabled' @{ Plugin = 'shizuku_auth'; Enabled = $false } | Out-Null
        Invoke-Debug 'set-plugin-enabled' @{ Plugin = 'shizuku_auth'; Enabled = $true } | Out-Null
        Invoke-Adb @('shell', 'am', 'force-stop', $packageName) | Out-Null
        Invoke-Debug 'navigate' @{ Destination = 'plugin:shizuku_auth' } | Out-Null
        Assert-HostRunning
        Wait-UiText 'Shizuku'
        if (-not $originalShizukuEnabled) {
            Invoke-Debug 'set-plugin-enabled' @{ Plugin = 'shizuku_auth'; Enabled = $false } | Out-Null
        }
    }

    $runtimeLog = Invoke-Adb @('logcat', '-d', '-t', '500')
    if (($runtimeLog -join [Environment]::NewLine) -match 'FATAL EXCEPTION.*com\.androidtoolsuite\.app\.debug') {
        throw 'Runtime 生命周期回归期间出现宿主崩溃'
    }

    [pscustomobject]@{
        hostRendererLifecycle = 'passed'
        webViewRendererLifecycle = 'passed'
        rotationRecreation = 'passed'
        permissionRevocation = 'passed'
        schedulerStatus = $terminal.status
        timeoutStatus = $timeoutRun.error.code
        retryAttempts = $retryRun.attempt
        sandboxRecovery = 'passed'
        providerReconnect = if ($shizuku) { 'exercised' } else { 'not-installed' }
    } | ConvertTo-Json
}
finally {
    if ($originalAccelerometer -match '^[01]$') {
        Invoke-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', $originalAccelerometer) | Out-Null
    }
    if ($originalRotation -match '^[0-3]$') {
        Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', $originalRotation) | Out-Null
    }
    try { Invoke-Debug 'delete-plugin' @{ Plugin = $sampleId } | Out-Null } catch {}
    try { Invoke-Debug 'delete-plugin' @{ Plugin = $webSampleId } | Out-Null } catch {}
    Remove-Item -LiteralPath $samplePackage -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $webSamplePackage -Force -ErrorAction SilentlyContinue
}
