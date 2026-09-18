# 独立性能测试版

性能优化开发分支：`codex/performance-optimization`。构建时以该分支当前工作树为准；本地安装不代表正式发布。

从聚合工作树执行：

```powershell
gradle -p app clean collectArtifacts collectPerformanceArtifacts
adb -s <设备序列号> install -r -t .\app\artifacts\android-tool-suite-performance.apk
```

- 应用名：安卓工具合集 性能版。
- 独立包名：`com.androidtoolsuite.app.performance`，与正式版、Debug 版、旧 benchmark 版并存。
- 基于 `release` 构建类型，`debuggable=false`、`BuildConfig.DEBUG=false`，依赖使用 Release 变体；不包含 Debug Receiver 或 WebView 调试入口。
- 启用 shell profileable，便于需要时观察具体加载路径；不要求大组跑分或温度／电量采样。
- 为本机侧载使用现有 Debug 签名配置，签名类型不改变非调试运行参数。这不是正式渠道发布包。
- 当前 Release 本身未开启 R8；性能版沿用该设置，不额外引入混淆或基线配置差异。
- 包名不同，因此插件、权限和用户数据独立，安装不会迁移或覆盖正式版数据。请在性能版内导入所需插件和数据，并完成其独立授权。
- APK 输出：`app/artifacts/android-tool-suite-performance.apk`（聚合工作树视角）。

手机安装只验证共存和安装状态，实际功能及加载体验仍由用户手动验收。
