# ATS Plugin Package Format

插件包推荐使用 `.atsplugin` 扩展名。本质是 zip 文件，根目录必须包含 `manifest.json`。
如果插件包含可执行代码，根目录还应包含 `plugin.apk`。

## 目录结构

```text
example.atsplugin
  manifest.json
  plugin.apk
  assets/
```

宿主会解析 `manifest.json`，并把可选的 `plugin.apk` 复制到应用内部目录，再按清单中的 `entryClass` 动态加载插件入口类。

## 打包

PowerShell:

```powershell
tools/package-plugin.ps1 -SourceDir examples/plugins/sample-package -OutputFile artifacts/sample-package.atsplugin
```

可执行插件推荐使用独立 Gradle 模块，例如本仓库的无障碍授权插件：

```powershell
gradle :plugins:accessibility-grant:packagePlugin
```

输出文件：

```text
plugins/accessibility-grant/build/outputs/atsplugin/accessibility-grant.atsplugin
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
  "permissions": [
    "file.picker",
    "package.query"
  ],
  "dependencies": [
    "shizuku_auth"
  ],
  "widgets": [
    {
      "id": "summary",
      "title": "示例状态",
      "value": "已安装",
      "subtitle": "这个信息会作为主页小部件显示"
    }
  ]
}
```

## 权限

支持的权限：

- `shell.exec`：通过宿主调用 Shizuku UserService 执行命令。
- `shizuku`：允许插件请求宿主使用内部 Shizuku 授权和 UserService 连接。
- `accessibility.settings`：修改 secure settings 中的无障碍服务配置。
- `package.query`：读取应用和组件信息。
- `file.picker`：请求宿主打开系统文件选择器。

导入后插件默认停用，权限默认不授予。用户需要先在“插件管理”中启用插件，再逐项开启权限。

## 依赖

插件可以通过 `dependencies` 或 `pluginDependencies` 声明依赖的插件 ID。宿主只会加载依赖已满足的插件；依赖未满足时，插件仍会出现在“插件管理”中，但不会出现在主页和插件列表。

常见依赖：

- `shizuku_auth`：宿主内置的 Shizuku 授权插件。

内置可选插件和外部插件默认停用，都可以在“插件管理”中启用或停用。启停状态保存在当前宿主内，不会写入导出的插件包。管理页会显示每个插件的依赖、被依赖方，以及完整依赖树。

如果依赖未满足，插件不能被启用，并会保留在“插件管理”中等待用户先启用依赖项。如果某个插件仍被其他已启用插件依赖，宿主会阻止停用或删除并提示依赖方。

## 主页小部件

插件可以通过 `widgets` 或 `homeWidgets` 注册主页信息小部件。当前外部插件清单支持静态信息字段：

- `id`：插件内唯一的小部件 ID。
- `title`：小部件标题。
- `value`：小部件主数值或状态。
- `subtitle`：小部件说明。

用户可以在主页的“自定义主页”区域自由显示或隐藏这些小部件。内置 Java 插件可以通过 `ToolPlugin.createHomeWidgets()` 注册动态小部件。

## 可执行插件

可执行插件的入口类必须：

- 编译进插件包根目录的 `plugin.apk`。
- 实现 `com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin`。
- 提供 public 无参构造方法。
- 在 `plugin.entryClass` 中声明完整类名。

插件工程只依赖 `:plugin-sdk`，不依赖 `:app`。宿主通过 `DexClassLoader` 加载 `plugin.apk`，因此插件代码可以独立构建和分发。

## 兼容

仍支持直接导入单个 JSON 清单文件。推荐分发时使用 `.atsplugin`，便于未来携带资源、脚本或签名信息。
