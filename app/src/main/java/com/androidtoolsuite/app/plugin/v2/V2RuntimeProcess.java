package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;

import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;

import java.util.List;

/** Process-lifetime Runtime v2 graph shared by Activities and WorkManager workers. */
public final class V2RuntimeProcess {
    private static volatile V2RuntimeProcess instance;

    private final V2PackageStore packages;
    private final V2StorageService storage;
    private final V2SecretStore secrets;
    private final V2DatasetService datasets;
    private final V2ShizukuService shizuku;
    private final V2TaskRunStore taskRuns;
    private final V2BackgroundTaskRegistry backgroundTasks;
    private final V2PluginPermissionManager permissions;
    private final V2CapabilityRouter capabilities;
    private final V2SchedulerService scheduler;
    private final V2NativeProviderManager nativeProviders;
    private final V2WorkerCapabilityProviderManager workerProviders;
    @SuppressWarnings("FieldCanBeLocal")
    private final AutoCloseable permissionCleanup;
    @SuppressWarnings("FieldCanBeLocal")
    private final List<AutoCloseable> processRegistrations;

    public static V2RuntimeProcess get(Context context) {
        V2RuntimeProcess current = instance;
        if (current != null) return current;
        synchronized (V2RuntimeProcess.class) {
            current = instance;
            if (current == null) {
                current = new V2RuntimeProcess(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    private V2RuntimeProcess(Context context) {
        packages = new V2PackageStore(context);
        storage = new V2StorageService(context);
        secrets = new V2SecretStore();
        datasets = new V2DatasetService(context, packages, storage, secrets);
        shizuku = new V2ShizukuService(context);
        taskRuns = new V2TaskRunStore(context);
        backgroundTasks = new V2BackgroundTaskRegistry();
        permissions = new V2PluginPermissionManager(context);
        permissions.reconcile(packages.load());
        capabilities = new V2CapabilityRouter(backgroundTasks, permissions);
        scheduler = new V2SchedulerService(context, packages, taskRuns, capabilities);
        permissionCleanup = permissions.addListener((pluginId, capabilityId, granted) -> {
            if (granted) return;
            storage.closePluginSessions(pluginId);
            datasets.closePluginSessions(pluginId);
            if ("scheduler".equals(capabilityId)) scheduler.cancelPlugin(pluginId);
        });
        try {
            processRegistrations = V2HostCapabilityProviders.registerProcessCapabilities(
                    context, capabilities, storage, datasets, scheduler
            );
        } catch (CapabilityFailure error) {
            throw new IllegalStateException("Runtime v2 process capability registration failed", error);
        }
        nativeProviders = new V2NativeProviderManager(
                context,
                capabilities,
                backgroundTasks,
                new V2TrustedPlatformBridge(shizuku)
        );
        nativeProviders.loadEnabledAtColdStart(packages.load());
        workerProviders = new V2WorkerCapabilityProviderManager(context, capabilities);
        workerProviders.sync(packages.load());
    }

    public V2PackageStore packages() {
        return packages;
    }

    public V2StorageService storage() {
        return storage;
    }

    public V2DatasetService datasets() {
        return datasets;
    }

    public V2ShizukuService shizuku() {
        return shizuku;
    }

    public V2TaskRunStore taskRuns() {
        return taskRuns;
    }

    public V2BackgroundTaskRegistry backgroundTasks() {
        return backgroundTasks;
    }

    public V2CapabilityRouter capabilities() {
        return capabilities;
    }

    public V2PluginPermissionManager permissions() {
        return permissions;
    }

    public V2SchedulerService scheduler() {
        return scheduler;
    }

    public V2NativeProviderManager nativeProviders() {
        return nativeProviders;
    }

    public V2WorkerCapabilityProviderManager workerProviders() {
        return workerProviders;
    }
}
