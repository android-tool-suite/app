# 插件数据兼容声明

应用内历史版本选择不仅校验 APK 和清单，也会在覆盖安装前判断目标插件能否读取当前版本可能写入的数据。该判断来自每个插件仓库根目录的 `data-compatibility.json`，发布工作流会把它写入签名目录中的 `release-metadata.json`。

```json
{
  "schemaVersion": 1,
  "dataFormatVersion": 1,
  "minReadableDataFormatVersion": 0,
  "maxReadableDataFormatVersion": 1
}
```

- `dataFormatVersion`：本版本可能写入的最高持久化数据格式。它覆盖插件使用的 SharedPreferences、文件和数据库，而不是插件包格式。
- `minReadableDataFormatVersion` / `maxReadableDataFormatVersion`：本版本能够安全读取并继续使用的数据格式闭区间，必须包含自身的 `dataFormatVersion`。最低值可为 `0`。
- 没有兼容声明的旧版本及旧安装记录统一视为数据格式 `v0`，其默认可读范围也只有 `v0`。新版若已验证能够读取这些旧数据，应显式声明最小可读版本为 `0`。
- 只改代码而不改变持久化格式时保持 `dataFormatVersion` 不变，因此同一数据格式内可以安全选择历史版本。
- 新版本开始写入不再能被旧版理解的结构前，必须递增 `dataFormatVersion`；若新版本包含向前迁移逻辑，应把旧格式包含在可读范围内。
- 不能因为解析时“看起来没有崩溃”就扩大兼容范围；声明应由插件测试覆盖。

宿主会记录仓库安装版本的数据格式。选择更低的 `versionCode`，或选择同一 `versionCode` 下更早的 Debug 构建时：

1. 当前数据格式和目标兼容声明都存在且范围匹配，显示风险提示并允许确认降级。
2. 目标声明明确不包含当前格式，阻止安装。
3. 旧版本未声明时按 `v0` 判断；目标版本声明可读 `v0` 才允许覆盖，否则按明确不兼容阻止。

首次安装历史版本不涉及已有插件数据，因此允许安装缺少旧式声明的 Release。普通升级也按目标版本能否读取当前格式判断；未声明目标只能读取 `v0`，声明 `v0–v1` 的目标可承接旧数据。

宿主无法自动回滚任意插件业务数据：外部插件与宿主同进程，并可能直接使用宿主的 SharedPreferences、数据库和私有文件。正式版统一 `.atsbackup` v3 只能处理插件通过 `LegacyDataBridge` 明确声明的 API1 Dataset，并由插件实现替换、合并、删除和写入后校验；它不等同于每次安装的自动快照。降级前应显式导出需要保留的 Dataset，或使用 UIGF 等插件领域格式；代码安装失败时的事务回滚只恢复插件 APK、清单和来源记录，不代表已回滚插件业务数据。
