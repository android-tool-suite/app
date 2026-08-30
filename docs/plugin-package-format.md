# ATS Plugin Package Format

插件包使用 `.atsplugin` 扩展名，本质是一个受限 ZIP。宿主当前同时读取两代格式：

- **format v3**：插件运行时 的主格式，普通工具使用统一声明式 UI（Host 或 WebView renderer）、版本化 Capability、宿主存储和可选后台任务；
- **format v1/v2**：旧 API1 Android AAR/Compose 插件的冻结兼容格式，只用于既有插件迁移和回滚。

新插件必须使用 format v3。旧格式只为尚未迁移的 Phigros 与抽卡插件继续可用，不再增加 API；所有剩余插件完成迁移并通过迁移、恢复、业务与降级测试后即可退出，不附加版本数量或日历时间要求。

## 1. Format v3 目录结构

最小 Web Tool：

```text
example.atsplugin
├─ manifest.json
├─ ui/
│  └─ main.json
├─ web/
│  ├─ index.html
│  ├─ app.js
│  └─ styles.css
└─ META-INF/
   └─ ats-integrity.json
```

按需还可以包含：

```text
workers/*.js              JavaScriptSandbox 后台任务
workers/*.wasm            预留的可选 WASM worker；当前 Android Backend 会明确拒绝执行
ui/*.json                 宿主渲染的声明式 UI v1 文档
android/provider.apk      受信 Native Provider
META-INF/ats-signature.sig
```

format v3 不包含根级 `plugin.apk`，也不允许 ZIP 目录占位项、未知顶层目录、绝对路径、反斜杠、路径穿越、重复路径或仅大小写不同的路径。所有入口都必须位于包内并由清单引用。

权威 schema 位于 `../runtime-contract/src/main/resources/contracts/manifest-v3.schema.json`；Java 与 TypeScript 绑定由同一契约源生成。

## 2. Manifest v3

下面是最小 Web Tool 的结构示例；字段细节和上限以 schema 为准：

```json
{
  "$schema": "https://android-tool-suite.test/contracts/manifest-v3.schema.json",
  "format": "ats-plugin",
  "formatVersion": 3,
  "plugin": {
    "id": "sample_notes",
    "title": "示例笔记",
    "description": "使用宿主存储保存笔记。",
    "version": "1.0.0",
    "versionCode": 1,
    "minHostVersionCode": 23,
    "publisher": "example.publisher",
    "kind": "tool"
  },
  "platforms": ["android"],
  "runtime": {
    "ui": [
      { "id": "main", "type": "declarative", "entry": "ui/main.json" }
    ],
    "background": [],
    "providers": []
  },
  "requires": {
    "plugins": [],
    "capabilities": [
      { "id": "storage", "version": "^1.0.0", "optional": false, "scopes": {} }
    ]
  },
  "provides": { "capabilities": [] },
  "contributes": {
    "tools": [{ "id": "main", "uiEntry": "main" }],
    "homeWidgets": []
  },
  "datasets": [],
  "tasks": []
}
```

重要规则：

- 插件、入口、Capability、Dataset 和任务 ID 使用稳定的小写 ID；版本使用 SemVer；
- 新包的 `runtime.ui` 统一使用 `declarative` 与 `ui/*.json`；文档根节点选择 Host 组件树或 `webview` renderer。旧 `type: web` 仅保留读取兼容；
- `requires.capabilities` 同时声明版本范围、是否可选和最小 scope；未声明的能力不可调用；
- `runtime.background` 可声明 `javascript-worker`、`provider-task` 和预留的 `wasm-worker`；后台任务不能依赖常驻 WebView；
- `datasets` 声明类别、格式版本、敏感性、恢复方式、上限和依赖；恢复由宿主 staging、校验后原子切换；
- `plugin.kind = tool` 不得包含 Native Provider；它可以贡献 UI/Tool，也可以通过必需的 `javascript-worker`（未来可选 WASM Worker）提供 Capability；
- `plugin.kind = trusted-provider` 必须包含受信 Native Provider，只用于必须以宿主身份访问 Android／Shizuku 等系统能力的全信任代码；它仍可像普通插件一样贡献 UI、Tool、主页组件和 Worker；
- `provides.capabilities` 必须列出版本、方法集合以及恰好一个实现入口：普通插件使用 `workerEntry`，底层 Provider 使用 `providerEntry`。普通插件提供能力不会获得宿主身份。

