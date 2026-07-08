# Shizuku Accessibility Grant

一个最小 Android 示例 App：通过 Shizuku 获取 shell/root 身份，列出手机里已安装的无障碍服务，并由用户手动点击按钮启用或停用指定服务。

## 使用方式

1. 在手机上安装并启动 Shizuku。
2. 用 Android Studio 打开本项目。
3. 构建并安装 `app` 模块。
4. 打开 App，授予 Shizuku 权限。
5. 在列表里选择你信任的无障碍服务，点击“启用”或“停用”。
6. 可用搜索框按应用名、服务名或包名过滤列表。
7. 可收藏常用服务；打开“启动时自动启用收藏服务”后，每次进入 App 会自动启用已收藏且仍安装的服务。

## 构建要求

- JDK 17
- Android SDK 35
- Android 7.0+，即 `minSdk 24`
- Gradle 8.9 或更新版本

命令行构建：

```powershell
gradle :app:assembleDebug
```

## 安全边界

无障碍权限非常敏感，能读取屏幕内容并代表用户执行操作。这个示例不会静默批量授权，只允许用户在界面里对单个已安装服务执行启用或停用。自动启用功能只作用于用户手动收藏过的服务，并且需要先打开“启动时自动启用收藏服务”开关。

底层实现会通过 Shizuku UserService 以 shell/root 身份执行：

```text
settings get secure enabled_accessibility_services
settings put secure enabled_accessibility_services ...
settings put secure accessibility_enabled 1/0
```

部分系统 ROM 可能会拦截或覆写 secure settings，遇到这种情况需要以具体设备行为为准。
