# ATS Plugin Package Format

## Dataset 合并规则

格式 v3 的 JSON／ZIP Dataset 可以同时声明 `restoreModes: ["merge", "replace"]` 和 `mergePolicy`。接收方决定当前可用方式，因此旧备份仅声明替换时，新插件仍可提供合并；数据格式版本仍必须兼容。没有合并规则的整体二进制缓存继续只支持替换。

JSON 策略为 `{ "format": "json", "rule": { "kind": "object" } }`，格式必须与 `validator` 一致。默认保留本机已有值并补充缺失字段。`object.fields` 声明子字段规则；`map.items` 对每个键应用规则；`array.keys` 使用稳定字段构成记录身份，`items` 处理同一身份的冲突，`sortBy`／`descending` 控制排序，`incomingFirst` 控制补充顺序。`requireAllKeys` 拒绝身份缺失的记录。字段路径支持点号和 `|` 备选字段；`aliases` 在身份比较时统一旧标识，不改写原始记录。

`set` 按完整值去重，`current` 保留本机整项，`newer.timestamp` 仅采用时间明确较新的整项，`or` 合并布尔证据。`legacySets` 的目标字段在缺失时，根据 `when` 的布尔值从 `source` 补充旧集合；`constants` 写入迁移后的固定字段。较新的存档、当前登录凭据、各账号设置与原始历史分别应用对应策略。

ZIP 策略为 `{ "format": "zip", "entries": [{ "pattern": "history/*.json", "rule": { "kind": "array", "keys": ["id"] } }] }`。规则仅作用于包内安全路径下的 JSON；无匹配规则的同名文件保留本机版本，新文件按路径补充。路径穿越、重复条目、过大内容或不符合规则的结构会拒绝合并。全部规则由宿主解释，不执行插件代码；结果仍经过 Dataset staging、容量与格式校验，成功后才原子激活，失败保留原有数据。敏感 Dataset 继续使用本机加密存储。

## 可配置主页小部件

`contributes.homeWidgets` 声明预设，同一个预设可由宿主创建多个独立实例。预设包含 `id`、`title`、`template`、`dataSource`、`sizes`，以及可选 `configurationSource`；两个来源方法均须属于清单声明的 Capability。

模板支持 `status`、`metric`、`action`、`trend`、`rows`。可配置预设的数据调用参数为 `{widgetId, instanceId, configuration}`；无配置的旧预设仍收到空对象。`trend` 额外返回按时间严格递增的 `points: [{timestamp, value}]`，最多 512 个有限、非负数值节点；单点只显示节点。`rows` 返回最多四个 `{label, value}` 项。宿主只保存受验证的展示字段。

配置来源收到 `{widgetId, instanceId, configuration, migrating}`，返回 `{configuration, fields}`。字段包含 `key`、`label`、`type`；类型为 `select`、`multi`、`toggle`。选择字段提供 `options: [{value, label}]`，多选使用 `minItems`/`maxItems`。最多 16 个字段、每字段 128 个选项；配置最大 8 KiB。配置来源读取本地选项，不写入业务设置；账号变化后重新返回可选卡池和兼容的草稿值。

宿主保存实例身份、配置修订及排列；取消编辑不提交。首次迁移可把已有账号作为默认值，之后必须以传入的固定配置读取；账号失效时返回空态，不能退回当前浏览账号。缓存按实例、预设、配置、包 generation 和 Dataset 修订隔离。

尺寸声明允许宽高各 1–4 网格单位，例如 `1x1`、`1x2`、`2x1`、`2x2`、`4x2`；宿主仅提供该预设明确支持的组合。配置界面按“布局”选择预设与受支持尺寸的组合。4×2 的 `trend` 在左侧显示数值、账号和范围，右侧使用约一半宽度绘制真实历史及日期；2×2 使用上下布局。

点按实例打开所属工具时，Web 容器在首次 `ready.payload` 或已保留页面的 `app.visibilityChanged` 中提供一次性的可选 `widgetEntry: {id, instanceId, presetId, configuration}`。`id` 是本次打开的临时标识，插件应忽略已处理标识，校验绑定对象仍存在并保留未保存输入保护。字段只传给该实例所属插件，不授予能力权限、不携带其他插件的数据、不写入启动 URL；普通打开不重复播放旧入口。旧插件可忽略该可选字段。固定对象只改变当前页面会话的查看对象，普通账号切换继续按插件原有逻辑保存。

插件可在用户操作后调用 `app.openWidgetSettings({instanceId?})` 打开同一原生配置界面；省略 ID 时管理本插件全部实例。入口绑定当前可见 UI 会话，不能操作其他插件实例。Native plugin-sdk 无新增接口要求。

可信 Provider 与宿主同进程、同 UID，可以自行使用 Android/Binder 和宿主共享的 `rikka.shizuku.*` 客户端（当前 13.1.5，插件 compileOnly）。SDK 用于 Provider 注册与 Capability 契约，不要求将底层操作逐项实现为宿主桥；旧 `TrustedPlatformBridge` 仅作按需兼容。普通插件没有此原生装载路径，仍使用 Capability Router。

`.atsbackup` 可携带已安装 format v3 原始包，包括未发布的本地项目。恢复复用本地安装校验，不自动授予权限或启用新插件；历史 API1 载荷仍拒绝执行。详见聚合工作区 `docs/architecture/data-management.md`。

