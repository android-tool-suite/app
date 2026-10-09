# Android Tool Suite

Android Tool Suite 的主体应用仓库。宿主负责插件安装、统一声明式 UI、Capability 权限、数据、调度和最小平台 bootstrap；Host 组件树与 WebView 都是声明式 UI renderer。工具和全信任底层能力通过不同类型的 `.atsplugin` 包独立安装，Shizuku 授权界面与能力 Provider 都不再是内置插件。

当前版本的新增特性、优化和问题修复见 [更新日志](CHANGELOG.md)。

需要独立安装非调试测试版时，参见 [性能版构建与安装说明](docs/performance-installation.md)。

## 使用方式

1. 安装并启动宿主应用，从“仓库”安装插件，或导入本地 `.atsplugin`。
2. 在“工具”的插件详情中启用插件，按需允许其功能权限；包含 Native Provider 的插件需重启宿主完成激活。
3. 主页放置和整理小部件，工具页打开与管理已安装插件，仓库发现未安装插件，设置维护应用偏好、数据和任务结果。
4. 使用系统工具时，先在设备上安装并启动 Shizuku，启用独立的 Shizuku 授权插件并请求连接授权；完全信任插件自身没有无效的能力开关。
5. 无障碍授权按服务分别维护收藏、启用与自动恢复规则。收藏不自动开启恢复，系统状态与已保存规则分别显示。
6. 跨应用与插件备份从“设置 → 数据管理”进入；插件详情的数据入口只处理该插件，均先选择内容再核对执行。

## 插件结构

插件使用 format v3 清单。普通 Tool 统一声明 `ui/*.json`，由文档选择宿主组件树或隔离 WebView renderer，并且只能通过 Capability Router 使用获授权的能力。API1 `ToolPlugin`／`plugin.apk` 已停止安装与执行。

