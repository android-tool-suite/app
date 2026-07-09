package com.example.shizukuaccessibilitygrant.plugin.api;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PluginPermissionCatalog {
    public static final String SHIZUKU = "shizuku";
    public static final String SHELL_EXEC = "shell.exec";
    public static final String ACCESSIBILITY_SETTINGS = "accessibility.settings";
    public static final String PACKAGE_QUERY = "package.query";
    public static final String FILE_PICKER = "file.picker";

    private PluginPermissionCatalog() {
    }

    public static Set<String> knownPermissions() {
        return new LinkedHashSet<>(Arrays.asList(
                SHIZUKU,
                SHELL_EXEC,
                ACCESSIBILITY_SETTINGS,
                PACKAGE_QUERY,
                FILE_PICKER
        ));
    }

    public static String label(String permission) {
        switch (permission) {
            case SHIZUKU:
                return "使用 Shizuku";
            case SHELL_EXEC:
                return "执行 Shell 命令";
            case ACCESSIBILITY_SETTINGS:
                return "修改无障碍设置";
            case PACKAGE_QUERY:
                return "读取应用列表";
            case FILE_PICKER:
                return "打开文件选择器";
            default:
                return permission;
        }
    }

    public static String description(String permission) {
        switch (permission) {
            case SHIZUKU:
                return "允许插件请求宿主使用内部 Shizuku 授权和 UserService 连接。";
            case SHELL_EXEC:
                return "允许插件通过宿主调用 Shizuku UserService 执行命令。";
            case ACCESSIBILITY_SETTINGS:
                return "允许插件修改 secure settings 中的无障碍服务配置。";
            case PACKAGE_QUERY:
                return "允许插件读取设备上与自身功能相关的应用和组件信息。";
            case FILE_PICKER:
                return "允许插件请求宿主打开系统文件选择器。";
            default:
                return "未知权限，请只授予你信任的插件。";
        }
    }
}
