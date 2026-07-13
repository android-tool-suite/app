# Android 模拟器与 ADB 调试

## 推荐环境

使用 Android Studio 自带的 Android Emulator。它直接使用 Android SDK 系统镜像，ADB、权限模型、`am`、`pm`、`uiautomator` 等行为最接近标准 Android，也适合无窗口自动化测试。

本项目要求 Android 7.0（API 24）以上。日常回归建议至少保留两个 AVD：

- API 24：验证最低系统版本。
- 最新稳定 API：验证目标系统的后台启动、广播和存储限制。

本机已有 AVD 时，可这样启动带完整手机界面的模拟器：

```powershell
$sdk = 'C:\Users\19635\AppData\Local\Android\Sdk'
& "$sdk\emulator\emulator.exe" -list-avds
& "$sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1
```

## 图形界面与 ADB 并行调试

图形界面和 ADB 可以同时使用。Emulator 窗口用于观察页面、手动点击和处理授权弹窗；ADB 用于安装、改变应用状态、抓取日志、读取 UI 树和执行可重复的自动化步骤。两者连接的是同一个虚拟设备，互不冲突。

启动图形界面时不要添加 `-no-window`：

```powershell
$sdk = 'C:\Users\19635\AppData\Local\Android\Sdk'
$adb = "$sdk\platform-tools\adb.exe"

& "$sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1
```

模拟器进入桌面后，在另一个 PowerShell 窗口确认 ADB 已连接：

```powershell
& $adb devices -l
& $adb shell getprop sys.boot_completed
```

`sys.boot_completed` 返回 `1` 后即可安装和调试。一个典型的图形化调试流程如下：

```powershell
# 安装最新 debug APK
& $adb install -r -t .\app\build\outputs\apk\debug\app-debug.apk

# 在模拟器窗口中打开插件管理页
.\tools\adb-debug.ps1 -Command navigate -Destination manager

# 保持窗口可见，同时用 ADB 查询和修改状态
.\tools\adb-debug.ps1 -Command status
.\tools\adb-debug.ps1 -Command set-plugin-enabled -Plugin shizuku_auth -Enabled $true

# 观察实时日志；按 Ctrl+C 停止
$appPid = (& $adb shell pidof com.androidtoolsuite.app).Trim()
& $adb logcat "--pid=$appPid"
```

通过脚本改变插件、授权或主页组件状态时，已经打开的应用界面会自动刷新。也可以直接在模拟器窗口中点击；之后再运行 `status` 或 `list-plugins` 回读最终状态。

### 在图形模拟器中使用 Shizuku

图形模拟器可以像普通手机一样安装 Shizuku APK。先准备可信来源的 Shizuku APK，然后执行：

```powershell
& $adb install -r .\Shizuku.apk

# 首次打开 Shizuku；也可以在模拟器桌面手动点击图标
& $adb shell monkey -p moe.shizuku.privileged.api 1

# 首次打开后，以 ADB 身份启动 Shizuku 服务
& $adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

回到 Shizuku 图形界面确认服务已启动，再打开本项目应用完成授权。可用下面的命令回读连接状态：

```powershell
.\tools\adb-debug.ps1 -Command status
```

应看到 `shizukuReady` 和 `shizukuPermission` 的状态；首次授权通常需要在模拟器窗口中确认。模拟器完全重启后，通常需要重新执行 Shizuku 的启动脚本。

如果同时连接了多个模拟器或实体设备，先从 `adb devices -l` 找到序列号，然后给项目脚本增加 `-Serial emulator-5554`，或给原始 ADB 命令增加 `-s emulator-5554`。

CI 或纯命令行测试使用无窗口模式：

```powershell
& "$sdk\emulator\emulator.exe" -avd Medium_Phone_API_36.1 `
  -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save
& "$sdk\platform-tools\adb.exe" wait-for-device
```

`-no-window` 只隐藏图形窗口，不会关闭或限制 ADB；图形模式和无窗口模式使用完全相同的构建、安装和调试命令。

## 构建和安装

ADB 调试入口只存在于 `debug` 构建，release APK 不包含该 Receiver。

