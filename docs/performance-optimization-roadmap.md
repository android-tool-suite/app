# Android Tool Suite 性能优化路线

## 目标与边界

本文记录宿主应用后续的性能测量与优化方向。当前观察是 Release 版明显比 Debug 版流畅，但在应用打开后的一段时间内，以及一级页面左右切换时，实机仍能感知到卡顿。P0.4 已于 2026-09-08 按用户要求暂缓，当前不创建 Macrobenchmark/Baseline Profile 模块，也不把性能工作作为 API1 退出门槛。

性能验收必须以正式签名 Release 或至少 `profileable`、不可调试的构建为主。Debug 构建会启用调试支持并失去部分运行时优化，只用于功能定位，不能把它显示的帧率直接当成发布质量结论。

## 已知现象

- 静止时屏幕可降到约 10 Hz，触摸其他应用或桌面通常能升到 120 Hz；本应用滑动时显示刷新率有时仍明显偏低。
- Release 版比 Debug 版流畅很多，说明编译模式、调试开销或运行时优化至少占一部分差异，但不能据此排除应用自身的慢帧。
- 首次打开后的短时间，以及页面切换、连续左右滑动时更容易感到卡顿；偶尔还会出现手势判定不灵敏。
- 当前主界面混合 Compose、`AndroidView` 插件小部件、Java 宿主状态和插件动态加载，任何一层都可能贡献 UI 线程或 RenderThread 压力。

## 基准场景

先固定一台 120 Hz 实体设备、同一系统版本、相同温度与电量状态，分别对 Release 和 Debug 执行下列场景；每项至少预热一次、测量五次：

1. 冷启动后等待首屏稳定，再在 10 秒内依次滑过五个一级页面。
2. 在主页与工具页之间连续往返滑动十次。
3. 点击相邻底栏入口十次；点击主页与设置这种不相邻入口各五次。
4. 在仓库页滚动已安装和可安装列表，并执行一次仅下载不安装的受控测试资源流程。
5. 分别在无外部插件、安装全部插件、主页含多个 `AndroidView` 小部件时重复以上场景。
6. 应用静置 30 秒后重复页面切换，观察从低刷新率恢复到交互刷新率的首批帧。

记录构建类型、提交号、设备刷新率模式、设备温度、每个场景的 `frameOverrunMs` P50/P90/P95/P99、`frameDurationCpuMs`、慢帧比例，以及对应 Perfetto trace。120 Hz 下单帧预算约为 8.3 ms，不能只用 60 Hz 的 16.7 ms 作为目标。

## 第一阶段：建立可重复测量

### Macrobenchmark

- 新建独立 `macrobenchmark` 模块，以 Release/benchmark 变体作为目标，使用 UI Automator 驱动上述分页与滚动场景。
- 使用 `FrameTimingMetric` 记录 CPU 帧时长和超预算时间；启动暂非当前重点，但可顺手保留 `StartupTimingMetric` 作为回归哨兵。
- 给主 Pager、五个底栏入口及关键列表添加稳定的测试标签，并启用 `testTagAsResourceId`，避免依赖中文文本定位。
- CI 先保存 JSON 和 trace，不立即设过严的硬阈值；积累稳定基线后再对 P95/P99 或回归比例设置门槛。
- 性能数字只采实体设备。模拟器可验证脚本可运行，但不作为帧率结论。

### Perfetto 与现场数据

- 在 Macrobenchmark 中开启 Compose composition tracing，区分重组、测量、布局、绘制、UI 线程阻塞和 RenderThread 压力。
- 给插件重载、仓库解析、页面停稳、列表状态持久化等关键路径加短小且稳定的 trace section。
- 开发期可用 `dumpsys gfxinfo` 与系统慢帧工具做快速筛查；需要长期观察时，再评估接入 JankStats，并附带当前一级页面、是否含 `AndroidView`、是否刚完成插件重载等 UI 状态。

## 第二阶段：按证据处理热点

以下是待验证假设，不应在没有 trace 的情况下同时修改。

### 状态与重组边界

