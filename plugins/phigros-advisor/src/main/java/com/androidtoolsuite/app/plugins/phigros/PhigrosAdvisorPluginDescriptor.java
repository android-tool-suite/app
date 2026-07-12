package com.androidtoolsuite.app.plugins.phigros;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

import java.util.Collections;
import java.util.LinkedHashSet;

public final class PhigrosAdvisorPluginDescriptor {
    public static final String ID = "phigros_advisor";

    private PhigrosAdvisorPluginDescriptor() {
    }

    public static ImportedPluginDescriptor create() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>();
        return new ImportedPluginDescriptor(
                ID,
                "Phigros Data Studio",
                "本地加密管理 SessionToken，查询 Bn/B30、RKS 明细与推分时间线，并生成 B30 和个人信息效果图。",
                "2.0.5",
                "Android Tool Suite · Phigros Data Studio",
                "1",
                "com.androidtoolsuite.app.plugins.phigros.PhigrosAdvisorPlugin",
                "",
                dependencies,
                Collections.emptyList()
        );
    }
}