```powershell
gradle :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 调试命令

推荐使用项目脚本；它仍然只调用 ADB，并会输出格式化 JSON：

```powershell
.\tools\adb-debug.ps1 -Command help
.\tools\adb-debug.ps1 -Command status
.\tools\adb-debug.ps1 -Command list-plugins
```

可用操作：

| 命令 | 参数 | 作用 |
| --- | --- | --- |
| `help` | 无 | 返回协议和命令列表 |
| `status` | 无 | 返回版本、SDK、Shizuku、组件和插件状态 |
| `list-plugins` | 无 | 列出内置/外部插件、依赖和活动状态 |
| `import-plugin` | `-PluginFile <本机文件>`，或 `-Path <收件箱相对路径>` | 导入包含清单和 APK 的完整 `.atsplugin`，默认停用 |
| `export-plugin` | `-Plugin <id> [-OutputFile <本机文件>]` | 导出外部插件包并通过 ADB 拉取到电脑 |
| `delete-plugin` | `-Plugin <id>` | 删除外部插件；有已启用依赖方时拒绝 |
| `set-plugin-enabled` | `-Plugin <id> -Enabled $true/$false` | 启停插件并校验依赖 |
| `set-widget-visible` | `-Widget <plugin:id> -Visible $true/$false` | 显示或隐藏主页组件 |
| `navigate` | `-Destination dashboard/plugins/manager/plugin:<id>` | 使用 `adb shell am start` 打开指定页面 |
| `reset-state` | 无 | 删除外部插件，停用可选内置插件并恢复组件显示状态 |

完整例子：

```powershell
.\tools\adb-debug.ps1 -Command import-plugin `
  -PluginFile ..\plugins\accessibility-grant\artifacts\accessibility-grant.atsplugin

.\tools\adb-debug.ps1 -Command set-plugin-enabled `
  -Plugin shizuku_auth -Enabled $true

.\tools\adb-debug.ps1 -Command export-plugin `
  -Plugin accessibility_grant -OutputFile .\artifacts\accessibility-grant-debug.atsplugin

.\tools\adb-debug.ps1 -Command navigate -Destination manager
```

有多个设备时，通过 `-Serial emulator-5554` 指定目标。

## 原始 ADB 协议

不使用脚本时，显式广播 debug Receiver。响应位于 `Broadcast completed` 的 `data` 字段，成功时 `result=-1`、`ok=true`，失败时 `result=0`、`ok=false`。

```powershell
$pkg = 'com.androidtoolsuite.app'
adb shell am broadcast -W `
  -a "$pkg.DEBUG_COMMAND" `
  -n "$pkg/.debug.DebugCommandReceiver" `
  --es command status
```

布尔参数必须使用 `--ez`，字符串参数使用 `--es`：

```powershell
adb shell am broadcast -W `
  -a "$pkg.DEBUG_COMMAND" `
  -n "$pkg/.debug.DebugCommandReceiver" `
  --es command set-plugin-enabled `
  --es plugin shizuku_auth `
  --ez enabled true
```

导入二进制插件前，先把文件放入应用私有的调试收件箱：

```powershell
adb push .\plugin.atsplugin /data/local/tmp/plugin.atsplugin
adb shell run-as $pkg mkdir -p files/debug-inbox
adb shell run-as $pkg cp /data/local/tmp/plugin.atsplugin files/debug-inbox/plugin.atsplugin
```

原始协议导出时，Receiver 写入应用专属外部目录；从响应的 `devicePath` 读取实际路径并拉取：

```powershell
adb shell am broadcast -W `
  -a "$pkg.DEBUG_COMMAND" `
  -n "$pkg/.debug.DebugCommandReceiver" `
  --es command export-plugin --es plugin accessibility_grant --es path plugin.atsplugin
adb pull /sdcard/Android/data/$pkg/files/debug-outbox/plugin.atsplugin .\plugin.atsplugin
```

打开指定页面直接使用 shell 身份启动 Activity，以兼容 Android 16 的后台启动限制：

```powershell
adb shell am start -W -n "$pkg/.host.MainActivity" --es debug_destination manager
adb shell am start -W -n "$pkg/.host.MainActivity" --es debug_destination plugin:accessibility_grant
```

## ADB 原生调试操作

应用接口负责内部状态；设备级操作继续使用标准 ADB：

```powershell
# 完整清空应用数据
adb shell pm clear com.androidtoolsuite.app

# 查看日志和崩溃
adb logcat --pid=$(adb shell pidof com.androidtoolsuite.app)

# 获取 UI 树
adb shell uiautomator dump /sdcard/window.xml
adb shell cat /sdcard/window.xml

# 输入、点击、滑动和返回
adb shell input text test
adb shell input tap 500 1200
adb shell input swipe 500 1800 500 500 300
adb shell input keyevent BACK

# 截图
adb exec-out screencap -p > screenshot.png
```

## 安全边界

- Receiver 位于 `app/src/debug`，不会进入 release 构建。
- Receiver 要求系统 `android.permission.DUMP`；ADB shell 可调用，普通第三方应用不能调用。
- 插件导入只接受应用私有 `files/debug-inbox` 下的相对路径，并限制为 64 MiB；导出只写应用专属外部目录。
- 插件与宿主运行在同一进程，不存在可靠的插件级权限隔离；调试环境也只应导入可信插件。
- 不提供任意命令执行接口；shell、文件和设备控制直接由 ADB 完成。
