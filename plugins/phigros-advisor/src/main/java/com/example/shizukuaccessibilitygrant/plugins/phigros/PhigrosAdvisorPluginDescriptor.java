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
                "Phigros Data Studio",
                "本地加密管理 SessionToken，查询 Bn/B30、RKS 明细与推分时间线，并生成 B30 和个人信息效果图。",
                "2.0.5",
                "Android Tool Suite · Phigros Data Studio",
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