## 3. 完整性与 Publisher 签名

`META-INF/ats-integrity.json` 使用固定格式：

```json
{
  "algorithm": "sha256",
  "formatVersion": 1,
  "files": [
    { "path": "manifest.json", "size": 1234, "sha256": "..." },
    { "path": "web/index.html", "size": 456, "sha256": "..." }
  ]
}
```

文件列表按路径排序，覆盖除 `META-INF/ats-integrity.json` 和 `META-INF/ats-signature.sig` 外的全部文件。宿主在写入活动 generation 前验证项目集合、大小和 SHA-256；损坏包不会替换当前版本。

普通 Tool 在本地手动导入时可以没有 publisher 签名，并会显示为未经仓库验证。`trusted-provider` 必须包含 `android/provider.apk` 与 `META-INF/ats-signature.sig`：签名算法为 ECDSA P-256/SHA-256，签名对象是 `ats-integrity.json` 的原始字节。宿主只使用内置或 Debug Developer Mode 明确加入的 publisher 公钥，不信任包内自报公钥。普通 Tool 即使不在清单中引用，夹带 `android/provider.apk` 也会被 CLI 和宿主拒绝。

底层 Provider 与宿主同进程运行，因此签名代表来源和完整性，不构成恶意代码隔离。它只能通过公开 SDK 注册清单中声明的高层 Capability 或 `provider-task`；不得把通用 Shell、Host 内部类或隐含官方权限暴露给其他插件。全信任包可以同时贡献普通 UI/Tool；这些贡献不会降低同包原生代码的信任级别。普通插件的 Worker Provider 则处于受限运行时，可以像其他插件一样组合和替换 Capability。

## 4. CLI 工作流

普通 Web 插件不需要 Android SDK 或 Gradle：

```powershell
python tools\plugin\ats.py create sample-notes `
  --plugin-id sample_notes `
  --title "示例笔记" `
  --publisher example.publisher

python tools\plugin\ats.py lint sample-notes
python tools\plugin\ats.py pack sample-notes --output sample-notes.atsplugin
python tools\plugin\ats.py verify sample-notes.atsplugin
python tools\plugin\ats.py dev sample-notes --android --serial <设备序列号>
```

包含 Native Provider 时必须签名，并在离线验收中使用对应公钥：

```powershell
python tools\plugin\ats.py pack provider-project --output provider.atsplugin `
  --signing-key <publisher-private.pem> `
  --public-key <publisher-public.pem>
```

私钥不得进入仓库或插件包。正式 Publisher 私钥由 CI/发布流程保管；本地 Debug key 不能替代正式发布验收。

## 5. WebView renderer 与能力边界

宿主把 Web 资源加载到每插件独立的虚拟 HTTPS origin，并注入版本化消息传输层。页面默认不能访问 `file://`、任意导航、Cookie、DOM 持久化或 Host Java 对象；外部网络必须通过声明了 origin/method scope 的 `network.request`。主题、生命周期、返回、错误和取消通过 插件运行时 SDK 传递。

复杂交互可以使用任意能产出静态 Web 资源的框架；插件不得复制宿主设计 token。项目内官方插件应使用 SDK 提供的主题变量和 `SuiteDesignSystem` 对应的语义组件，覆盖浅色、深色、窄屏、横屏、加载、空、错误和离线状态。

## 6. 声明式 UI

所有新工具把 UI entry 声明为：

```json
{ "id": "main", "type": "declarative", "entry": "ui/main.json" }
```

声明式 UI v1 只提供 `column`、`row`、`section`、`card`、`text`、`icon`、`status`、`metric`、`notice`、`button`、`state`、`divider` 和 `spacer`。`icon` 只接受契约列出的共享 Material 图标名称，避免插件用字符或自绘图标形成另一套视觉语言；普通说明使用 `notice.tone = neutral`，成功、警告、危险和信息才使用对应语义色。数据由启动 Query 写入受限状态路径，按钮 Action 只能调用 manifest 已声明的 Capability；没有任意表达式、HTML、脚本或宿主类访问。根节点、节点数、层级、文本、payload、deadline 和 action 引用都在安装前校验。

简单状态工具、设置页和动作面板使用 `body.type = column`。复杂图表、编辑器、画布和高度自定义交互使用 WebView renderer：

```json
{
  "formatVersion": 1,
  "body": { "type": "webview", "entry": "web/index.html" }
}
```

