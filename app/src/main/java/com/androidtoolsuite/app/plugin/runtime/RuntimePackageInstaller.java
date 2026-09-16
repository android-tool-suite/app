package com.androidtoolsuite.app.plugin.runtime;

import android.os.Build;
import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.runtime.contract.ContractException;
import java.io.IOException;

/** Shared local/backup install policy. Caller confirms only after completing its transaction. */
public final class RuntimePackageInstaller {
    private RuntimePackageInstaller() { }

    public static PluginPackageStore.InstallSession begin(PluginPackageStore store, byte[] bytes,
                                                          String expectedId) throws IOException, ContractException {
        if (!PluginPackageArchive.hasFormatV3Manifest(bytes)) {
            throw new IOException("历史 API1 插件包仅供识别，不能安装；请选择 format v3 包");
        }
        PluginPackageStore.InstallSession session = store.install(bytes, "local", "", false);
        try {
            PluginPackageStore.InstalledPlugin installed = store.find(session.pluginId);
            if (installed == null) throw new IOException("安装后的插件不可读");
            if (expectedId != null && !expectedId.equals(session.pluginId)) throw new IOException("插件包 ID 与归档项目不一致");
            if ("plugin_manager".equals(session.pluginId)) throw new IOException("插件 ID 与内置插件冲突");
            for (HostTool tool : ToolRegistry.createBuiltInPlugins()) {
                try {
                    if (tool.id().equals(session.pluginId)) throw new IOException("插件 ID 与内置插件冲突");
                } finally { tool.onDestroy(); }
            }
            if (installed.manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE) throw new IOException("当前应用版本不兼容此插件");
            if (installed.manifest.plugin.minAndroidApi > Build.VERSION.SDK_INT) throw new IOException("当前 Android 版本不兼容此插件");
            return session;
        } catch (IOException | RuntimeException error) {
            store.rollbackInstall(session);
            throw error;
        }
    }
}
