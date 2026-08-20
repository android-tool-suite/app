# Android Tool Suite

Android Tool Suite 的主体应用仓库。宿主内置“插件管理”和 Shizuku 授权/绑定能力；每个外部插件均在自己的 Git 仓库中开发和发布，通过 `.atsplugin` 包导入宿主。

当前版本的新增特性、优化和问题修复见 [更新日志](CHANGELOG.md)。

宿主卡顿的测量基线、候选热点和后续实施顺序见 [性能优化路线](docs/performance-optimization-roadmap.md)。性能结论以实体设备上的 Release／benchmark 构建为准，不以 Debug 帧率代替正式验收。

## 使用方式

1. 在手机上安装并启动 Shizuku。
2. 用 Android Studio 打开本主体应用仓库。
3. 构建并安装 `app` 模块。
4. 打开 App，授予 Shizuku 权限。
5. 在底部导航进入“主页”“插件”或“管理”。
6. 在“插件管理 → 插件仓库”中选择正式或调试仓库，再为每个插件选择具体历史版本进行安装、升级或安全降级；也可以从同一页面导入本地 `.atsplugin`。
7. 启用插件后进入“无障碍授权”，在列表里选择你信任的无障碍服务，点击“启用”或“停用”。
8. 可用搜索框按应用名、服务名或包名过滤列表。
9. 可收藏常用服务；打开“启动时自动启用收藏服务”后，每次进入 App 会自动启用已收藏且仍安装的服务。

## 插件结构

插件实现 `ToolPlugin` 接口。宿主内置插件在 `ToolRegistry.createRequiredBuiltInPlugins()` 中注册；外部插件通过包含 `manifest.json` 和 `plugin.apk` 的完整 `.atsplugin` 包安装，并由 `ExternalToolFactory` 加载可执行入口。

```text
app/src/main/java/com/androidtoolsuite/app/
  host/                 主程序壳、Activity、Shizuku UserService、插件管理界面
  plugin/api/           插件 API：ToolPlugin、PluginHost、HomeWidget、依赖声明
  plugin/store/         插件状态、外部插件清单存储
  plugin/runtime/       插件注册器和外部插件工厂
  plugins/              宿主必须内置的插件实现
    builtin/shizuku/    Shizuku 授权内置插件

plugin-sdk/
  src/main/java/...     可发布的插件开发 SDK：API、清单模型、共享 UI 工具
```

统一工作区内的每个外部插件都是独立 Git 仓库：

- `../plugins/accessibility-grant`：无障碍授权。
- `../plugins/phigros-advisor`：Phigros Data Studio。
- `../plugins/gacha-analysis`：跃迁与祈愿分析。

主体与各插件仓库之间没有 Gradle project 依赖：主体仓库发布版本化 SDK AAR，每个插件仓库按 Maven 坐标消费它。新增插件时应创建新的仓库，不加入主体仓库或其他插件仓库。

需要 Shizuku shell 能力的插件可以通过 `PluginHost.runShellCommand(...)` 复用宿主已经绑定好的 Shizuku UserService。插件代码与宿主运行在同一进程，宿主不提供容易被绕过的插件级权限开关，因此只应安装可信插件。

## 导入插件

只支持导入完整 `.atsplugin` 插件包：包内必须同时包含 `manifest.json`、`plugin.apk`，清单还必须声明 `plugin.entryClass`。单个 JSON、只有说明信息的包以及缺少可执行入口的包都会被拒绝。插件默认停用，可以通过 `dependencies` 声明依赖；依赖未满足时不能启用，未启用的插件不会进入主页和工具列表。

完整包格式与 SDK 接入方式见 `docs/plugin-package-format.md`。

历史版本降级的数据格式契约与维护规则见 [插件数据兼容声明](docs/plugin-data-compatibility.md)。该声明属于发布元数据，不修改插件 SDK 公共 API。

向本机 Maven 仓库发布 SDK：

```powershell
gradle :plugin-sdk:publishToMavenLocal
```

也可以发布到主体仓库内的临时 Maven 目录，分别供插件仓库验证：

```powershell
gradle :plugin-sdk:publishReleasePublicationToPluginSdkRepository
gradle -p ..\plugins\accessibility-grant `
  -PatsSdkRepository=..\..\app\plugin-sdk\build\repository `
  clean collectArtifacts
gradle -p ..\plugins\phigros-advisor `
  -PatsSdkRepository=..\..\app\plugin-sdk\build\repository `
  clean collectArtifacts
```

说明：应用会在管理页明确提示同进程插件的信任边界。启用外部插件前，请确认插件来源和代码可信。

工具页和主页小部件的显隐统一在“插件管理 → 界面管理”中按插件设置；每个插件只显示一次，并分别提供“工具页”和“主页”开关。隐藏只影响界面展示，不会停用插件。主页小部件和工具卡片都可以长按拖动，使用相同虚影预览松手后的落点，排序仅在松手时保存；主页小部件长按后松开还可调整尺寸。

## 构建要求

- JDK 17
- Android SDK 35
- Android 7.0+，即 `minSdk 24`
- Gradle 8.9 或更新版本

命令行构建：

```powershell
gradle :app:assembleDebug
```

### Debug 签名

不配置也能构建：Gradle 回落到 Android 默认 debug keystore，装到干净设备上正常工作。构建时会打印当前用的是哪一路签名。

需要注意默认 keystore 的两个后果：

- 无法覆盖安装 CI 构建的版本，`adb install -r` 报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 一旦装上，应用内更新会被系统安装器以同样原因拒绝。更新走 `ACTION_VIEW` 交给系统安装器，签名变更一律拒装，所以那台设备的更新通道会失效，需要卸载重装才能回到 CI 版本。