WebView 根文档不能同时定义 Host 状态、Query 或 Action；页面通过统一 RPC 调用 Capability。两种 renderer 共用宿主详情顶栏、主题、语义色、状态组件、Capability Router 和权限管理。
WebView 页面还必须使用宿主 `theme.css` 提供的 `--ats-type-*` 字号、行高和字重 token；插件根内容不重复绘制宿主详情标题，卡片和状态图标应与共享 Compose 组件保持同一信息层级。

## 7. 插件权限

`requires.capabilities` 既是最小能力声明，也是权限请求上限。应用管理页按插件逐项展示权限说明、风险和 scope：

- `app` 与只访问本插件命名空间的 `storage` 是运行基础，始终允许且不列入面向用户的权限管理；
- 网络、所选文件、剪贴板、通知和后台任务由用户允许；
- 无障碍管理与 Shizuku 连接属于敏感操作，默认不允许；
- 授权绑定规范化 scope 的 SHA-256 指纹，升级时扩大或改变范围会重新进入待决定状态；
- 撤销后新调用和事件立即被拒绝，在途调用会取消，后台任务停止调度；权限决定和拒绝只记录时间、插件、Capability 与结果，不记录请求载荷。

普通 V3 插件的 Host Action、WebView RPC、Worker、事件和后台任务都只能经过 Capability Router：未声明或未授权的调用在 Provider 执行前被拒绝，撤销还会取消在途调用和插件持有的临时 handle。普通插件提供 Capability 时，Worker 的下游调用以提供者自身身份再次检查权限，不能继承消费者授权。`trusted-provider` 与 API1 `plugin.apk` 仍是同进程可信代码，逐项 Capability 开关不能约束其原生代码，因此完全信任包自身不显示权限列表；管理页会明确区分这条边界。

## 8. 安装、更新与回滚

宿主先在 staging 目录完成路径、schema、完整性、平台和签名校验，再把整个包切换为新的只读 generation。失败时保留旧 generation；Provider 更新在下次宿主冷启动激活。删除插件时同时关闭 session、任务和 Provider effect，但业务 Dataset 仍按显式数据管理流程处理。

正式仓库、调试仓库和手动导入是不同来源：

- 正式/调试索引还会校验索引签名、包大小、SHA-256、插件 ID、版本和最低宿主版本；
- 手动导入不取得仓库信誉；
- 无论来源，包内 format v3 校验规则相同；Native Provider 仍必须命中受信 publisher key。

## 9. 数据格式兼容与降级

需要支持历史版本选择的插件在仓库根目录维护 `data-compatibility.json`：

```json
{
  "schemaVersion": 1,
  "dataFormatVersion": 1,
  "minReadableDataFormatVersion": 0,
  "maxReadableDataFormatVersion": 1
}
```

- `dataFormatVersion` 是当前版本可能写入的最高业务数据格式，不是插件包格式；
- 可读范围必须包含自身格式；没有声明的历史版本按只读 `v0` 处理；
- 写入旧版无法理解的结构前必须递增数据格式，并用测试证明可读范围；
- 降级目标无法读取当前格式时，宿主必须阻止安装；同一格式内可以提示后继续；
- 代码 generation 回滚只恢复插件包，不代表回滚业务 Dataset。降级前应导出 `.atsbackup` Dataset 或插件领域标准格式。

format v3 Dataset 的格式、恢复模式和依赖由 manifest 声明；API1 的物理路径、删除能力和迁移验收统一由外层 [数据管理文档](../../docs/data-management.md) 约束。

## 10. Legacy format v1/v2

旧包结构仍为：

```text
legacy.atsplugin
├─ manifest.json
├─ plugin.apk
└─ assets/
```

`plugin.entryClass` 实现 `com.androidtoolsuite.app.plugin.api.ToolPlugin`，宿主通过 `DexClassLoader` 在同一进程加载。format v2 在 v1 基础上增加整数 `versionCode`、`minHostVersionCode` 和 `sdkVersion`。这条路径只接受兼容性、迁移和安全修复；新 Capability、任务、Dataset 与 Web UI 只进入 format v3。

API1 的停止发布、Registry 拒绝和代码删除必须按外层 [插件运行时架构](../../docs/plugin-runtime-architecture.md) 的迁移验收执行；只有所有剩余插件完成迁移并通过迁移、恢复、业务与降级测试后才能删除。
