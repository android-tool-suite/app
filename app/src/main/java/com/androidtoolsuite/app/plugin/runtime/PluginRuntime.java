package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;

import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;

import java.util.List;

/** Process-lifetime 插件运行时 graph shared by Activities and WorkManager workers. */
public final class PluginRuntime {
    private static volatile PluginRuntime instance;

    private final WidgetSnapshotStore widgetSnapshots;
    private final PluginPackageStore packages;
    private final StorageService storage;
    private final SecretStore secrets;
    private final DatasetService datasets;
    private final ShizukuService shizuku;
    private final TaskRunStore taskRuns;
    private final BackgroundTaskRegistry backgroundTasks;
    private final PluginPermissionManager permissions;
    private final CapabilityRouter capabilities;
    private final SchedulerService scheduler;
    private final NativeProviderManager nativeProviders;
    private final WorkerCapabilityProviderManager workerProviders;
    @SuppressWarnings("FieldCanBeLocal")
    private final AutoCloseable permissionCleanup;
    @SuppressWarnings("FieldCanBeLocal")
    private final List<AutoCloseable> processRegistrations;

    public static PluginRuntime get(Context context) {
        PluginRuntime current = instance;
        if (current != null) return current;
        synchronized (PluginRuntime.class) {
            current = instance;
            if (current == null) {
                current = new PluginRuntime(context.getApplicationContext());
                instance = current;
            }
            return current;
        }
    }

    private PluginRuntime(Context context) {
        packages = new PluginPackageStore(context);
        storage = new StorageService(context);
        secrets = new SecretStore();
        datasets = new DatasetService(context, packages, storage, secrets);
        shizuku = new ShizukuService(context);
        taskRuns = new TaskRunStore(context);
        backgroundTasks = new BackgroundTaskRegistry();
        permissions = new PluginPermissionManager(context);
        permissions.reconcile(packages.load());
        capabilities = new CapabilityRouter(backgroundTasks, permissions);
        scheduler = new SchedulerService(context, packages, taskRuns, capabilities);
        permissionCleanup = permissions.addListener((pluginId, capabilityId, granted) -> {
            if (granted) return;
            storage.closePluginSessions(pluginId);
            datasets.closePluginSessions(pluginId);
            if ("scheduler".equals(capabilityId)) scheduler.cancelPlugin(pluginId);
        });
        try {
            processRegistrations = HostCapabilityProviders.registerProcessCapabilities(
                    context, capabilities, storage, datasets, scheduler
            );
        } catch (CapabilityFailure error) {
            throw new IllegalStateException("插件运行时 process capability registration failed", error);
        }
        nativeProviders = new NativeProviderManager(
                context,
                capabilities,
                backgroundTasks,
                new HostPlatformBridge(shizuku)
        );
        nativeProviders.loadEnabledAtColdStart(packages.load());
        workerProviders = new WorkerCapabilityProviderManager(context, capabilities);
        workerProviders.sync(packages.load());
        widgetSnapshots = new WidgetSnapshotStore(context, this);
    }

    public WidgetSnapshotStore widgetSnapshots() { return widgetSnapshots; }

    public PluginPackageStore packages() {
        return packages;
    }

    public StorageService storage() {
        return storage;
    }

    public DatasetService datasets() {
        return datasets;
    }

    public ShizukuService shizuku() {
        return shizuku;
    }

    public TaskRunStore taskRuns() {
        return taskRuns;
    }

    public BackgroundTaskRegistry backgroundTasks() {
        return backgroundTasks;
    }

    public CapabilityRouter capabilities() {
        return capabilities;
    }

    public PluginPermissionManager permissions() {
        return permissions;
    }

    public SchedulerService scheduler() {
        return scheduler;
    }

    public NativeProviderManager nativeProviders() {
        return nativeProviders;
    }

    public WorkerCapabilityProviderManager workerProviders() {
        return workerProviders;
    }
}
