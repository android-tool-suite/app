# ATS Plugin Package Format

插件包推荐使用 `.atsplugin` 扩展名。本质是 zip 文件，根目录必须包含 `manifest.json`。

## 目录结构

```text
example.atsplugin
  manifest.json
  assets/
```

当前版本只解析 `manifest.json`，其他文件会保留为未来扩展约定。

## 打包

PowerShell:

```powershell
tools/package-plugin.ps1 -SourceDir examples/plugins/sample-package -OutputFile dist/sample-package.atsplugin
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
    "author": "Local"
  },
  "permissions": [
    "file.picker",
    "package.query"
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

导入后权限默认不授予，需要用户在“插件管理”里逐项开启。

## 兼容

仍支持直接导入单个 JSON 清单文件。推荐分发时使用 `.atsplugin`，便于未来携带资源、脚本或签名信息。
