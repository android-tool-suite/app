package com.androidtoolsuite.app.debug;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import com.androidtoolsuite.app.BuildConfig;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.androidtoolsuite.app.host.MainActivity;
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget;
import com.androidtoolsuite.app.plugin.api.PluginDependency;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import com.androidtoolsuite.app.plugin.runtime.ToolRegistry;
import com.androidtoolsuite.app.plugin.store.BuiltInPluginStateStore;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.app.plugin.runtime.CapabilityRouter;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageArchive;
import com.androidtoolsuite.app.plugin.runtime.PluginPermissionManager;
import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import com.androidtoolsuite.runtime.contract.GeneratedContract;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/** Debug-build-only, adb-shell-controlled command endpoint. */
public final class DebugCommandReceiver extends BroadcastReceiver {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".DEBUG_COMMAND";
    private static final String EXTRA_COMMAND = "command";
    private static final long MAX_PLUGIN_PACKAGE_BYTES = 64L * 1024L * 1024L;

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            JSONObject response;
            String command = clean(intent == null ? null : intent.getStringExtra(EXTRA_COMMAND));
            try {
                response = success(command, execute(appContext, intent, command));
                pendingResult.setResultCode(Activity.RESULT_OK);
            } catch (Throwable error) {
                response = failure(command, error);
                pendingResult.setResultCode(Activity.RESULT_CANCELED);
            }
            pendingResult.setResultData(response.toString());
            pendingResult.finish();
        }, "adb-debug-command").start();
    }

    private JSONObject execute(Context context, Intent intent, String command) throws Exception {
        if (intent == null) {
            throw new IllegalArgumentException("缺少 Intent");
        }
        switch (command) {
            case "verify-datasets":
                return DebugDataCommands.verify(context, requiredString(intent, "plugin"));
            case "list-datasets":
                return DebugDataCommands.datasets(context, clean(intent.getStringExtra("plugin")));
            case "inspect-backup":
            case "restore-datasets": {
                File source = resolveInboxFile(context, requiredString(intent, "path"));
                Set<String> keys = new LinkedHashSet<>();
                String selection = clean(intent.getStringExtra("keys"));
                if (!selection.isEmpty()) Collections.addAll(keys, selection.split(","));
                char[] password = new char[0];
                String passwordPath = clean(intent.getStringExtra("password_path"));
                try {
                    if (!passwordPath.isEmpty()) {
                        File passwordFile = resolveInboxFile(context, passwordPath);
                        if (passwordFile.length() > 4096) throw new IOException("密码文件过大");
                        try (InputStream input = new FileInputStream(passwordFile)) {
                            password = new String(readAll(input, 4096), StandardCharsets.UTF_8).toCharArray();
                        } finally {
                            if (!passwordFile.delete()) throw new IOException("无法删除临时密码文件");
                        }
                    }
                    JSONObject result = DebugDataCommands.backup(context, source,
                            "restore-datasets".equals(command), keys, password);
                    if ("restore-datasets".equals(command)) notifyStateChanged(context);
                    return result;
                } finally {
                    java.util.Arrays.fill(password, '\0');
                }
            }
            case "help":
                return help(context);
            case "status":
                return status(context);
            case "list-plugins":
                return listPlugins(context);
            case "import-plugin":
                return importPlugin(
                        context,
                        requiredString(intent, "path"),
                        intent.getBooleanExtra("replace_same_version", false)
                );
            case "export-plugin":
                return exportPlugin(
                        context,
                        requiredString(intent, "plugin"),
                        requiredString(intent, "path")
                );
            case "delete-plugin":
                return deletePlugin(context, requiredString(intent, "plugin"));
            case "set-plugin-enabled":
                return setPluginEnabled(
                        context,
                        requiredString(intent, "plugin"),
                        requiredBoolean(intent, "enabled")
                );
            case "set-widget-visible":
                return setWidgetVisible(
                        context,
                        requiredString(intent, "widget"),
                        requiredBoolean(intent, "visible")
                );
            case "reset-state":
                return resetState(context);
            case "set-dev-server":
                return setDevServer(
                        context,
                        requiredString(intent, "plugin"),
                        requiredString(intent, "url")
                );
            case "list-permissions":
                return listPermissions(context, requiredString(intent, "plugin"));
            case "set-permission":
                return setPermission(
                        context,
                        requiredString(intent, "plugin"),
                        requiredString(intent, "capability"),
                        requiredBoolean(intent, "enabled")
                );
            case "run-task":
                return runTask(
                        context,
                        requiredString(intent, "plugin"),
                        requiredString(intent, "task")
                );
            case "last-task-run":
                return lastTaskRun(
                        context,
                        requiredString(intent, "plugin"),
                        requiredString(intent, "task")
                );
            case "clear-dev-server":
                return clearDevServer(context, requiredString(intent, "plugin"));
            default:
                throw new IllegalArgumentException("未知命令：" + command + "；使用 help 查看命令列表");
        }
    }

    private JSONObject help(Context context) throws JSONException, IOException {
        JSONObject result = new JSONObject();
        result.put("action", ACTION);
        result.put("component", context.getPackageName() + "/.debug.DebugCommandReceiver");
        result.put("inbox", debugInbox(context).getAbsolutePath());
        result.put("outbox", debugOutbox(context).getAbsolutePath());
        JSONArray commands = new JSONArray();
        commands.put(command("help"));
        commands.put(command("list-datasets", "plugin:optional"));
        commands.put(command("verify-datasets", "plugin"));
        commands.put(command("inspect-backup", "path"));
        commands.put(command("restore-datasets", "path", "keys:comma-separated", "password_path:optional"));
        commands.put(command("status"));
        commands.put(command("list-plugins"));
        commands.put(command("import-plugin", "path"));
        commands.put(command("export-plugin", "plugin", "path"));
        commands.put(command("delete-plugin", "plugin"));
        commands.put(command("set-plugin-enabled", "plugin", "enabled:boolean"));
        commands.put(command("list-permissions", "plugin"));
        commands.put(command("set-permission", "plugin", "capability", "enabled:boolean"));
        commands.put(command("run-task", "plugin", "task"));
        commands.put(command("last-task-run", "plugin", "task"));
        commands.put(command("set-widget-visible", "widget", "visible:boolean"));
        commands.put(command("reset-state"));
        commands.put(command("set-dev-server", "plugin", "url"));
        commands.put(command("clear-dev-server", "plugin"));
        result.put("commands", commands);
        return result;
    }

    private JSONObject status(Context context) throws Exception {
        JSONObject result = new JSONObject();
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        result.put("package", context.getPackageName());
        result.put("versionName", packageInfo.versionName);
        result.put("versionCode", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode);
        result.put("sdk", Build.VERSION.SDK_INT);
        result.put("debugInbox", debugInbox(context).getAbsolutePath());
        result.put("debugOutbox", debugOutbox(context).getAbsolutePath());
        result.put("shizukuReady", isShizukuReady());
        result.put("shizukuPermission", hasShizukuPermission());
        result.put("hiddenWidgets", new JSONArray(hiddenWidgets(context)));
        result.put("plugins", listPlugins(context).getJSONArray("plugins"));
        result.put("runtimeDevServers", new JSONObject(
                context.getSharedPreferences("runtime_v2_dev_servers", Context.MODE_PRIVATE).getAll()
        ));
        return result;
    }

    private JSONObject runTask(Context context, String pluginId, String taskId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin installed = runtime.packages().find(pluginId);
        if (installed == null) throw new IllegalArgumentException("插件不存在：" + pluginId);
        JSONObject payload = new JSONObject()
                .put("taskId", taskId)
                .put("input", new JSONObject());
        return runtime.capabilities().invoke(
                installed.manifest,
                pluginId,
                "adb-debug-" + UUID.randomUUID(),
                GeneratedContract.Methods.SCHEDULER_RUNNOW,
                payload,
                true,
                10_000
        ).get(15, TimeUnit.SECONDS);
    }

    private JSONObject lastTaskRun(Context context, String pluginId, String taskId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        if (runtime.packages().find(pluginId) == null) {
            throw new IllegalArgumentException("插件不存在：" + pluginId);
        }
        return runtime.taskRuns().lastRun(pluginId, taskId);
    }

    private JSONObject setDevServer(Context context, String pluginId, String rawUrl) throws Exception {
        if (PluginRuntime.get(context).packages().find(pluginId) == null) {
            throw new IllegalArgumentException("插件运行时 插件不存在：" + pluginId);
        }
        Uri url = Uri.parse(rawUrl);
        String host = clean(url.getHost()).toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(url.getScheme())
                || !("127.0.0.1".equals(host) || "localhost".equals(host) || "10.0.2.2".equals(host))
                || url.getPort() < 1 || url.getUserInfo() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("dev URL 必须是带端口的 Android 调试回环地址");
        }
        if (!context.getSharedPreferences("runtime_v2_dev_servers", Context.MODE_PRIVATE)
                .edit().putString(pluginId, url.toString()).commit()) {
            throw new IOException("无法保存 插件运行时 dev server");
        }
        notifyStateChanged(context);
        return new JSONObject().put("plugin", pluginId).put("url", url.toString());
    }

    private JSONObject clearDevServer(Context context, String pluginId) throws Exception {
        if (!context.getSharedPreferences("runtime_v2_dev_servers", Context.MODE_PRIVATE)
                .edit().remove(pluginId).commit()) {
            throw new IOException("无法清除 插件运行时 dev server");
        }
        notifyStateChanged(context);
        return changed("plugin", pluginId, "cleared", true);
    }

    private JSONObject listPlugins(Context context) throws Exception {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        Set<String> activeIds = activePluginVersions(context).keySet();
        JSONArray plugins = new JSONArray();

        for (HostTool plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            plugins.put(pluginJson(plugin, true, true, true));
            plugin.onDestroy();
        }
        for (HostTool plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            plugins.put(pluginJson(
                    plugin,
                    true,
                    false,
                    builtInStore.isEnabled(plugin.id()) && activeIds.contains(plugin.id())
            ));
            plugin.onDestroy();
        }
        PluginRuntime runtime = PluginRuntime.get(context);
        for (PluginPackageStore.InstalledPlugin installed : runtime.packages().load()) {
            JSONObject plugin = new JSONObject();
            plugin.put("id", installed.manifest.plugin.id);
            plugin.put("title", installed.manifest.plugin.title);
            plugin.put("version", installed.manifest.plugin.version);
            plugin.put("formatVersion", 3);
            plugin.put("builtIn", false);
            plugin.put("required", false);
            plugin.put("enabled", installed.enabled);
            plugin.put("active", activeIds.contains(installed.manifest.plugin.id));
            plugin.put("generation", installed.generationDirectory.getName());
            plugin.put("nativeProvider", !installed.manifest.providerEntries.isEmpty());
            plugin.put("datasets", installed.manifest.datasets.size());
            plugin.put("tasks", installed.manifest.tasks.size());
            List<PluginPermissionManager.Permission> permissions = runtime.permissions().permissions(installed.manifest);
            int pendingPermissions = 0;
            for (PluginPermissionManager.Permission permission : permissions) {
                if (permission.state != PluginPermissionManager.State.GRANTED) pendingPermissions++;
            }
            plugin.put("permissions", permissions.size());
            plugin.put("pendingPermissions", pendingPermissions);
            plugins.put(plugin);
        }

        JSONObject result = new JSONObject();
        result.put("plugins", plugins);
        return result;
    }

    private JSONObject importPlugin(
            Context context,
            String relativePath,
            boolean replaceSameVersion
    ) throws Exception {
        File packageFile = resolveInboxFile(context, relativePath);
        if (!packageFile.isFile()) {
            throw new IllegalArgumentException("调试收件箱中不存在文件：" + relativePath);
        }
        if (packageFile.length() > MAX_PLUGIN_PACKAGE_BYTES) {
            throw new IllegalArgumentException("插件包超过 64 MiB 限制");
        }

        byte[] packageBytes;
        try (FileInputStream input = new FileInputStream(packageFile)) {
            packageBytes = readAll(input, MAX_PLUGIN_PACKAGE_BYTES);
        }
        if (PluginPackageArchive.hasFormatV3Manifest(packageBytes)) {
            PluginRuntime runtime = PluginRuntime.get(context);
            PluginPackageStore.InstallSession session = runtime.packages().install(
                    packageBytes, "adb-debug", "debug", false, replaceSameVersion
            );
            try {
                PluginPackageStore.InstalledPlugin installed = runtime.packages().find(session.pluginId);
                if (installed == null) throw new IOException("插件运行时 install generation is unreadable");
                runtime.permissions().reconcile(installed.manifest);
                if (isReservedPluginId(session.pluginId)) {
                    throw new IllegalArgumentException("插件 ID 与现有插件冲突：" + session.pluginId);
                }
                if (session.newInstall) runtime.packages().setEnabled(session.pluginId, false);
                runtime.packages().confirmInstall(session);
                runtime.scheduler().syncPlugin(session.pluginId);
                notifyStateChanged(context);
                int pendingPermissions = 0;
                for (PluginPermissionManager.Permission permission
                        : runtime.permissions().permissions(installed.manifest)) {
                    if (permission.state != PluginPermissionManager.State.GRANTED) pendingPermissions++;
                }
                return new JSONObject()
                        .put("plugin", session.pluginId)
                        .put("title", installed.manifest.plugin.title)
                        .put("formatVersion", 3)
                        .put("updated", !session.newInstall)
                        .put("sameVersionReplacement", replaceSameVersion && !session.newInstall)
                        .put("enabled", runtime.packages().isEnabled(session.pluginId))
                        .put("pendingPermissions", pendingPermissions);
            } catch (Throwable error) {
                runtime.packages().rollbackInstall(session);
                runtime.permissions().reconcile(runtime.packages().load());
                throw error;
            }
        }

        throw new IllegalArgumentException("旧版 API1 插件已停止安装，请使用 format v3 插件包");
    }

    private JSONObject deletePlugin(Context context, String pluginId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin v2 = runtime.packages().find(pluginId);
        if (v2 == null) {
            throw new IllegalArgumentException("外部插件不存在：" + pluginId);
        }
        List<String> dependents = enabledDependents(context, pluginId);
        if (!dependents.isEmpty()) {
            throw new IllegalStateException("仍被已启用插件依赖：" + join(dependents));
        }
        runtime.scheduler().cancelPlugin(pluginId);
        runtime.nativeProviders().deactivate(pluginId);
        runtime.packages().delete(pluginId);
        runtime.permissions().removePlugin(pluginId);
        runtime.workerProviders().sync(runtime.packages().load());
        notifyStateChanged(context);
        return changed("plugin", pluginId, "deleted", true);
    }

    private JSONObject exportPlugin(Context context, String pluginId, String relativePath) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin v2 = runtime.packages().find(pluginId);
        if (v2 == null) {
            throw new IllegalArgumentException("外部插件不存在：" + pluginId);
        }
        File output = resolveOutboxFile(context, relativePath);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建导出目录");
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(runtime.packages().exportPackage(pluginId));
            stream.getFD().sync();
        }

        JSONObject result = new JSONObject();
        result.put("plugin", pluginId);
        result.put("path", relativePath);
        result.put("devicePath", output.getAbsolutePath());
        result.put("bytes", output.length());
        return result;
    }

    private JSONObject setPluginEnabled(Context context, String pluginId, boolean enabled) throws Exception {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);

        HostTool required = findPlugin(ToolRegistry.createRequiredBuiltInPlugins(), pluginId);
        if (required != null) {
            required.onDestroy();
            if (!enabled) {
                throw new IllegalArgumentException("必需内置插件不能停用：" + pluginId);
            }
            return changed("plugin", pluginId, "enabled", true);
        }

        HostTool optional = findPlugin(ToolRegistry.createOptionalBuiltInPlugins(), pluginId);
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin v2 = runtime.packages().find(pluginId);
        if (optional == null && v2 == null) {
            throw new IllegalArgumentException("插件不存在：" + pluginId);
        }

        Set<String> dependencies = optional != null
                ? optional.dependencies()
                : Collections.emptySet();
        if (enabled) {
            Map<String, String> activeVersions = activePluginVersions(context);
            List<String> missing = missingDependencies(dependencies, activeVersions);
            if (v2 != null) missing.addAll(missingRuntimeRequirements(runtime, v2, activeVersions));
            if (!missing.isEmpty()) {
                throw new IllegalStateException("依赖未满足：" + join(missing));
            }
        } else {
            List<String> dependents = enabledDependents(context, pluginId);
            if (!dependents.isEmpty()) {
                throw new IllegalStateException("仍被已启用插件依赖：" + join(dependents));
            }
        }

        if (optional != null) {
            builtInStore.setEnabled(pluginId, enabled);
            optional.onDestroy();
        } else {
            runtime.packages().setEnabled(pluginId, enabled);
            runtime.scheduler().syncPlugin(pluginId);
            if (!enabled) runtime.nativeProviders().deactivate(pluginId);
            runtime.workerProviders().sync(runtime.packages().load());
        }
        notifyStateChanged(context);
        return changed("plugin", pluginId, "enabled", enabled);
    }

    private List<String> missingRuntimeRequirements(
            PluginRuntime runtime,
            PluginPackageStore.InstalledPlugin installed,
            Map<String, String> activeVersions
    ) {
        List<String> missing = new ArrayList<>();
        RuntimePluginManifest manifest = installed.manifest;
        if (manifest.plugin.minHostVersionCode > BuildConfig.VERSION_CODE) {
            missing.add("host>=" + manifest.plugin.minHostVersionCode);
        }
        if (manifest.plugin.minAndroidApi > Build.VERSION.SDK_INT) {
            missing.add("android>=" + manifest.plugin.minAndroidApi);
        }
        for (RuntimePluginManifest.Requirement requirement : manifest.pluginRequirements) {
            if (!requirement.optional
                    && !CapabilityRouter.versionSatisfied(
                            activeVersions.get(requirement.id), requirement.version)) {
                missing.add(requirement.id + "@" + requirement.version);
            }
        }
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (requirement.optional || runtime.capabilities().canResolve(requirement.id, requirement.version)) {
                continue;
            }
            boolean selfProvided = false;
            for (RuntimePluginManifest.CapabilityContribution contribution
                    : manifest.capabilityContributions) {
                if (contribution.id.equals(requirement.id)
                        && CapabilityRouter.versionSatisfied(contribution.version, requirement.version)) {
                    selfProvided = true;
                    break;
                }
            }
            if (!selfProvided) missing.add(requirement.id + "@" + requirement.version);
        }
        return missing;
    }

    private JSONObject listPermissions(Context context, String pluginId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin installed = runtime.packages().find(pluginId);
        if (installed == null) throw new IllegalArgumentException("插件运行时 插件不存在：" + pluginId);
        runtime.permissions().reconcile(installed.manifest);
        JSONArray values = new JSONArray();
        for (PluginPermissionManager.Permission permission
                : runtime.permissions().permissions(installed.manifest)) {
            values.put(new JSONObject()
                    .put("capability", permission.capabilityId)
                    .put("title", permission.title)
                    .put("description", permission.description)
                    .put("risk", permission.risk)
                    .put("optional", permission.optional)
                    .put("managed", permission.isManaged())
                    .put("state", permission.state.name().toLowerCase(Locale.ROOT))
                    .put("scopes", new JSONObject(permission.scopesJson)));
        }
        return new JSONObject()
                .put("plugin", pluginId)
                .put("permissions", values)
                .put("audit", runtime.permissions().audit(pluginId));
    }

    private JSONObject setPermission(
            Context context,
            String pluginId,
            String capabilityId,
            boolean enabled
    ) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin installed = runtime.packages().find(pluginId);
        if (installed == null) throw new IllegalArgumentException("插件运行时 插件不存在：" + pluginId);
        runtime.permissions().setGranted(installed.manifest, capabilityId, enabled);
        runtime.scheduler().syncPlugin(pluginId);
        notifyStateChanged(context);
        return new JSONObject()
                .put("plugin", pluginId)
                .put("capability", capabilityId)
                .put("enabled", enabled);
    }

    private JSONObject setWidgetVisible(Context context, String widget, boolean visible) throws JSONException {
        SharedPreferences preferences = context.getSharedPreferences("main_ui", Context.MODE_PRIVATE);
        LinkedHashSet<String> hidden = new LinkedHashSet<>(
                preferences.getStringSet("hidden_widgets", Collections.emptySet())
        );
        if (visible) {
            hidden.remove(widget);
        } else {
            hidden.add(widget);
        }
        if (!preferences.edit().putStringSet("hidden_widgets", hidden).commit()) {
            throw new IllegalStateException("无法保存主页组件状态");
        }
        notifyStateChanged(context);
        JSONObject result = changed("widget", widget, "visible", visible);
        result.put("hiddenWidgets", new JSONArray(hidden));
        return result;
    }

    private JSONObject resetState(Context context) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        int removedV2 = 0;
        for (PluginPackageStore.InstalledPlugin installed : new ArrayList<>(runtime.packages().load())) {
            runtime.scheduler().cancelPlugin(installed.manifest.plugin.id);
            runtime.nativeProviders().deactivate(installed.manifest.plugin.id);
            runtime.packages().delete(installed.manifest.plugin.id);
            runtime.permissions().removePlugin(installed.manifest.plugin.id);
            removedV2++;
        }
        runtime.workerProviders().sync(runtime.packages().load());
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        for (HostTool plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            builtInStore.setEnabled(plugin.id(), false);
            plugin.onDestroy();
        }
        if (!context.getSharedPreferences("main_ui", Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new IllegalStateException("无法重置主页状态");
        }
        notifyStateChanged(context);

        JSONObject result = new JSONObject();
        result.put("removedExternalPlugins", removedV2);
        result.put("optionalBuiltInsEnabled", false);
        result.put("hiddenWidgets", new JSONArray());
        result.put("note", "完整清空应用数据请使用 adb shell pm clear " + context.getPackageName());
        return result;
    }

    private Map<String, String> activePluginVersions(Context context) {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        LinkedHashMap<String, String> active = new LinkedHashMap<>();
        for (HostTool plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            if (dependenciesSatisfied(plugin.dependencies(), active)) {
                active.put(plugin.id(), plugin.version());
            }
            plugin.onDestroy();
        }
        for (HostTool plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInStore.isEnabled(plugin.id()) && dependenciesSatisfied(plugin.dependencies(), active)) {
                active.put(plugin.id(), plugin.version());
            }
            plugin.onDestroy();
        }

        PluginRuntime runtime = PluginRuntime.get(context);
        for (PluginPackageStore.InstalledPlugin installed : runtime.packages().load()) {
            boolean providerReady = !"trusted-provider".equals(installed.manifest.plugin.kind)
                    || runtime.nativeProviders().isActive(
                            installed.manifest.plugin.id,
                            installed.generationDirectory.getName());
            boolean workerReady = installed.manifest.capabilityContributions.stream()
                    .noneMatch(item -> !item.workerEntry.isEmpty())
                    || runtime.workerProviders().isActive(
                            installed.manifest.plugin.id,
                            installed.generationDirectory.getName());
            if (installed.enabled && providerReady && workerReady
                    && !active.containsKey(installed.manifest.plugin.id)) {
                active.put(installed.manifest.plugin.id, installed.manifest.plugin.version);
            }
        }
        return active;
    }

    private boolean dependenciesSatisfied(Set<String> dependencies, Map<String, String> activeVersions) {
        return missingDependencies(dependencies, activeVersions).isEmpty();
    }

    private List<String> missingDependencies(Set<String> dependencies, Map<String, String> activeVersions) {
        List<String> missing = new ArrayList<>();
        for (String dependency : dependencies) {
            PluginDependency parsed = PluginDependency.parse(dependency);
            if (!parsed.isSatisfied(activeVersions)) {
                missing.add(parsed.label());
            }
        }
        return missing;
    }

    private List<String> enabledDependents(Context context, String pluginId) {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        List<String> dependents = new ArrayList<>();
        for (HostTool plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInStore.isEnabled(plugin.id()) && dependsOn(plugin.dependencies(), pluginId)) {
                dependents.add(plugin.id());
            }
            plugin.onDestroy();
        }
        for (PluginPackageStore.InstalledPlugin installed : PluginRuntime.get(context).packages().load()) {
            if (!installed.enabled) continue;
            for (com.androidtoolsuite.runtime.contract.RuntimePluginManifest.Requirement requirement
                    : installed.manifest.pluginRequirements) {
                if (!requirement.optional && requirement.id.equals(pluginId)) {
                    dependents.add(installed.manifest.plugin.id);
                }
            }
        }
        return dependents;
    }

    private boolean dependsOn(Set<String> dependencies, String pluginId) {
        for (String dependency : dependencies) {
            if (PluginDependency.parse(dependency).id.equals(pluginId)) {
                return true;
            }
        }
        return false;
    }

    private JSONObject pluginJson(HostTool plugin, boolean builtIn, boolean required, boolean active)
            throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", plugin.id());
        json.put("title", plugin.title());
        json.put("version", plugin.version());
        json.put("builtIn", builtIn);
        json.put("required", required);
        json.put("enabled", required || active);
        json.put("active", active);
        json.put("dependencies", new JSONArray(plugin.dependencies()));
        JSONArray widgets = new JSONArray();
        for (HostHomeWidget widget : plugin.createHomeWidgets(null, null)) {
            widgets.put(plugin.id() + ":" + widget.id());
        }
        json.put("widgets", widgets);
        return json;
    }

    private byte[] readAll(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("数据超过 64 MiB 限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private File debugInbox(Context context) throws IOException {
        File inbox = new File(context.getFilesDir(), "debug-inbox");
        if (!inbox.exists() && !inbox.mkdirs()) {
            throw new IOException("无法创建调试收件箱");
        }
        return inbox.getCanonicalFile();
    }

    private File resolveInboxFile(Context context, String relativePath) throws IOException {
        File inbox = debugInbox(context);
        File candidate = new File(inbox, relativePath).getCanonicalFile();
        if (!candidate.getPath().startsWith(inbox.getPath() + File.separator)) {
            throw new IllegalArgumentException("path 必须位于调试收件箱内");
        }
        return candidate;
    }

    private File debugOutbox(Context context) throws IOException {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) {
            throw new IOException("应用专属外部目录不可用");
        }
        File outbox = new File(externalFiles, "debug-outbox");
        if (!outbox.exists() && !outbox.mkdirs()) {
            throw new IOException("无法创建调试发件箱");
        }
        return outbox.getCanonicalFile();
    }

    private File resolveOutboxFile(Context context, String relativePath) throws IOException {
        File outbox = debugOutbox(context);
        File candidate = new File(outbox, relativePath).getCanonicalFile();
        if (!candidate.getPath().startsWith(outbox.getPath() + File.separator)) {
            throw new IllegalArgumentException("path 必须位于调试发件箱内");
        }
        return candidate;
    }

    private Set<String> hiddenWidgets(Context context) {
        return new LinkedHashSet<>(context.getSharedPreferences("main_ui", Context.MODE_PRIVATE)
                .getStringSet("hidden_widgets", Collections.emptySet()));
    }

    private HostTool findPlugin(List<HostTool> plugins, String id) {
        HostTool match = null;
        for (HostTool plugin : plugins) {
            if (plugin.id().equals(id)) {
                match = plugin;
            } else {
                plugin.onDestroy();
            }
        }
        return match;
    }

    private boolean isReservedPluginId(String id) {
        if ("plugin_manager".equals(id)) {
            return true;
        }
        for (HostTool plugin : ToolRegistry.createBuiltInPlugins()) {
            boolean matches = plugin.id().equals(id);
            plugin.onDestroy();
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private boolean isShizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void notifyStateChanged(Context context) {
        MainActivity.notifyDebugStateChanged();
    }

    private String requiredString(Intent intent, String key) {
        String value = clean(intent.getStringExtra(key));
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少参数：" + key);
        }
        return value;
    }

    private boolean requiredBoolean(Intent intent, String key) {
        if (!intent.hasExtra(key)) {
            throw new IllegalArgumentException("缺少布尔参数：" + key);
        }
        return intent.getBooleanExtra(key, false);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private JSONObject command(String name, String... arguments) throws JSONException {
        JSONObject command = new JSONObject();
        command.put("name", name);
        command.put("arguments", new JSONArray(arguments));
        return command;
    }

    private JSONObject changed(String firstKey, Object firstValue, String secondKey, Object secondValue)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put(firstKey, firstValue);
        result.put(secondKey, secondValue);
        return result;
    }

    private JSONObject success(String command, JSONObject data) {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", true);
            response.put("command", command);
            response.put("data", data);
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return response;
    }

    private JSONObject failure(String command, Throwable error) {
        JSONObject response = new JSONObject();
        try {
            response.put("ok", false);
            response.put("command", command);
            response.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            response.put("type", error.getClass().getSimpleName());
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
        return response;
    }

    private String join(List<String> values) {
        return android.text.TextUtils.join(", ", values);
    }

}
