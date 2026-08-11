package com.androidtoolsuite.app.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UpdateCatalog {
    public static final String CHANNEL_RELEASE = "release";
    public static final String CHANNEL_DEBUG = "debug";

    public final int schemaVersion;
    public final String channel;
    public final String generatedAt;
    public final AppRelease app;
    public final List<PluginRelease> plugins;
    private final Map<String, List<PluginRelease>> pluginVersions;

    private UpdateCatalog(
            int schemaVersion,
            String channel,
            String generatedAt,
            AppRelease app,
            List<PluginRelease> plugins,
            Map<String, List<PluginRelease>> pluginVersions
    ) {
        this.schemaVersion = schemaVersion;
        this.channel = channel;
        this.generatedAt = generatedAt;
        this.app = app;
        this.plugins = Collections.unmodifiableList(new ArrayList<>(plugins));
        LinkedHashMap<String, List<PluginRelease>> immutableVersions = new LinkedHashMap<>();
        for (Map.Entry<String, List<PluginRelease>> entry : pluginVersions.entrySet()) {
            immutableVersions.put(
                    entry.getKey(),
                    Collections.unmodifiableList(new ArrayList<>(entry.getValue()))
            );
        }
        this.pluginVersions = Collections.unmodifiableMap(immutableVersions);
    }

    public static UpdateCatalog parse(String rawJson) throws JSONException {
        JSONObject root = new JSONObject(rawJson);
        int schemaVersion = root.optInt("schemaVersion", 0);
        if (schemaVersion != 1) {
            throw new JSONException("不支持的更新索引版本：" + schemaVersion);
        }
        String channel = clean(root.optString("channel", CHANNEL_RELEASE));
        if (!CHANNEL_RELEASE.equals(channel) && !CHANNEL_DEBUG.equals(channel)) {
            throw new JSONException("不支持的更新通道：" + channel);
        }

        boolean historyCatalog = isHistoryCatalog(root);
        AppRelease app = null;
        JSONObject appJson = root.optJSONObject("app");
        if (appJson != null) {
            JSONArray versions = appJson.optJSONArray("versions");
            JSONObject latest = versions == null ? appJson : versions.optJSONObject(0);
            if (latest != null) {
                app = AppRelease.fromJson(latest, channel);
            }
        }

        List<PluginRelease> plugins = new ArrayList<>();
        LinkedHashMap<String, List<PluginRelease>> pluginVersions = new LinkedHashMap<>();
        JSONArray pluginArray = root.optJSONArray("plugins");
        if (pluginArray != null) {
            for (int index = 0; index < pluginArray.length(); index++) {
                JSONObject pluginJson = pluginArray.optJSONObject(index);
                if (pluginJson != null) {
                    List<PluginRelease> versions = new ArrayList<>();
                    JSONArray versionArray = pluginJson.optJSONArray("versions");
                    if (historyCatalog && versionArray != null) {
                        for (int versionIndex = 0; versionIndex < versionArray.length(); versionIndex++) {
                            JSONObject versionJson = versionArray.optJSONObject(versionIndex);
                            if (versionJson != null) {
                                PluginRelease release = PluginRelease.fromJson(versionJson, channel);
                                String groupId = required(pluginJson, "id");
                                if (!groupId.equals(release.id)) {
                                    throw new JSONException("插件历史版本 ID 不一致：" + groupId);
                                }
                                versions.add(release);
                            }
                        }
                    } else {
                        versions.add(PluginRelease.fromJson(pluginJson, channel));
                    }
                    if (!versions.isEmpty()) {
                        PluginRelease latest = versions.get(0);
                        plugins.add(latest);
                        pluginVersions.put(latest.id, versions);
                    }
                }
            }
        }
        return new UpdateCatalog(
                schemaVersion,
                channel,
                clean(root.optString("generatedAt")),
                app,
                plugins,
                pluginVersions
        );
    }

    public static UpdateCatalog combine(UpdateCatalog appCatalog, UpdateCatalog pluginCatalog) {
        return combine(appCatalog, pluginCatalog, pluginCatalog);
    }

    public static UpdateCatalog combine(
            UpdateCatalog appCatalog,
            UpdateCatalog pluginLatestCatalog,
            UpdateCatalog pluginHistoryCatalog
    ) {
        List<PluginRelease> latestPlugins = new ArrayList<>(pluginLatestCatalog.plugins);
        LinkedHashMap<String, PluginRelease> latestById = new LinkedHashMap<>();
        for (PluginRelease latest : latestPlugins) {
            latestById.put(latest.id, latest);
        }
        for (PluginRelease historical : pluginHistoryCatalog.plugins) {
            if (!latestById.containsKey(historical.id)) {
                latestPlugins.add(historical);
                latestById.put(historical.id, historical);
            }
        }

        LinkedHashMap<String, List<PluginRelease>> versionsByPlugin = new LinkedHashMap<>();
        for (PluginRelease latest : latestPlugins) {
            List<PluginRelease> ordered = new ArrayList<>();
            ordered.add(latest);
            for (PluginRelease historical : pluginHistoryCatalog.versionsForPlugin(latest.id)) {
                if (!historical.sha256.equalsIgnoreCase(latest.sha256)) {
                    ordered.add(historical);
                }
            }
            versionsByPlugin.put(latest.id, ordered);
        }
        return new UpdateCatalog(
                pluginLatestCatalog.schemaVersion,
                pluginLatestCatalog.channel,
                pluginHistoryCatalog.generatedAt,
                appCatalog == null ? null : appCatalog.app,
                latestPlugins,
                versionsByPlugin
        );
    }

    public PluginRelease findPlugin(String pluginId) {
        for (PluginRelease plugin : plugins) {
            if (plugin.id.equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    public List<PluginRelease> versionsForPlugin(String pluginId) {
        List<PluginRelease> versions = pluginVersions.get(pluginId);
        return versions == null ? Collections.emptyList() : versions;
    }

    public abstract static class ReleaseAsset {
        public final String versionName;
        public final int versionCode;
        public final String channel;
        public final String commitSha;
        public final String releaseUrl;
        public final String downloadUrl;
        public final long size;
        public final String sha256;
        public final String publishedAt;

        ReleaseAsset(JSONObject json, String channel) throws JSONException {
            versionName = required(json, "versionName");
            versionCode = positive(json, "versionCode");
            this.channel = channel;
            commitSha = clean(json.optString("commitSha"));
            if (CHANNEL_DEBUG.equals(channel) && !commitSha.matches("[0-9a-fA-F]{40}")) {
                throw new JSONException("调试更新缺少有效的 commit SHA");
            }
            releaseUrl = required(json, "releaseUrl");
            downloadUrl = required(json, "downloadUrl");
            size = json.optLong("size", -1L);
            if (size <= 0L) {
                throw new JSONException("更新资产大小无效");
            }
            sha256 = required(json, "sha256").toLowerCase();
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new JSONException("更新资产 SHA-256 无效");
            }
            publishedAt = clean(json.optString("publishedAt"));
        }
    }

    public static final class AppRelease extends ReleaseAsset {
        public final String packageName;
        public final int minSdk;

        private AppRelease(JSONObject json, String channel) throws JSONException {
            super(json, channel);
            packageName = required(json, "packageName");
            minSdk = positive(json, "minSdk");
        }

        static AppRelease fromJson(JSONObject json, String channel) throws JSONException {
            return new AppRelease(json, channel);
        }
    }

    public static final class PluginRelease extends ReleaseAsset {
        public final String id;
        public final String title;
        public final String description;
        public final String author;
        public final String repositoryUrl;
        public final int minHostVersionCode;
        public final String sdkVersion;
        public final Set<String> dependencies;
        public final int dataFormatVersion;
        public final int minReadableDataFormatVersion;
        public final int maxReadableDataFormatVersion;

        private PluginRelease(JSONObject json, String channel) throws JSONException {
            super(json, channel);
            id = required(json, "id");
            title = required(json, "title");
            description = clean(json.optString("description"));
            author = clean(json.optString("author"));
            repositoryUrl = required(json, "repositoryUrl");
            minHostVersionCode = Math.max(0, json.optInt("minHostVersionCode", 0));
            sdkVersion = clean(json.optString("sdkVersion"));
            dependencies = Collections.unmodifiableSet(readStrings(json.optJSONArray("dependencies")));
            JSONObject compatibility = json.optJSONObject("dataCompatibility");
            if (compatibility == null) {
                dataFormatVersion = 0;
                minReadableDataFormatVersion = 0;
                maxReadableDataFormatVersion = 0;
            } else {
                int schemaVersion = positive(compatibility, "schemaVersion");
                if (schemaVersion != 1) {
                    throw new JSONException("不支持的数据兼容声明版本：" + schemaVersion);
                }
                dataFormatVersion = positive(compatibility, "dataFormatVersion");
                minReadableDataFormatVersion = positive(
                        compatibility,
                        "minReadableDataFormatVersion"
                );
                maxReadableDataFormatVersion = positive(
                        compatibility,
                        "maxReadableDataFormatVersion"
                );
                if (minReadableDataFormatVersion > dataFormatVersion
                        || dataFormatVersion > maxReadableDataFormatVersion) {
                    throw new JSONException("插件数据兼容范围无效：" + id);
                }
            }
        }

        static PluginRelease fromJson(JSONObject json, String channel) throws JSONException {
            return new PluginRelease(json, channel);
        }

        public boolean hasDataCompatibilityDeclaration() {
            return dataFormatVersion > 0;
        }

        public boolean canReadDataFormat(int version) {
            return hasDataCompatibilityDeclaration()
                    && version >= minReadableDataFormatVersion
                    && version <= maxReadableDataFormatVersion;
        }
    }

    private static boolean isHistoryCatalog(JSONObject root) {
        JSONObject app = root.optJSONObject("app");
        if (app != null && app.optJSONArray("versions") != null) {
            return true;
        }
        JSONArray plugins = root.optJSONArray("plugins");
        return plugins != null
                && plugins.length() > 0
                && plugins.optJSONObject(0) != null
                && plugins.optJSONObject(0).optJSONArray("versions") != null;
    }

    private static int positive(JSONObject json, String name) throws JSONException {
        int value = json.optInt(name, 0);
        if (value <= 0) {
            throw new JSONException("字段 " + name + " 必须为正整数");
        }
        return value;
    }

    private static String required(JSONObject json, String name) throws JSONException {
        String value = clean(json.optString(name));
        if (value.isEmpty()) {
            throw new JSONException("缺少字段：" + name);
        }
        return value;
    }

    private static LinkedHashSet<String> readStrings(JSONArray array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array == null) {
            return values;
        }
        for (int index = 0; index < array.length(); index++) {
            String value = clean(array.optString(index));
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
