package com.androidtoolsuite.app.migration;

import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.app.plugin.runtime.RuntimePackageInstaller;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Holds package install sessions until the selected archive data has also restored successfully. */
public final class RuntimePackageRestore implements AutoCloseable {
    private final PluginPackageStore store;
    private final List<PluginPackageStore.InstallSession> sessions = new ArrayList<>();
    public RuntimePackageRestore(PluginPackageStore store) { this.store = store; }

    public void install(List<MigrationBridgeManager.ImportSelection> selected, Map<String, File> staged) throws IOException {
        try {
            for (MigrationBridgeManager.ImportSelection selection : selected) {
                if (!selection.option.isPluginPackage()) continue;
                File file = staged.get(selection.option.key());
                if (file == null || !file.isFile() || file.length() > ContractLimits.MAX_PACKAGE_BYTES) throw new IOException("插件包缺失或过大");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + (long) count > ContractLimits.MAX_PACKAGE_BYTES) throw new IOException("插件包过大");
                        bytes.write(buffer, 0, count);
                    }
                }
                sessions.add(RuntimePackageInstaller.begin(store, bytes.toByteArray(), selection.option.packagedPluginId()));
            }
        } catch (ContractException error) { throw new IOException("插件安装校验失败：" + error.getMessage(), error); }
    }
    public Set<String> newPluginIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (PluginPackageStore.InstallSession session : sessions) if (session.newInstall) ids.add(session.pluginId);
        return ids;
    }
    public void commit() {
        for (PluginPackageStore.InstallSession session : sessions) store.confirmInstall(session);
        sessions.clear();
    }
    @Override public void close() {
        for (int i = sessions.size() - 1; i >= 0; i--) store.rollbackInstall(sessions.get(i));
        sessions.clear();
    }
}
