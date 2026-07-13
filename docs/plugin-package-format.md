# ATS Plugin Package Format

插件包必须使用 `.atsplugin` 扩展名。本质是 zip 文件，根目录必须同时包含 `manifest.json` 和 `plugin.apk`。

## 目录结构

```text
example.atsplugin
  manifest.json
  plugin.apk
  assets/
```

宿主会解析 `manifest.json`，把 `plugin.apk` 复制到应用内部目录，再按清单中的 `entryClass` 动态加载插件入口类。缺少任一文件或入口类时会拒绝导入。

## 打包

可执行插件应在各自的独立仓库构建。每个插件仓库的根工程都提供：

```powershell
gradle packagePlugin
```

输出文件：

```text
build/outputs/atsplugin/<plugin-name>.atsplugin
```

## manifest.json

```json
{
  "format": "ats-plugin",
  "formatVersion": "1",
  "plugin": {
    "id": "sample_notes",
    "title": "示例插件",
    "description": "这是一个示例插件。",
    "version": "1.0",
    "author": "Local",
    "entryClass": "com.example.plugins.sample.SamplePlugin"
  },
  "dependencies": [
    "shizuku_auth"
  ]
}
```

## 安全边界

可执行插件与宿主运行在同一 Android 进程，并能取得宿主传入的 `Activity` 和 `PluginHost`。插件清单不提供权限声明或授权开关，因为这种同进程开关无法构成可靠隔离。请只安装可信来源的插件；需要更强隔离时，应将插件迁移到独立进程并通过受限 IPC 暴露能力。

## 依赖

插件可以通过 `dependencies` 或 `pluginDependencies` 声明依赖的插件 ID。宿主只会加载依赖已满足的插件；依赖未满足时，插件仍会出现在“插件管理”中，但不会出现在主页和插件列表。

常见依赖：

- `shizuku_auth`：宿主内置的 Shizuku 授权插件。

内置可选插件和外部插件默认停用，都可以在“插件管理”中启用或停用。启停状态保存在当前宿主内，不会写入导出的插件包。管理页会显示每个插件的依赖、被依赖方，以及完整依赖树。

如果依赖未满足，插件不能被启用，并会保留在“插件管理”中等待用户先启用依赖项。如果某个插件仍被其他已启用插件依赖，宿主会阻止停用或删除并提示依赖方。

## 主页小部件

可执行插件通过 `ToolPlugin.createHomeWidgets()` 注册动态小部件。主页小部件和工具卡片使用统一的长按拖动与落点虚影，排序仅在松手时保存；主页小部件长按后松开还可调整尺寸。工具页和主页小部件的显隐统一在“插件管理 → 界面管理”中按插件设置，每个插件分别提供“工具页”和“主页”开关。

## 可执行插件

可执行插件的入口类必须：

- 编译进插件包根目录的 `plugin.apk`。
- 实现 `com.androidtoolsuite.app.plugin.api.ToolPlugin`。
- 提供 public 无参构造方法。
- 在 `plugin.entryClass` 中声明完整类名。

插件工程只依赖已发布的 `com.androidtoolsuite:plugin-sdk:1.0.0` AAR，不依赖宿主的 `:app` 或本地 `:plugin-sdk` project。主体仓库可通过 `gradle :plugin-sdk:publishToMavenLocal` 发布 SDK，插件仓库随后可直接执行 `gradle packagePlugin`。宿主通过 `DexClassLoader` 加载 `plugin.apk`，因此插件代码可以独立构建和分发。

单个 JSON 清单和不含 `plugin.apk` 的说明型插件包不受支持。
