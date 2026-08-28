# Shizuku Authorization Tool

这是 Runtime v2 的官方声明式 UI 示例，也是宿主移除旧内置 `shizuku_auth` 后提供的独立插件包源码。

它不携带 Android DEX，也不直接依赖 Shizuku SDK；页面只调用宿主公开的 `shizuku.control` Capability。安装后需要在“管理 → Shizuku 授权 → 插件权限”中允许“管理 Shizuku 连接”。

从 `app/` 仓库根目录打包：

```powershell
python tools\runtime-v2\ats.py pack `
  examples\runtime-v2\shizuku-auth `
  --output artifacts\shizuku-auth.atsplugin
```
