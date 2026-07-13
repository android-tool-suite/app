# Android Tool Suite

Android Tool Suite 的主体应用仓库。宿主内置“插件管理”和 Shizuku 授权/绑定能力；每个外部插件均在自己的 Git 仓库中开发和发布，通过 `.atsplugin` 包导入宿主。

当前版本的新增特性、优化和问题修复见 [更新日志](CHANGELOG.md)。

## 使用方式

1. 在手机上安装并启动 Shizuku。
2. 用 Android Studio 打开本主体应用仓库。
3. 构建并安装 `app` 模块。
4. 打开 App，授予 Shizuku 权限。
5. 在底部导航进入“主页”“插件”或“管理”。
6. 从外部插件仓库构建或获取插件，并在“插件管理”中导入 `.atsplugin`。
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

主体与各插件仓库之间没有 Gradle project 依赖：主体仓库发布版本化 SDK AAR，每个插件仓库按 Maven 坐标消费它。新增插件时应创建新的仓库，不加入主体仓库或其他插件仓库。

需要 Shizuku shell 能力的插件可以通过 `PluginHost.runShellCommand(...)` 复用宿主已经绑定好的 Shizuku UserService。插件代码与宿主运行在同一进程，宿主不提供容易被绕过的插件级权限开关，因此只应安装可信插件。

## 导入插件

只支持导入完整 `.atsplugin` 插件包：包内必须同时包含 `manifest.json`、`plugin.apk`，清单还必须声明 `plugin.entryClass`。单个 JSON、只有说明信息的包以及缺少可执行入口的包都会被拒绝。插件默认停用，可以通过 `dependencies` 声明依赖；依赖未满足时不能启用，未启用的插件不会进入主页和工具列表。

完整包格式与 SDK 接入方式见 `docs/plugin-package-format.md`。

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

收集主体 APK：

```powershell
gradle clean collectArtifacts
```

输出仅包含 `artifacts/android-tool-suite-debug.apk`；每个外部插件仓库只管理自己的 `.atsplugin` 产物。

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
