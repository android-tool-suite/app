package com.example.shizukuaccessibilitygrant.plugins.phigros;

import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog;

import java.util.Collections;
import java.util.LinkedHashSet;

public final class PhigrosAdvisorPluginDescriptor {
    public static final String ID = "phigros_advisor";

    private PhigrosAdvisorPluginDescriptor() {
    }

    public static ImportedPluginDescriptor create() {
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        permissions.add(PluginPermissionCatalog.NETWORK);
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        return new ImportedPluginDescriptor(
                ID,
                "Phigros 查分助手",
                "抓取 Phigros 云存档或导入成绩 JSON，按 P3+B27 估算 RKS，并给出按收益排序的推分建议。",
                "1.1.0",
                "Android Tool Suite",
                "1",
                "com.example.shizukuaccessibilitygrant.plugins.phigros.PhigrosAdvisorPlugin",
                "",
                permissions,
                new LinkedHashSet<>(),
                dependencies,
                Collections.emptyList()
        );
    }
}