- `HostUiState.revision` 仍是 Java 宿主状态的全局失效桥。检查一次 bump 实际使哪些页面、卡片和弹窗重组，逐步把高频或局部状态迁移为更细粒度的可观察状态。
- 检查五个缓存页面是否都读取全局 revision；不依赖当前变化的页面应保持可跳过，避免后台页重组与布局。
- 将昂贵的过滤、排序和插件描述符派生结果移入稳定缓存或 `derivedStateOf`，并确认输入集合具有稳定身份。
- 检查 `AndroidView` 小部件的 `update` 回调是否在无关重组中重复执行，以及插件 View 是否触发额外 requestLayout/invalidate。

### 主线程与插件生命周期

- 对 `loadPlugins`、V3 清单／权限恢复、声明式文档解析、WebView 首建、Native Provider、主页组件、仓库 JSON 和签名校验分别打点。
- 把磁盘读取、哈希和 JSON 解析等可移出的工作放到后台；声明式 Compose 节点、WebView 和 Provider 激活的最终创建仍遵守主线程要求。Provider 的同进程信任边界与 Web/声明式 Capability 权限要分别标注。
- 插件安装后当前实现会完整销毁并重建插件集合。测量后再决定是否能安全地做增量替换；在依赖关系、回滚和插件生命周期没有明确契约前，不先做局部热更新。

### Pager、列表与绘制

- 对比 `beyondViewportPageCount` 为 0 和 1 的实际结果：前者减少后台页面成本，后者避免相邻复杂页面反复创建。以往返滑动 P95/P99 和内存峰值共同决定。
- 检查页面根布局是否发生多次测量，尤其是 `BoxWithConstraints`、自定义小部件网格和嵌入 View。
- 对图标、形状、日期格式化、版本列表等容易重复创建的对象做 allocation 检查；只优化 trace 中频繁且有成本的项。
- 检查图片或效果图是否在 UI 线程解码、是否缺少尺寸约束和缓存；此项主要影响插件详情及插件自己的 Compose/View 页面。

### 刷新率策略

- 分开判断“应用实际产帧慢”和“系统没有把显示切到高刷新率”。刷新率面板只说明显示模式，不能替代 FrameTimingMetric。
- 复核 Android 15 `requestedFrameRate` 投票是否施加在正确的根 View、是否覆盖触摸后的惯性动画，以及多个子 View 的投票是否互相冲突。
- 不长期锁定最高刷新率；目标是交互期间及时升频，动画结束后允许系统降频。每次调整同时观察功耗与温度。

## 第三阶段：编译与发布侧优化

- 在可重复基准建立后生成 Baseline Profile，覆盖冷启动、进入五个一级页面、首次打开仓库和插件详情等常用路径。
- 为正式构建评估 R8/资源压缩。当前 Release 的 `minifyEnabled` 为 `false`；开启前需要验证反射加载的插件入口、序列化模型、Shizuku 与 Compose 相关保留规则，不能只凭 APK 变小判断收益。
- 对比无 Baseline Profile、部分编译和完全编译，确认 Release 比 Debug 流畅的差异究竟来自 ART 编译、调试器、Compose 检查还是代码路径本身。
- 将稳定的 Macrobenchmark 报告作为发布附件或 CI 产物，版本升级时做趋势比较。

## 建议实施顺序

1. 建立实体设备 Macrobenchmark 和 Perfetto 基线。
2. 先处理 trace 中确认的主线程 I/O、全局重组和重复插件/Widget 创建。
3. 再比较 Pager 缓存、布局与绘制策略。
4. 调整刷新率投票，并验证帧时序和功耗，而不是只看刷新率浮层。
5. 最后引入 Baseline Profile 与 R8，分别量化收益并保留回退方案。

每个优化提交只解决一类热点，附优化前后同设备、同场景、同构建类型的指标与 trace。功能正确性、插件回滚和数据兼容性验证继续作为性能改造的前置条件。

## 参考资料

- Android Developers：[Pager in Compose](https://developer.android.com/develop/ui/compose/layouts/pager)
- Android Developers：[Write a Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- Android Developers：[Capture Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
- Android Developers：[Slow rendering](https://developer.android.com/topic/performance/vitals/render)
- Android Developers：[JankStats](https://developer.android.com/topic/performance/jankstats)
- AndroidX 源码：[PagerState.kt](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/pager/PagerState.kt)
- Android 官方开源示例：[compose-samples](https://github.com/android/compose-samples)
