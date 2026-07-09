# Android Tool Suite

一个插件式安卓工具合集。宿主内置“插件管理”和 Shizuku 授权/绑定能力；“无障碍授权”是独立插件模块，构建为 `.atsplugin` 后导入宿主，通过权限管理申请使用宿主的 Shizuku 能力。

## 使用方式

1. 在手机上安装并启动 Shizuku。
2. 用 Android Studio 打开本项目。
3. 构建并安装 `app` 模块。
4. 打开 App，授予 Shizuku 权限。
5. 在底部导航进入“主页”“插件”或“管理”。
6. 构建无障碍授权插件并在“插件管理”中导入 `.atsplugin`。
7. 使用前，在“插件管理”里给“无障碍授权”授予 `shizuku`、`shell.exec`、`accessibility.settings`、`package.query` 权限。
8. 授权后进入“无障碍授权”，在列表里选择你信任的无障碍服务，点击“启用”或“停用”。
9. 可用搜索框按应用名、服务名或包名过滤列表。
10. 可收藏常用服务；打开“启动时自动启用收藏服务”后，每次进入 App 会自动启用已收藏且仍安装的服务。

## 插件结构

插件实现 `ToolPlugin` 接口。宿主内置插件在 `ToolRegistry.createRequiredBuiltInPlugins()` 中注册；外部插件通过 `.atsplugin`/JSON 清单登记，并由 `ExternalToolFactory` 映射到可执行插件实现。

```text
app/src/main/java/com/example/shizukuaccessibilitygrant/
  host/                 主程序壳、Activity、Shizuku UserService、插件管理界面
  plugin/api/           插件 API：ToolPlugin、PluginHost、HomeWidget、权限目录、依赖声明
  plugin/store/         插件状态、外部插件清单存储
  plugin/runtime/       插件注册器和外部插件工厂
  plugins/              具体插件实现
    builtin/shizuku/    Shizuku 授权内置插件
    external/           未知外部插件的清单展示页

plugin-sdk/
  src/main/java/...     插件开发 SDK：API、清单模型、共享 UI 工具

plugins/accessibility-grant/
  src/main/java/...     无障碍授权插件源码
  manifest.json         插件清单
  build.gradle          独立插件构建和 packagePlugin 打包任务
```

需要 Shizuku shell 能力的外部插件必须先声明并获授 `shizuku` 与 `shell.exec` 权限，再通过 `PluginHost.runShellCommand(...)` 复用宿主已经绑定好的 Shizuku UserService。

## 导入插件

当前支持导入 `.atsplugin` 插件包或 JSON 插件清单，用于把外部插件登记到工具合集里，并支持在“插件管理”中导出、删除和授权。带 `plugin.apk` 和 `entryClass` 的 `.atsplugin` 会作为可执行插件动态加载；纯 JSON 清单只展示插件信息和权限。插件可以通过 `dependencies` 声明依赖，依赖未满足时不会进入主页和插件列表。

示例清单见：

```text
examples/plugins/sample-json/manifest.json
```

JSON 清单字段格式：

```json
{
  "format": "ats-plugin",
  "formatVersion": "1",
  "plugin": {
    "id": "sample_notes",
    "title": "示例插件",
    "description": "这是一个用于测试导入、删除和权限管理流程的外部插件清单。",
    "version": "1.0",
    "author": "Local"
  },
  "permissions": [
    "file.picker",
    "package.query"
  ]
}
```

完整包格式见 `docs/plugin-package-format.md`。

构建无障碍授权插件：

```powershell
gradle :plugins:accessibility-grant:packagePlugin
```

插件包输出：

```text
plugins/accessibility-grant/build/outputs/atsplugin/accessibility-grant.atsplugin
```

说明：为了避免任意导入文件直接获得 shell 执行能力，外部导入插件声明的权限默认不授予，需要用户在“插件管理”里逐项开启。

## 构建要求

- JDK 17
- Android SDK 35
- Android 7.0+，即 `minSdk 24`
- Gradle 8.9 或更新版本

命令行构建：

```powershell
gradle :app:assembleDebug
```

## 安全边界

无障碍权限非常敏感，能读取屏幕内容并代表用户执行操作。这个示例不会静默批量授权，只允许用户在界面里对单个已安装服务执行启用或停用。自动启用功能只作用于用户手动收藏过的服务，并且需要先打开“启动时自动启用收藏服务”开关。

底层实现会通过 Shizuku UserService 以 shell/root 身份执行：

```text
settings get secure enabled_accessibility_services
settings put secure enabled_accessibility_services ...
settings put secure accessibility_enabled 1/0
```

部分系统 ROM 可能会拦截或覆写 secure settings，遇到这种情况需要以具体设备行为为准。
