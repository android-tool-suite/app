# Worker Capability 示例

这个普通 format v3 插件用 `javascript-worker` 提供 `sample.echo`，并从 Host-rendered 声明式页面消费它。

Worker 在 Android `JavaScriptSandbox` isolate 中运行，只能以插件自身身份调用清单声明且已授权的 Capability；它不会获得 `Context`、Binder、Shizuku、宿主类或文件路径。需要宿主身份与系统交互的实现必须使用单独的 `trusted-provider` 包。
