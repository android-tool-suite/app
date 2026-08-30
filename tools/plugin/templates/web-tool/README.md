# @PLUGIN_TITLE@

这是 `@PLUGIN_ID@` 的 Android Tool Suite 插件运行时 Web Tool 源码。

```powershell
python path\to\ats.py lint .
python path\to\ats.py dev .
python path\to\ats.py pack . --output build\plugin.atsplugin
```

页面必须使用 `/__ats__/theme.css` 中的共享 token。持久数据、网络、文件和系统行为通过声明过的 ATS Capability 调用，不使用 WebView DOM Storage 或远程导航。