```text
app/src/main/java/com/androidtoolsuite/app/
  host/                 主程序壳、Activity、Shizuku UserService、插件管理界面
  plugin/runtime/       插件运行时、V3 包、UI renderer、权限、数据与调度
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

主体与各插件仓库之间没有 Gradle project 依赖：含 Native Provider 的插件按 Maven 坐标消费版本化 SDK AAR；纯 Web/Worker 插件使用公开契约和打包 CLI，无需依赖 Android 工程或 SDK AAR。新增插件时应创建新的仓库，不加入主体仓库或其他插件仓库。

普通插件既能消费 Capability，也能通过受限 JavaScript Worker 提供自定义 Capability；Worker 的下游调用以提供者插件自己的身份重新检查声明和权限，不会继承消费者权限。需要宿主身份的系统交互才使用 `trusted-provider`，但它仍可拥有普通插件的 UI、主页组件、Worker 和工具贡献。`shizuku_auth` 通过最小宿主 bridge 注册 `shizuku.control`、`accessibility.manage` 与通用 `system.logs`，宿主本身不注册这些业务能力。普通插件的未授权调用会在 Router 被阻断；`trusted-provider` 是同进程全信任代码，权限开关不能替代来源审核。插件私有数据空间是运行基础，不列入权限页面；完全信任插件自身也不显示无法生效的权限开关。

## 导入插件

format v3 包必须包含 `manifest.json`、`META-INF/ats-integrity.json` 以及清单引用的 `web/`、`ui/`、`workers/` 或可选 `android/provider.apk`；Provider 包还必须有受信 publisher 签名。旧 format v1/v2 包仅供历史归档识别，不再安装、启用或导出。插件默认停用，宿主版本、可选 `minAndroidApi` 或依赖未满足时不能启用；敏感 Capability 默认待用户决定。

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

说明：应用在插件详情明确提示同进程插件的信任边界。启用外部插件前，请确认插件来源和代码可信。

工具页整合已安装插件管理，支持排序、文件夹和工具收纳；详情维护启用、权限、更新、插件包与插件数据。主页的小部件可按预设添加多个独立实例，固定账号、卡池和历史范围，按 1×1 占用网格摆放；可放入文件夹，分别调整尺寸、隐藏或真正移除。收纳工具和隐藏小部件不停止插件运行。

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

覆盖安装要求相同签名；本地包与 CI 产物使用不同 keystore 时，`adb install -r` 会报告 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。连续本地构建可使用自己的固定 keystore。Debug 没有远端应用更新，签名一致性仍关系到后续侧载覆盖和数据保留。

#### 与 CI 使用同一签名

需要与 CI 完全一致的签名（例如要覆盖安装 CI 版本、或要验证侧载覆盖安装），得拿到项目专用的 debug keystore；它只存在于 CI secret `ATS_DEBUG_KEYSTORE_B64` 与维护者的密钥备份中，仓库里没有。

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

日常 CI 只测试并上传构建产物。Debug 通过本地构建和 ADB 安装进行调试，不发布远程版本；`v<versionName>` 标签生成正式 Release，并通过 GitHub App 通知目录重建。

Release 使用包名 `com.androidtoolsuite.app`，Debug 使用 `com.androidtoolsuite.app.debug`，因此可以同时安装且数据完全隔离。应用启动时最多每 24 小时读取一次签名更新索引：Release 检查正式宿主更新，Debug 不提供在线宿主更新；两种构建的插件仓库均使用正式目录。应用会读取签名后的历史目录并展示各版本；插件更新在目录签名、大小、SHA-256 与预加载校验成功后事务式替换。

插件发布元数据可以声明 `dataFormatVersion` 以及目标版本可读取的数据格式范围。降级只有在当前数据格式和目标兼容范围都明确且匹配时才会执行，并会再次要求确认；声明不兼容或旧版本缺少声明时，应用会阻止覆盖。降级前应使用统一 `.atsbackup` 数据包或插件自身的领域格式备份需要保留的数据。

设置中的数据管理与插件详情使用统一 `.atsbackup` v3 流程：先选择应用设置、插件状态、安装包或 Dataset，再核对保护／恢复方式。导出默认加密所选内容，支持仅敏感项、不加密与逐项自定义；导入优先提供接收方真实支持的合并，仍可明确选择替换。数据文件名使用内容标识与本地日期时间，插件包导出只保留版本、不附加时间；Debug 应用的备份、插件包及插件文件导出统一在扩展名前添加 `-debug`，用于辨认导出来源，不改变内部内容。

数据管理现在只通过宿主私有 DatasetBridge 处理 format v3 Dataset，并使用 staging、完整性校验和 generation 原子切换。旧 `.atsbackup` v2/v3 与宿主迁移包仍可识别；其中携带的 API1 插件包不会被安装或执行。

## ADB 自动化调试

Debug APK 提供受 `android.permission.DUMP` 保护的 ADB 命令入口，可查询应用状态、导入/删除/启停插件、切换主页组件显隐并重置调试状态。页面跳转使用 ADB 原生 `am start`，支持直接打开主页、工具列表、仓库、工具或指定插件。

```powershell
.\tools\adb-debug.ps1 -Command status
.\tools\adb-debug.ps1 -Command list-plugins
.\tools\adb-debug.ps1 -Command navigate -Destination plugins
```

支持带完整手机界面的 Emulator 与 ADB 并行调试，也支持无窗口自动化测试。图形模拟器启动、Shizuku 安装和启动、全部命令、原始广播协议及安全边界见 [ADB 调试文档](docs/adb-debugging.md)。

## 安全边界

普通插件的网络、文件和系统调用由 Capability Router 按声明、权限及用户手势检查；授权后仍仅能使用声明范围内的能力。`trusted-provider` 是同进程可信代码，启用前应核实其来源，插件开关不能被描述为安全沙箱。

无障碍启停由独立授权插件按用户选择执行，自动恢复只作用于显式保存的服务规则，并要求后台权限与可用系统连接。Shizuku Provider 以 shell/root 身份读写对应 secure settings，普通消费者没有通用 Shell 或完整日志接口。部分系统可能拦截或重写设置，实际结果以设备回读为准。
