# Android 模拟器与 ADB 调试

## Dataset 恢复与诊断（仅 Debug）

以下命令从聚合工作区根目录执行；独立 app 仓库中把 `app/tools` 改为 `tools`。
无需系统文件选择器，可检查 v2/v3 归档和恢复已安装插件声明且兼容的 Dataset：

```powershell
.\app\tools\adb-debug.ps1 -Serial <serial> -Command list-datasets
.\app\tools\adb-debug.ps1 -Serial <serial> -Command verify-datasets -Plugin gacha_analysis
.\app\tools\adb-debug.ps1 -Serial <serial> -Command inspect-backup -BackupFile <本地归档路径>
.\app\tools\adb-debug.ps1 -Serial <serial> -Command restore-datasets `
  -BackupFile <本地归档路径> `
  -DatasetKeys 'gacha_analysis/gacha-settings','gacha_analysis/genshin-records','gacha_analysis/starrail-records'
```

`list-datasets -Plugin <id>` 可筛选插件，只返回存在状态、敏感标记和字节数，不输出内容。
`verify-datasets` 将普通 Dataset 的 128 KiB 分块读取结果与完整导出做摘要比较，仅返回是否一致、字节数与块数，跳过敏感 Dataset；适用于定位读取损坏，不代表业务内容语义验证。
`inspect-backup` 的 `restorable` 表示本 Debug Dataset 接口可恢复性；宿主设置、插件启用状态和插件包需使用对应应用流程，并非整个产品不支持。
`restore-datasets` 必须显式选择 keys，采用 REPLACE 语义，依赖项必须一起选择；未知、不兼容项目会在写入前报错。
归档先校验并暂存，再复用 MigrationBridgeManager 恢复，结果 `restored` 列出成功项目；不调用 `pm clear` 或删除插件。
加密归档可用 `-PasswordFile <本地 UTF-8 文件>`，文件内容为密码本身（无 BOM、无末尾换行）；设备临时密码文件读取后删除，密码不放在广播参数或响应中，本地密码文件由调用者保管。
`-BackupFile` 自动上传并在命令结束后删除设备临时副本，也可用 `-Path <debug-inbox 内相对路径>` 复用已上传归档。
原始 Download 备份不受影响。数据较大时应等待广播完成，勿并发执行恢复或插件更新。

## 推荐环境与模拟器

框架变更的设备回归（安装最新 Debug APK 和 `:app:assembleDebugAndroidTest` 产物后执行）：

```powershell
adb -s <serial> shell am instrument -w `
  -e class com.androidtoolsuite.app.plugin.runtime.RuntimePackageBackupInstrumentedTest,com.androidtoolsuite.app.plugin.runtime.DatasetServiceInstrumentedTest `
  com.androidtoolsuite.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`DirectShizukuProviderInstrumentedTest` 与 `BackupEnableConsentInstrumentedTest` 为需显式选择的设备集成测试：先安装已签名的新 Shizuku 插件、启动 Shizuku 并授权 Debug 宿主；前者验证不给 SDK 平台桥仍可读取设置与匹配日志，后者验证备份不能绕过可信 Provider 启用确认并在结束时还原启用状态。不要通过跳过断言掩盖未授权状态。业务数据恢复测试仅使用 `test.runtime_v2_data` 测试插件，不清除应用数据。

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

插件 Web 开发服务器仅存在于 Debug 构建，可手动设置或由 `ats dev --android` 管理：

```powershell
.\tools\adb-debug.ps1 -Command set-dev-server `
  -Plugin accessibility_grant `
  -DevUrl http://127.0.0.1:8765/web/index.html
.\tools\adb-debug.ps1 -Command clear-dev-server -Plugin accessibility_grant
```

模拟器和实体设备都使用 `adb reverse` 后的 `127.0.0.1`，并由 Debug Host
代理到插件 HTTPS 虚拟源；不要把远程地址或 dev 开关写入 Release 包。

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
.\tools\adb-debug.ps1 -Command import-plugin `
  -PluginFile ..\plugins\shizuku-auth\artifacts\shizuku-auth.atsplugin
.\tools\adb-debug.ps1 -Command set-plugin-enabled -Plugin shizuku_auth -Enabled $true
& $adb shell am force-stop com.androidtoolsuite.app.debug
& $adb shell monkey -p com.androidtoolsuite.app.debug 1

# 观察实时日志；按 Ctrl+C 停止
$appPid = (& $adb shell pidof com.androidtoolsuite.app.debug).Trim()
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

