# 插件运行时 CLI

`ats.py` 只依赖 Python 标准库，用于创建声明式 Tool（Host／WebView renderer）、校验 format v3 项目、生成确定性
`.atsplugin`、验证包完整性、运行浏览器／Android Debug harness，以及从统一 contract 生成
Java/TypeScript 常量。

```powershell
python tools/plugin/ats.py lint examples/plugins/hello-web
python tools/plugin/ats.py pack examples/plugins/hello-web `
  --output build/plugin-runtime/hello-web.atsplugin
python tools/plugin/ats.py verify build/plugin-runtime/hello-web.atsplugin
python tools/plugin/ats.py generate --check
python -m unittest discover -s tools/plugin/tests -v
```

创建最小项目并启动带 Capability mock 与自动刷新的浏览器 harness：

```powershell
python tools/plugin/ats.py create ..\my-tool `
  --plugin-id example.my_tool `
  --title "我的工具" `
  --publisher example.publisher
python tools/plugin/ats.py dev ..\my-tool --mock ..\my-tool\dev-mocks.json
```

Tool 把 UI entry 统一指向 `ui/*.json`，根节点选择 Host 组件树或 `webview` renderer。CLI 会在打包前验证组件树、Query/Action、状态绑定、
Capability 对应和主页组件数据源；完整的全信任 Provider 示例位于独立的 `plugin-shizuku-auth` 仓库。普通插件还可用
`provides.capabilities[].workerEntry` 指向必需 JavaScript Worker 提供自定义能力，示例见
`examples/plugins/worker-capability`。敏感 Capability 安装后
默认为待决定，开发者不能用 manifest 或 mock 绕过宿主权限管理。

连接已安装该插件的 Android Debug Host：

```powershell
python tools/plugin/ats.py dev ..\my-tool --android --serial emulator-5554
```

CLI 会为模拟器或实体设备配置 `adb reverse`；WebView 始终保留插件自己的 HTTPS 虚拟源，宿主
只代理 `127.0.0.1` 上的精确 loopback 资源。退出 `ats dev` 时会清除
映射。Release manifest、Release WebView 和正式包均不包含此入口。

只有必须以宿主身份与系统交互的 `trusted-provider` 才能包含 `android/provider.apk`，并且必须传入 ECDSA P-256 私钥：

```powershell
python tools/plugin/ats.py pack path/to/plugin `
  --output build/provider.atsplugin `
  --signing-key path/to/publisher-private.pem `
  --public-key path/to/publisher-public.pem
```

私钥不得放入插件项目或 Git。离线验包时可用 `--public-key` 校验包内签名；宿主使用自己的
publisher trust store，不信任包内自报的公钥。
