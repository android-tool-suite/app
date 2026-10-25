package com.androidtoolsuite.app.plugin.runtime;

import android.os.Build;
import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.update.UpdateCatalog;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import java.io.IOException;

/** Shared UI, repository, ADB and backup install policy. Caller completes the transaction. */
public final class RuntimePackageInstaller {
    private RuntimePackageInstaller() { }

    public static PluginPackageStore.InstallSession begin(PluginPackageStore store, byte[] bytes,
                                                          String expectedId) throws IOException, ContractException {
        return begin(store, bytes, expectedId, "local", "", null);
    }

    public static PluginPackageStore.InstallSession begin(PluginPackageStore store, byte[] bytes,
                                                          String expectedId, String source, String channel)
            throws IOException, ContractException {
        return begin(store, bytes, expectedId, source, channel, null);
    }

    public static PluginPackageStore.InstallSession beginRepository(PluginPackageStore store, byte[] bytes,
                                                                    UpdateCatalog.PluginRelease release)
            throws IOException, ContractException {
        return begin(store, bytes, release.id, release.releaseUrl, release.channel, release);
    }

    private static PluginPackageStore.InstallSession begin(PluginPackageStore store, byte[] bytes,
                                                           String expectedId, String source, String channel,
                                                           UpdateCatalog.PluginRelease release)
            throws IOException, ContractException {
        if (!PluginPackageArchive.hasFormatV3Manifest(bytes)) {
            throw new IOException("历史 API1 插件包仅供识别，不能安装；请选择 format v3 包");
        }
        PluginPackageStore.InstallSession session = store.install(
                bytes, source, channel, release != null, true,
                (previous, target) -> {
                    validateManifest(target, expectedId);
                    if (release != null) validateRepositoryPackage(target, release);
                    validateDataCompatibility(previous, target, release);
                });
        try {
            PluginPackageStore.InstalledPlugin installed = store.find(session.pluginId);
            if (installed == null) throw new IOException("安装后的插件不可读");
            return session;
        } catch (IOException | RuntimeException error) {
            store.rollbackInstall(session);
            throw error;
        }
    }

    private static void validateManifest(RuntimePluginManifest manifest, String expectedId) throws IOException {
        if (expectedId != null && !expectedId.equals(manifest.plugin.id)) throw new IOException("插件包 ID 与归档项目不一致");
        if ("plugin_manager".equals(manifest.plugin.id)) throw new IOException("插件 ID 与内置插件冲突");
        for (HostTool tool : ToolRegistry.createBuiltInPlugins()) {
            try {
                if (tool.id().equals(manifest.plugin.id)) throw new IOException("插件 ID 与内置插件冲突");
            } finally { tool.onDestroy(); }
        }
        if (manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE) throw new IOException("当前应用版本不兼容此插件");
        if (manifest.plugin.minAndroidApi > Build.VERSION.SDK_INT) throw new IOException("当前 Android 版本不兼容此插件");
    }

    private static void validateRepositoryPackage(RuntimePluginManifest manifest,
                                                   UpdateCatalog.PluginRelease release) throws IOException {
        if (!release.id.equals(manifest.plugin.id)
                || release.versionCode != manifest.plugin.versionCode
                || !release.versionName.equals(manifest.plugin.version)
                || release.minHostVersionCode != manifest.plugin.minHostVersionCode
                || release.minAndroidApi != manifest.plugin.minAndroidApi) {
            throw new IOException("下载包与仓库索引元数据不一致");
        }
        if (!release.dependencies.equals(manifest.legacyPluginDependencyLabels())) {
            throw new IOException("下载包依赖与仓库索引不一致");
        }
    }

    private static void validateDataCompatibility(RuntimePluginManifest previous,
                                                   RuntimePluginManifest target,
                                                   UpdateCatalog.PluginRelease release) throws IOException {
        if (previous == null) return;
        int currentFormat = 0;
        for (RuntimePluginManifest.Dataset dataset : previous.datasets) {
            currentFormat = Math.max(currentFormat, dataset.formatVersion);
            if (target.plugin.versionCode > previous.plugin.versionCode) continue;
            for (RuntimePluginManifest.Dataset candidate : target.datasets) {
                if (dataset.id.equals(candidate.id)
                        && (dataset.formatVersion != candidate.formatVersion
                        || dataset.sensitive != candidate.sensitive)) {
                    throw new IOException("目标版本不能读取当前 Dataset：" + dataset.id
                            + "（格式 v" + dataset.formatVersion + "）");
                }
            }
        }
        if (release != null && !release.canReadDataFormat(currentFormat)) {
            throw new IOException("目标版本不能读取当前数据格式 v" + currentFormat);
        }
    }
}