回到 Shizuku 图形界面确认服务已启动。导入并启用签名的 `shizuku-auth.atsplugin`，冷启动宿主
使同包系统功能激活；完全信任的 Shizuku 插件自身不显示权限开关，直接打开该插件请求 Shizuku 授权。
Provider 未激活前，同包 UI 不加载，管理页显示需要重启。
可用下面的命令回读连接和插件权限状态：

```powershell
.\tools\adb-debug.ps1 -Command status
.\tools\adb-debug.ps1 -Command list-permissions -Plugin shizuku_auth
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
| `import-plugin` | `-PluginFile <本机文件>`，或 `-Path <收件箱相对路径>`；开发中可追加 `-ReplaceSameVersion` | 导入完整 `.atsplugin`；同版本开发替换只在 Debug ADB 入口显式开启，仍使用原子 generation 切换并保留 Dataset |
| `export-plugin` | `-Plugin <id> [-OutputFile <本机文件>]` | 导出外部插件包并通过 ADB 拉取到电脑 |
| `delete-plugin` | `-Plugin <id>` | 删除外部插件；有已启用依赖方时拒绝 |
| `set-plugin-enabled` | `-Plugin <id> -Enabled $true/$false` | 启停插件并校验依赖 |
| `list-permissions` | `-Plugin <id>` | 列出 format v3 插件声明的权限、scope、当前状态和有限审计 |
| `set-permission` | `-Plugin <id> -Capability <id> -Enabled $true/$false` | Debug 构建中允许或撤销普通插件 Capability |
| `run-task` | `-Plugin <id> -Task <id>` | 用空输入立即运行清单中声明的后台任务；不接受任意载荷 |
| `last-task-run` | `-Plugin <id> -Task <id>` | 读取最近一次任务状态、时间和有界结果 |
| `set-widget-visible` | `-Widget <plugin:id> -Visible $true/$false` | 显示或隐藏主页组件 |
| `navigate` | `-Destination dashboard/plugins/manager/store/settings/about/plugin:<id>` | 使用 `adb shell am start` 打开指定页面 |
| `reset-state` | 无 | 删除外部插件和权限状态，并恢复组件显示状态 |

P0 Runtime 生命周期回归可用专用脚本执行。它会安装集中 Debug APK（可用 `-SkipInstall` 跳过）、临时导入后台任务示例，覆盖冷启动、前后台、旋转重建、权限撤销、任务状态和 Shizuku Provider 重连路径，并在结束时恢复旋转设置、删除示例插件：

```powershell
.\tools\adb-runtime-regression.ps1 -Serial <设备序列号>
```

完整例子：

```powershell
.\tools\adb-debug.ps1 -Command import-plugin `
  -PluginFile ..\plugins\shizuku-auth\artifacts\shizuku-auth.atsplugin

.\tools\adb-debug.ps1 -Command import-plugin `
  -PluginFile ..\plugins\accessibility-grant\artifacts\accessibility-grant.atsplugin

.\tools\adb-debug.ps1 -Command set-plugin-enabled `
  -Plugin shizuku_auth -Enabled $true

adb shell am force-stop com.androidtoolsuite.app.debug
adb shell monkey -p com.androidtoolsuite.app.debug 1

.\tools\adb-debug.ps1 -Command export-plugin `
  -Plugin accessibility_grant -OutputFile .\artifacts\accessibility-grant-debug.atsplugin

.\tools\adb-debug.ps1 -Command navigate -Destination manager
```

有多个设备时，通过 `-Serial emulator-5554` 指定目标。

## 原始 ADB 协议

不使用脚本时，显式广播 debug Receiver。响应位于 `Broadcast completed` 的 `data` 字段，成功时 `result=-1`、`ok=true`，失败时 `result=0`、`ok=false`。

```powershell
$pkg = 'com.androidtoolsuite.app.debug'
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

adb shell am broadcast -W `
  -a "$pkg.DEBUG_COMMAND" `
  -n "$pkg/.debug.DebugCommandReceiver" `
  --es command set-permission `
  --es plugin shizuku_auth `
  --es capability shizuku.control `
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
adb shell pm clear com.androidtoolsuite.app.debug

# 查看日志和崩溃
adb logcat --pid=$(adb shell pidof com.androidtoolsuite.app.debug)

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
- 普通 V3 插件的 WebView／JavaScriptSandbox 代码不获得宿主对象，能力调用由 Router 强制授权；只有 `trusted-provider` 会作为同进程全信任原生代码装载，调试环境也只应导入可信来源。
- 不提供任意命令执行接口；shell、文件和设备控制直接由 ADB 完成。
