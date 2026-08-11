# 插件数据兼容声明

应用内历史版本选择不仅校验 APK 和清单，也会在覆盖安装前判断目标插件能否读取当前版本可能写入的数据。该判断来自每个插件仓库根目录的 `data-compatibility.json`，发布工作流会把它写入签名目录中的 `release-metadata.json`。

```json
{
  "schemaVersion": 1,
  "dataFormatVersion": 1,
  "minReadableDataFormatVersion": 1,
  "maxReadableDataFormatVersion": 1
}
```

- `dataFormatVersion`：本版本可能写入的最高持久化数据格式。它覆盖插件使用的 SharedPreferences、文件和数据库，而不是插件包格式。
- `minReadableDataFormatVersion` / `maxReadableDataFormatVersion`：本版本能够安全读取并继续使用的数据格式闭区间，必须包含自身的 `dataFormatVersion`。
- 只改代码而不改变持久化格式时保持 `dataFormatVersion` 不变，因此同一数据格式内可以安全选择历史版本。
- 新版本开始写入不再能被旧版理解的结构前，必须递增 `dataFormatVersion`；若新版本包含向前迁移逻辑，应把旧格式包含在可读范围内。
- 不能因为解析时“看起来没有崩溃”就扩大兼容范围；声明应由插件测试覆盖。

宿主会记录仓库安装版本的数据格式。选择更低的 `versionCode`，或选择同一 `versionCode` 下更早的 Debug 构建时：

1. 当前数据格式和目标兼容声明都存在且范围匹配，显示风险提示并允许确认降级。
2. 目标声明明确不包含当前格式，阻止安装。
3. 任一版本缺少声明，按未知风险处理并阻止直接覆盖。

首次安装历史版本不涉及已有插件数据，因此允许安装缺少旧式声明的 Release。普通升级仍保持对旧发布的兼容，但若新旧双方都有声明且明确不兼容，同样会阻止覆盖。

宿主无法统一回滚插件业务数据：外部插件与宿主同进程，并可能直接使用宿主的 SharedPreferences、数据库和私有文件。`.atsbackup` 迁移包也刻意不包含这些业务数据。降级前应使用插件自己的 UIGF、记录导出或其他备份功能；代码安装失败时的事务回滚只恢复插件 APK、清单和来源记录，不代表已回滚插件业务数据。
