# Android Tool Suite

Android Tool Suite 的主体应用仓库。宿主负责插件安装、统一声明式 UI、Capability 权限、数据、调度和最小平台 bootstrap；Host 组件树与 WebView 都是声明式 UI renderer。工具和全信任底层能力通过不同类型的 `.atsplugin` 包独立安装，Shizuku 授权界面与能力 Provider 都不再是内置插件。

当前版本的新增特性、优化和问题修复见 [更新日志](CHANGELOG.md)。

宿主卡顿的测量基线、候选热点和后续实施顺序见 [性能优化路线](docs/performance-optimization-roadmap.md)。性能结论以实体设备上的 Release／benchmark 构建为准，不以 Debug 帧率代替正式验收。

## 使用方式

1. 在手机上安装并启动 Shizuku。
2. 用 Android Studio 打开本主体应用仓库。
3. 构建并安装 `app` 模块。
4. 从独立的 `plugin-shizuku-auth` 仓库构建或下载 `shizuku-auth.atsplugin`，导入并启用后重启宿主，使同包底层能力冷启动激活。
5. 在管理页允许“管理 Shizuku 连接”，再打开该插件请求系统授权。
6. 在底部导航进入“主页”“插件”或“管理”。
7. 在“插件管理 → 插件仓库”中选择正式或调试仓库，再为每个插件选择具体历史版本进行安装、升级或安全降级；也可以从同一页面导入本地 `.atsplugin`。
8. 启用插件后进入“无障碍授权”，在列表里选择你信任的无障碍服务，点击“启用”或“停用”。
9. 可用搜索框按应用名、服务名或包名过滤列表。
10. 可收藏常用服务；打开“启动时自动启用收藏服务”后，每次进入 App 会自动启用已收藏且仍安装的服务。

## 插件结构

插件运行时 插件使用 format v3 清单。普通 Tool 统一声明 `ui/*.json`，由文档选择宿主组件树或隔离 WebView renderer，并且只能通过 Capability Router 使用获授权的能力。API1 `ToolPlugin`／`plugin.apk` 已停止安装与执行。

```text
app/src/main/java/com/androidtoolsuite/app/
  host/                 主程序壳、Activity、Shizuku UserService、插件管理界面
  plugin/runtime/       插件运行时、V3 包、UI renderer、权限、数据与调度
  plugin/runtime/       format v3 运行时与宿主私有 Tool/数据管理适配
plugin-sdk/
  src/main/java/...     可发布的插件 API、Native Provider 接口和共享 UI

runtime-contract/
  src/main/resources/   manifest、RPC、声明式 UI 与 Capability 单一契约源

web-sdk/
  src/                  Web Tool 使用的 TypeScript SDK

examples/plugins/
  hello-web/            Web Tool 示例
  worker-capability/    普通插件用受限 Worker 提供自定义能力的示例

tools/plugin/
  ats.py                插件校验、打包、开发服务器与契约生成 CLI
```

统一工作区内的插件均是独立 Git 仓库：

- `../plugins/shizuku-auth`：Shizuku 授权与全信任底层能力。
- `../plugins/accessibility-grant`：无障碍授权。
- `../plugins/phigros-advisor`：Phigros Data Studio。
- `../plugins/gacha-analysis`：跃迁与祈愿分析。

`shizuku_auth` 构建为一个签名的全信任 `.atsplugin`：同包包含声明式授权界面、主页组件和 Native Provider。宿主不会内置或自动启用它，原生能力在启用后的下一次冷启动激活。

主体与各插件仓库之间没有 Gradle project 依赖：主体仓库发布版本化 SDK AAR，每个插件仓库按 Maven 坐标消费它。新增插件时应创建新的仓库，不加入主体仓库或其他插件仓库。

普通插件既能消费 Capability，也能通过受限 JavaScript Worker 提供自定义 Capability；Worker 的下游调用以提供者插件自己的身份重新检查声明和权限，不会继承消费者权限。需要宿主身份的系统交互才使用 `trusted-provider`，但它仍可拥有普通插件的 UI、主页组件、Worker 和工具贡献。`shizuku_auth` 通过最小宿主 bridge 注册 `shizuku.control`、`accessibility.manage` 与通用 `system.logs`，宿主本身不注册这些业务能力。普通插件的未授权调用会在 Router 被阻断；`trusted-provider` 是同进程全信任代码，权限开关不能替代来源审核。插件私有数据空间是运行基础，不列入权限页面；完全信任插件自身也不显示无法生效的权限开关。

## 导入插件

format v3 包必须包含 `manifest.json`、`META-INF/ats-integrity.json` 以及清单引用的 `web/`、`ui/`、`workers/` 或可选 `android/provider.apk`；Provider 包还必须有受信 publisher 签名。旧 format v1/v2 包继续要求 `plugin.apk`。插件默认停用，宿主版本、可选 `minAndroidApi` 或依赖未满足时不能启用；敏感 Capability 默认待用户决定。

完整包格式与 SDK 接入方式见 `docs/plugin-package-format.md`。

历史版本降级的数据格式契约与维护规则见 [插件包格式](docs/plugin-package-format.md#9-数据格式兼容与降级)。该声明属于发布元数据，不修改插件 SDK 公共 API。

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

工具页、主页小部件、更新检查和普通 format v3 插件的 Capability 权限统一在管理页的插件展开卡中设置。隐藏只影响界面展示，不会停用插件。主页小部件和工具卡片都可以长按拖动，使用相同虚影预览松手后的落点，排序仅在松手时保存；主页小部件长按后松开还可调整尺寸。

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

日常 CI 只测试并上传构建产物。手动推送 `debug-<完整提交 SHA>` 标签后，专用工作流使用稳定签名重新测试、构建并发布 Debug，包含 APK、元数据和校验和；`v<versionName>` 标签继续生成正式 Release。两类发布都通过 GitHub App 通知目录重建。

Release 使用包名 `com.androidtoolsuite.app`，Debug 使用 `com.androidtoolsuite.app.debug`，因此可以同时安装且数据完全隔离。应用启动时最多每 24 小时读取一次签名更新索引：Release 始终检查正式宿主，Debug 检查带稳定签名的最新手动标签调试宿主；插件源仍可独立选择正式或调试仓库。应用会读取签名后的历史目录并展示各版本；插件更新在目录签名、大小、SHA-256 与预加载校验成功后事务式替换。

插件发布元数据可以声明 `dataFormatVersion` 以及目标版本可读取的数据格式范围。降级只有在当前数据格式和目标兼容范围都明确且匹配时才会执行，并会再次要求确认；声明不兼容或旧版本缺少声明时，应用会阻止覆盖。降级前应使用统一 `.atsbackup` 数据包或插件自身的领域格式备份需要保留的数据。

设置页和插件管理页使用统一 `.atsbackup` v3：应用设置、插件启用状态、每个插件包和插件声明的 Dataset 都能独立选择。导出项可设为不导出、明文或密码加密；导入会根据目标是否已有数据和插件能力提供跳过、导入、替换或合并。

数据管理现在只通过宿主私有 DatasetBridge 处理 format v3 Dataset，并使用 staging、完整性校验和 generation 原子切换。旧 `.atsbackup` v2/v3 与宿主迁移包仍可识别；其中携带的 API1 插件包不会被安装或执行。

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
