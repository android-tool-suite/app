# Phigros 查分助手

这是一个 Android Tool Suite 外部插件。插件参考 `Catrong/phi-plugin` 重构，支持尝试获取 SessionToken、抓取 Phigros 远程云存档，也支持粘贴成绩 JSON；解析后按 P3+B27 估算 RKS，并按预计总 RKS 增量给出推分建议。

## 功能

- 插件版本：`1.1.0`，要求宿主 `host_app>=1.1.0`。
- SessionToken 获取尝试：按 `phi-plugin` 的 TapTap device code 流程生成登录链接，用户授权后轮询 TapTap token，再用 LeanCloud `authData.taptap` 换取 `sessionToken`。
- 云存档抓取：按 `phi-plugin` 的 `SaveManager` 流程先请求 `/users/me`，再查 `/gamesaves/?where=...&include=cover,gameFile`，选择最新存档并下载 `gameFile.url`。
- 存档解析：本地解压 zip，AES-256-CBC 解密 `gameRecord`，读取成绩、ACC 和 FC/AP 信息。
- 定数表：下载并缓存 `7aGiven/PhigrosLibrary` 的 `difficulty.tsv`。
- 查分：按单曲 RKS 排序展示成绩，支持曲名、难度、等级搜索。
- 推分建议：参考 `phi-plugin` 的思路，计算下一次可见 RKS 涨分所需的最低目标 ACC。

## 在线资料调研

公开项目里常见的查分路线主要有两类：

- 云存档解析：`7aGiven/PhigrosLibrary` 是 C/C++ 实现的 Phigros 云存档解析库，文档说明其封装了 B19、BestN、目标 ACC 等常用函数。项目 README 也提醒不要大规模查分，避免对服务器造成压力。
- 当前查分口径：`catrong/phi-plugin` 的 `getB19(e, num)` 已按 P3+B27 处理，代码中累加 3 个 Phi 候选和前 27 个 Best，并以 30 为除数。
- SessionToken 获取：`Catrong/phi-plugin` 的 `lib/getQRcode.js`、`lib/TapTap/TapTapHelper.js`、`lib/TapTap/LCHelper.js` 使用 TapTap device code + MAC 签名 + LeanCloud 登录换取 `sessionToken`。
- Bot/服务封装：`Walkersifolia/PhigrosLibrary-plugin`、`XTxiaoting14332/nonebot-plugin-phigros`、`Sczr0/Phi-Backend` 等项目基于云存档解析做 Bot 或后端查询。
- 离线存档：`sakimidare/rks-calc` 采用 Android Backup 存档计算 Phigros RKS，适合不接触账号 token 的离线流程。

本插件当前可直接读取最新云存档：使用用户输入或登录换取的 SessionToken 请求 TapTap/LeanCloud，下载 `gameFile.url`，在本地解密 zip 中的 `gameRecord`。SessionToken 不会自动保存。

移动端插件内暂未内置二维码图片生成库；当前会显示 TapTap 登录链接，并提供复制/浏览器打开按钮。若后续宿主支持图片视图或插件资源库，可以把该链接渲染为二维码。

## 支持的 JSON

最稳妥的格式是数组：

```json
[
  {
    "title": "Spasmodic",
    "level": "IN",
    "difficulty": 15.8,
    "score": 995000
  },
  {
    "title": "Igallta",
    "level": "IN",
    "difficulty": 15.7,
    "accuracy": 99.4
  }
]
```

也支持对象里包含 `records`、`scores`、`songs`、`charts`、`data`、`best`、`b19` 数组，或简单的“曲名 -> 难度 -> 成绩”嵌套对象。

可识别字段：

- 曲名：`title`、`song`、`songName`、`name`、`id`、`chart`
- 难度名：`level`、`difficultyName`、`diff`、`chartLevel`、`rank`
- 定数：`constant`、`difficulty`、`rating`、`ds`、`levelValue`、`chartConstant`
- 分数：`score`、`best`、`record`、`value`
- ACC：`accuracy`、`acc`、`rate`

## 计算规则

单曲 RKS 使用社区常见公式：

```text
acc < 55%: 0
acc >= 55%: 定数 * ((acc - 55) / 45)^2
```

总体 RKS 参考 `catrong/phi-plugin` 的当前口径估算为 P3 + B27 后除以 30。P3 是满分记录中按单曲 RKS 排序的前三个候选；B27 是全成绩按单曲 RKS 排序的前 27 个。

## 构建

```powershell
gradle :plugins:phigros-advisor:packagePlugin
```

输出：

```text
plugins/phigros-advisor/build/outputs/atsplugin/phigros-advisor.atsplugin
```

## 版本依赖

宿主应用从 `1.1.0` 起暴露内置能力插件 `host_app`。插件清单可以使用兼容旧格式的依赖声明：

```json
{
  "dependencies": [
    "host_app>=1.1.0"
  ]
}
```

旧的纯 ID 依赖仍然有效，例如 `"shizuku_auth"`。支持的比较符包括 `>=`、`>`、`<=`、`<`、`=`、`==`。