插件包使用 `.atsplugin` 扩展名，本质是一个受限 ZIP。宿主当前同时读取两代格式：

- **format v3**：当前运行时的主格式，普通工具使用统一声明式 UI（Host 或 WebView renderer）、版本化 Capability、宿主存储和可选后台任务；
- **format v1/v2**：已退出的 API1 Android AAR/Compose 格式，只保留历史归档识别，不再安装或执行。

所有插件必须使用 format v3。公开 SDK 与宿主不再提供 API1 创建或装载入口；数据迁移、恢复和降级应按目标版本、归档 fixture 与对应测试验证，不能由包格式支持推定所有历史场景均已通过。

主体 2.0.0 完成旧运行时管理代码退出：历史安装记录不再参与插件列表、启停、依赖、导出或同 ID 安装冲突检查。设备上的旧文件和业务数据不会自动删除；未迁移的数据需先使用支持 Migration Bridge 的旧版本导出，再通过数据备份恢复。旧宿主迁移包只恢复兼容的应用设置，不恢复旧插件包及其启用状态。

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
    "minAndroidApi": 26,
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
- `plugin.minAndroidApi` 可选，省略时按 API 24；依赖必需 JavaScript Worker 的插件至少声明 26，宿主在安装和启用前检查；
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

`requires.capabilities` 既是最小能力声明，也是权限请求上限。插件详情的功能权限页逐项展示权限说明、风险和 scope：

- `app` 与只访问本插件命名空间的 `storage` 是运行基础，始终允许且不列入面向用户的权限管理；
- 网络、所选文件、保存文件、剪贴板、通知和后台任务由用户允许；
- 无障碍管理与 Shizuku 连接属于敏感操作，默认不允许；
- 授权绑定规范化 scope 的 SHA-256 指纹，升级时扩大或改变范围会重新进入待决定状态；
- 撤销后新调用和事件立即被拒绝，在途调用会取消，后台任务停止调度；权限决定和拒绝只记录时间、插件、Capability 与结果，不记录请求载荷。

普通 V3 插件的 Host Action、WebView RPC、Worker、事件和后台任务都只能经过 Capability Router：未声明或未授权的调用在 Provider 执行前被拒绝，撤销还会取消在途调用和插件持有的临时 handle。普通插件提供 Capability 时，Worker 的下游调用以提供者自身身份再次检查权限，不能继承消费者授权。`trusted-provider` 是唯一仍会装载原生代码的格式，逐项 Capability 开关不能约束其原生代码，因此完全信任包自身不显示权限列表；插件详情明确区分这条边界。

`file.export` 不接受宿主路径或大块内联字节。插件先通过 `storage.blob.*` 将内容分块写入自身命名空间，再在用户手势中调用 `file.export.save`；宿主校验 Blob 摘要、大小和声明的 MIME scope 后打开系统“另存为”界面。选择器打开期间 Blob 若发生变化，导出失败且不会把新旧内容混写到目标文件。

`network.request` 默认拒绝 `Authorization`、Cookie、Host、代理和连接级请求头。确需认证头的登录协议必须在网络 scope 的 `headers` 中逐项声明 `authorization` 或 `cookie`；声明参与权限指纹并显示在功能权限页。Host、`Proxy-Authorization` 与连接级请求头始终不可开放。

## 8. 安装、更新与回滚

宿主先在 staging 目录完成路径、schema、完整性、平台和签名校验，再把整个包切换为新的只读 generation。失败时保留旧 generation；Provider 更新在下次宿主冷启动激活。删除插件时同时关闭 session、任务和 Provider effect，但业务 Dataset 仍按显式数据管理流程处理。

正式仓库和手动导入是不同来源：

- 正式索引还会校验索引签名、包大小、SHA-256、插件 ID、版本和最低宿主版本；
- 手动导入不取得仓库信誉；
- 无论来源，包内 format v3 校验规则相同；Native Provider 仍必须命中受信 publisher key。

应用内文件导入、仓库安装、ADB 调试导入和备份中的插件包恢复共用安装器。降级与同版本包替换不需要先移除插件：切换前检查插件 ID、最低宿主/Android 版本及数据契约，沿用原子 generation 切换，保留 Dataset 和启用状态，事务失败恢复上一代安装包。降级或同版本替换时，同 ID Dataset 的 `formatVersion` 和 `sensitive` 必须保持兼容；仓库安装还检查已签名索引中的数据可读范围。目标版本未声明的 Dataset 保留，不自动清理或改写。

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

format v3 Dataset 的格式、恢复模式和依赖由 manifest 声明；历史归档的只读兼容范围由外层 [数据管理文档](../../docs/architecture/data-management.md) 约束。

## 10. 历史 format v1/v2

旧包结构仍为：

```text
legacy.atsplugin
├─ manifest.json
├─ plugin.apk
└─ assets/
```

这类包曾包含 `plugin.entryClass` 和 `plugin.apk`。当前宿主只在历史归档探测器中识别这些字段，以给出明确的不兼容说明；本地导入、Debug ADB、仓库安装和归档恢复均不会重新装载或执行其代码。需要继续使用的数据必须由已经完成迁移的 format v3 插件读取宿主管理的 Dataset。
