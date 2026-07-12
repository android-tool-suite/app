package com.example.shizukuaccessibilitygrant.debug;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.example.shizukuaccessibilitygrant.host.MainActivity;
import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginDependency;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugin.runtime.ExternalToolFactory;
import com.example.shizukuaccessibilitygrant.plugin.runtime.ToolRegistry;
import com.example.shizukuaccessibilitygrant.plugin.store.BuiltInPluginStateStore;
import com.example.shizukuaccessibilitygrant.plugin.store.ExternalPluginStore;

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
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import rikka.shizuku.Shizuku;

/** Debug-build-only, adb-shell-controlled command endpoint. */
public final class DebugCommandReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.example.shizukuaccessibilitygrant.DEBUG_COMMAND";
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
            case "help":
                return help(context);
            case "status":
                return status(context);
            case "list-plugins":
                return listPlugins(context);
            case "import-plugin":
                return importPlugin(context, requiredString(intent, "path"));
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
        commands.put(command("status"));
        commands.put(command("list-plugins"));
        commands.put(command("import-plugin", "path"));
        commands.put(command("export-plugin", "plugin", "path"));
        commands.put(command("delete-plugin", "plugin"));
        commands.put(command("set-plugin-enabled", "plugin", "enabled:boolean"));
        commands.put(command("set-widget-visible", "widget", "visible:boolean"));
        commands.put(command("reset-state"));
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
        return result;
    }

    private JSONObject listPlugins(Context context) throws Exception {
        ExternalPluginStore externalStore = new ExternalPluginStore(context);
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        Set<String> activeIds = activePluginVersions(context).keySet();
        JSONArray plugins = new JSONArray();

        for (ToolPlugin plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            plugins.put(pluginJson(plugin, true, true, true));
            plugin.onDestroy();
        }
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            plugins.put(pluginJson(
                    plugin,
                    true,
                    false,
                    builtInStore.isEnabled(plugin.id()) && activeIds.contains(plugin.id())
            ));
            plugin.onDestroy();
        }
        for (ImportedPluginDescriptor descriptor : externalStore.load()) {
            JSONObject plugin = new JSONObject();
            plugin.put("id", descriptor.id);
            plugin.put("title", descriptor.title);
            plugin.put("version", descriptor.version);
            plugin.put("builtIn", false);
            plugin.put("required", false);
            plugin.put("enabled", externalStore.isEnabled(descriptor.id));
            plugin.put("active", activeIds.contains(descriptor.id));
            plugin.put("entryClass", descriptor.entryClass);
            plugin.put("hasCode", !descriptor.codePath.isEmpty());
            plugin.put("dependencies", new JSONArray(descriptor.dependencies));
            JSONArray widgets = new JSONArray();
            descriptor.widgets.forEach(widget -> widgets.put(descriptor.id + ":" + widget.id));
            plugin.put("widgets", widgets);
            plugins.put(plugin);
        }

        JSONObject result = new JSONObject();
        result.put("plugins", plugins);
        return result;
    }

    private JSONObject importPlugin(Context context, String relativePath) throws Exception {
        File packageFile = resolveInboxFile(context, relativePath);
        if (!packageFile.isFile()) {
            throw new IllegalArgumentException("调试收件箱中不存在文件：" + relativePath);
        }
        if (packageFile.length() > MAX_PLUGIN_PACKAGE_BYTES) {
            throw new IllegalArgumentException("插件包超过 64 MiB 限制");
        }

        PluginPackage pluginPackage = readPluginPackage(packageFile);
        ImportedPluginDescriptor descriptor = pluginPackage.descriptor;
        if (isReservedPluginId(descriptor.id)) {
            throw new IllegalArgumentException("插件 ID 与内置插件冲突：" + descriptor.id);
        }

        ExternalPluginStore store = new ExternalPluginStore(context);
        boolean updating = findExternal(store, descriptor.id) != null;
        store.savePluginCode(descriptor.id, pluginPackage.codeBytes);
        if (!updating) store.setEnabled(descriptor.id, false);
        store.save(descriptor);
        notifyStateChanged(context);

        JSONObject result = new JSONObject();
        result.put("plugin", descriptor.id);
        result.put("title", descriptor.title);
        result.put("updated", updating);
        result.put("enabled", store.isEnabled(descriptor.id));
        return result;
    }

    private JSONObject deletePlugin(Context context, String pluginId) throws Exception {
        ExternalPluginStore store = new ExternalPluginStore(context);
        ImportedPluginDescriptor descriptor = findExternal(store, pluginId);
        if (descriptor == null) {
            throw new IllegalArgumentException("外部插件不存在：" + pluginId);
        }
        List<String> dependents = enabledDependents(context, pluginId);
        if (!dependents.isEmpty()) {
            throw new IllegalStateException("仍被已启用插件依赖：" + join(dependents));
        }
        store.delete(pluginId);
        notifyStateChanged(context);
        return changed("plugin", pluginId, "deleted", true);
    }

    private JSONObject exportPlugin(Context context, String pluginId, String relativePath) throws Exception {
        ExternalPluginStore store = new ExternalPluginStore(context);
        ImportedPluginDescriptor descriptor = findExternal(store, pluginId);
        if (descriptor == null) {
            throw new IllegalArgumentException("外部插件不存在：" + pluginId);
        }
        File output = resolveOutboxFile(context, relativePath);
        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建导出目录");
        }
        writePluginPackage(output, descriptor);

        JSONObject result = new JSONObject();
        result.put("plugin", pluginId);
        result.put("path", relativePath);
        result.put("devicePath", output.getAbsolutePath());
        result.put("bytes", output.length());
        return result;
    }

    private JSONObject setPluginEnabled(Context context, String pluginId, boolean enabled) throws Exception {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        ExternalPluginStore externalStore = new ExternalPluginStore(context);

        ToolPlugin required = findPlugin(ToolRegistry.createRequiredBuiltInPlugins(), pluginId);
        if (required != null) {
            required.onDestroy();
            if (!enabled) {
                throw new IllegalArgumentException("必需内置插件不能停用：" + pluginId);
            }
            return changed("plugin", pluginId, "enabled", true);
        }

        ToolPlugin optional = findPlugin(ToolRegistry.createOptionalBuiltInPlugins(), pluginId);
        ImportedPluginDescriptor external = findExternal(externalStore, pluginId);
        if (optional == null && external == null) {
            throw new IllegalArgumentException("插件不存在：" + pluginId);
        }

        Set<String> dependencies = optional != null ? optional.dependencies() : external.dependencies;
        if (enabled) {
            List<String> missing = missingDependencies(dependencies, activePluginVersions(context));
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
            externalStore.setEnabled(pluginId, enabled);
        }
        notifyStateChanged(context);
        return changed("plugin", pluginId, "enabled", enabled);
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
        ExternalPluginStore externalStore = new ExternalPluginStore(context);
        List<ImportedPluginDescriptor> imported = new ArrayList<>(externalStore.load());
        for (ImportedPluginDescriptor descriptor : imported) {
            externalStore.delete(descriptor.id);
        }
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            builtInStore.setEnabled(plugin.id(), false);
            plugin.onDestroy();
        }
        if (!context.getSharedPreferences("main_ui", Context.MODE_PRIVATE).edit().clear().commit()) {
            throw new IllegalStateException("无法重置主页状态");
        }
        notifyStateChanged(context);

        JSONObject result = new JSONObject();
        result.put("removedExternalPlugins", imported.size());
        result.put("optionalBuiltInsEnabled", false);
        result.put("hiddenWidgets", new JSONArray());
        result.put("note", "完整清空应用数据请使用 adb shell pm clear " + context.getPackageName());
        return result;
    }

    private Map<String, String> activePluginVersions(Context context) {
        BuiltInPluginStateStore builtInStore = new BuiltInPluginStateStore(context);
        ExternalPluginStore externalStore = new ExternalPluginStore(context);
        LinkedHashMap<String, String> active = new LinkedHashMap<>();
        for (ToolPlugin plugin : ToolRegistry.createRequiredBuiltInPlugins()) {
            if (dependenciesSatisfied(plugin.dependencies(), active)) {
                active.put(plugin.id(), plugin.version());
            }
            plugin.onDestroy();
        }
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInStore.isEnabled(plugin.id()) && dependenciesSatisfied(plugin.dependencies(), active)) {
                active.put(plugin.id(), plugin.version());
            }
            plugin.onDestroy();
        }

        List<ImportedPluginDescriptor> pending = new ArrayList<>(externalStore.load());
        pending.removeIf(plugin -> !externalStore.isEnabled(plugin.id));
        boolean loaded;
        do {
            loaded = false;
            for (int index = pending.size() - 1; index >= 0; index--) {
                ImportedPluginDescriptor descriptor = pending.get(index);
                if (dependenciesSatisfied(descriptor.dependencies, active)) {
                    ToolPlugin plugin = ExternalToolFactory.create(context, descriptor);
                    if (plugin != null) {
                        active.put(plugin.id(), plugin.version());
                        plugin.onDestroy();
                        loaded = true;
                    }
                    pending.remove(index);
                }
            }
        } while (loaded);
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
        ExternalPluginStore externalStore = new ExternalPluginStore(context);
        List<String> dependents = new ArrayList<>();
        for (ToolPlugin plugin : ToolRegistry.createOptionalBuiltInPlugins()) {
            if (builtInStore.isEnabled(plugin.id()) && dependsOn(plugin.dependencies(), pluginId)) {
                dependents.add(plugin.id());
            }
            plugin.onDestroy();
        }
        for (ImportedPluginDescriptor descriptor : externalStore.load()) {
            if (externalStore.isEnabled(descriptor.id) && dependsOn(descriptor.dependencies, pluginId)) {
                dependents.add(descriptor.id);
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

    private JSONObject pluginJson(ToolPlugin plugin, boolean builtIn, boolean required, boolean active)
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
        for (HomeWidget widget : plugin.createHomeWidgets(null, null)) {
            widgets.put(plugin.id() + ":" + widget.id());
        }
        json.put("widgets", widgets);
        return json;
    }

    private PluginPackage readPluginPackage(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = readAll(input, MAX_PLUGIN_PACKAGE_BYTES);
        }
        if (bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new IOException("只支持包含 manifest.json 和 plugin.apk 的完整 .atsplugin 插件包");
        }
        String manifest = null;
        byte[] code = null;
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && "manifest.json".equals(entry.getName())) {
                    manifest = new String(readAll(zip, MAX_PLUGIN_PACKAGE_BYTES), StandardCharsets.UTF_8);
                } else if (!entry.isDirectory()
                        && ("plugin.apk".equals(entry.getName()) || entry.getName().endsWith("/plugin.apk"))) {
                    code = readAll(zip, MAX_PLUGIN_PACKAGE_BYTES);
                }
                zip.closeEntry();
            }
        }
        if (manifest == null) {
            throw new IOException("插件包缺少 manifest.json");
        }
        ImportedPluginDescriptor descriptor = ImportedPluginDescriptor.fromJson(manifest);
        if (descriptor.entryClass.isEmpty()) {
            throw new IOException("插件包清单缺少 plugin.entryClass");
        }
        if (code == null || code.length == 0) {
            throw new IOException("插件包缺少 plugin.apk");
        }
        return new PluginPackage(descriptor, code);
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

    private void writePluginPackage(File output, ImportedPluginDescriptor descriptor) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(descriptor.toJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            if (!descriptor.codePath.isEmpty()) {
                File code = new File(descriptor.codePath);
                if (code.isFile()) {
                    zip.putNextEntry(new ZipEntry("plugin.apk"));
                    try (FileInputStream input = new FileInputStream(code)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            zip.write(buffer, 0, read);
                        }
                    }
                    zip.closeEntry();
                }
            }
        }
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

    private ImportedPluginDescriptor findExternal(ExternalPluginStore store, String id) {
        for (ImportedPluginDescriptor descriptor : store.load()) {
            if (descriptor.id.equals(id)) {
                return descriptor;
            }
        }
        return null;
    }

    private ToolPlugin findPlugin(List<ToolPlugin> plugins, String id) {
        ToolPlugin match = null;
        for (ToolPlugin plugin : plugins) {
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
        for (ToolPlugin plugin : ToolRegistry.createBuiltInPlugins()) {
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

    private static final class PluginPackage {
        final ImportedPluginDescriptor descriptor;
        final byte[] codeBytes;

        PluginPackage(ImportedPluginDescriptor descriptor, byte[] codeBytes) {
            this.descriptor = descriptor;
            this.codeBytes = codeBytes;
        }
    }
}