如果只是本地开发自测，默认 keystore 足够；只要不与 CI 包混装就不会遇到上面的问题。想让自己的连续构建能互相覆盖，用 `keytool` 生成一把固定的 keystore 长期使用即可，不需要项目的那把。

#### 与 CI 使用同一签名

需要与 CI 完全一致的签名（例如要覆盖安装 CI 版本、或要验证应用内更新链路），得拿到项目专用的 debug keystore；它只存在于 CI secret `ATS_DEBUG_KEYSTORE_B64` 与维护者的密钥备份中，仓库里没有。

拿到后在**仓库外**准备一个属性文件，例如与 keystore 放在同一目录：

```properties
# 相对路径按本文件所在目录解析，绝对路径也可以
storeFile=android-tool-suite-debug.p12
storePassword=<口令>
keyAlias=<别名>
keyPassword=<口令>
```

再在 `app/local.properties`（已被 `.gitignore` 忽略）里指向它：

```properties
atsDebugSigningProperties=<该属性文件的绝对路径>
```

密钥、口令和本机路径都留在仓库外；仓库里只有 `local.properties` 这一行，且不会被提交。文件名与 keystore 名由你自己定，构建不假定命名。

CI 用 `ATS_DEBUG_KEYSTORE_PATH` / `ATS_DEBUG_KEYSTORE_PASSWORD` / `ATS_DEBUG_KEY_ALIAS` / `ATS_DEBUG_KEY_PASSWORD` 四个环境变量，优先级高于 `atsDebugSigningProperties`。

核对产物指纹：

```powershell
$buildTools = Get-ChildItem "$env:ANDROID_HOME\build-tools" -Directory |
  Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs artifacts\android-tool-suite-debug.apk
```

项目 debug 证书是 `CN=Android Tool Suite Debug, O=android-tool-suite`。看到 `CN=Android Debug` 就说明用的是默认 keystore。

收集主体 APK：

```powershell
gradle clean collectArtifacts
```

输出仅包含 `artifacts/android-tool-suite-debug.apk`；每个外部插件仓库只管理自己的 `.atsplugin` 产物。

`main` 分支 CI 成功后会使用稳定专用签名创建 `debug-<完整提交 SHA>` 历史快照，同时更新名为 `debug` 的滚动预发布，二者都提供 `android-tool-suite-debug.apk`、发布元数据和校验和；`v<versionName>` 标签仍使用 GitHub Environment 中的正式签名密钥生成 `android-tool-suite.apk` 正式 Release。两类发布完成后由 GitHub App 生成短时 installation token，发送事件通知发布目录重建。Pages 发布中心会展示宿主 APK 和插件，并允许选择正式或调试历史版本。

Release 使用包名 `com.androidtoolsuite.app`，Debug 使用 `com.androidtoolsuite.app.debug`，因此可以同时安装且数据完全隔离。应用启动时最多每 24 小时读取一次签名更新索引：Release 始终检查正式宿主，Debug 检查带稳定签名的滚动调试宿主；插件源仍可独立选择正式或调试仓库。应用会读取签名后的历史目录并展示各版本；插件更新在目录签名、大小、SHA-256 与预加载校验成功后事务式替换。

插件发布元数据可以声明 `dataFormatVersion` 以及目标版本可读取的数据格式范围。降级只有在当前数据格式和目标兼容范围都明确且匹配时才会执行，并会再次要求确认；声明不兼容或旧版本缺少声明时，应用会阻止覆盖。正式版宿主迁移包不包含插件业务数据，因此降级前仍应使用插件自身的 UIGF、记录导出等功能备份。临时 Bridge Debug 构建另提供 Dataset 数据包往返验证，不写入 Runtime v2 存储。

插件管理页可以导出和导入 `.atsbackup` 迁移包，用于复制宿主布局、仓库选择、插件包和启用状态。迁移包不会包含 Phigros SessionToken、米游社会话、抽卡记录或其他插件业务数据；这些数据继续使用插件自己的导出功能或在目标应用重新登录。

Bridge Debug 构建在设置页提供单独的“导入／导出 Bridge 数据包”入口。它只处理插件明确声明的 API1 Dataset，导入时先在应用私有缓存完成解密与完整性校验，随后由插件以事务或原子文件切换恢复；该入口用于迁移验收，不是正式版宿主迁移包。

## ADB 自动化调试

Debug APK 提供受 `android.permission.DUMP` 保护的 ADB 命令入口，可查询应用状态、导入/删除/启停插件、切换主页组件显隐并重置调试状态。页面跳转使用 ADB 原生 `am start`，支持直接打开主页、工具列表、插件管理或指定插件。

```powershell
.\tools\adb-debug.ps1 -Command status
.\tools\adb-debug.ps1 -Command list-plugins
.\tools\adb-debug.ps1 -Command navigate -Destination manager
```

支持带完整手机界面的 Emulator 与 ADB 并行调试，也支持无窗口自动化测试。图形模拟器启动、Shizuku 安装和启动、全部命令、原始广播协议及安全边界见 [ADB 调试文档](docs/adb-debugging.md)。

## 安全边界

无障碍权限非常敏感，能读取屏幕内容并代表用户执行操作。这个示例不会静默批量授权，只允许用户在界面里对单个已安装服务执行启用或停用。自动启用功能只作用于用户手动收藏过的服务，并且需要先打开“启动时自动启用收藏服务”开关。

底层实现会通过 Shizuku UserService 以 shell/root 身份执行：

```text
settings get secure enabled_accessibility_services
settings put secure enabled_accessibility_services ...
settings put secure accessibility_enabled 1/0
```

部分系统 ROM 可能会拦截或覆写 secure settings，遇到这种情况需要以具体设备行为为准。
