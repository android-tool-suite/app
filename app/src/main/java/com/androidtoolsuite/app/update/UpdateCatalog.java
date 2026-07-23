package com.androidtoolsuite.app.update;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class UpdateCatalog {
    public final int schemaVersion;
    public final String generatedAt;
    public final AppRelease app;
    public final List<PluginRelease> plugins;

    private UpdateCatalog(
            int schemaVersion,
            String generatedAt,
            AppRelease app,
            List<PluginRelease> plugins
    ) {
        this.schemaVersion = schemaVersion;
        this.generatedAt = generatedAt;
        this.app = app;
        this.plugins = Collections.unmodifiableList(new ArrayList<>(plugins));
    }

    public static UpdateCatalog parse(String rawJson) throws JSONException {
        JSONObject root = new JSONObject(rawJson);
        int schemaVersion = root.optInt("schemaVersion", 0);
        if (schemaVersion != 1) {
            throw new JSONException("不支持的更新索引版本：" + schemaVersion);
        }

        AppRelease app = null;
        JSONObject appJson = root.optJSONObject("app");
        if (appJson != null) {
            app = AppRelease.fromJson(appJson);
        }

        List<PluginRelease> plugins = new ArrayList<>();
        JSONArray pluginArray = root.optJSONArray("plugins");
        if (pluginArray != null) {
            for (int index = 0; index < pluginArray.length(); index++) {
                JSONObject pluginJson = pluginArray.optJSONObject(index);
                if (pluginJson != null) {
                    plugins.add(PluginRelease.fromJson(pluginJson));
                }
            }
        }
        return new UpdateCatalog(
                schemaVersion,
                clean(root.optString("generatedAt")),
                app,
                plugins
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

    public abstract static class ReleaseAsset {
        public final String versionName;
        public final int versionCode;
        public final String releaseUrl;
        public final String downloadUrl;
        public final long size;
        public final String sha256;
        public final String publishedAt;

        ReleaseAsset(JSONObject json) throws JSONException {
            versionName = required(json, "versionName");
            versionCode = positive(json, "versionCode");
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

        private AppRelease(JSONObject json) throws JSONException {
            super(json);
            packageName = required(json, "packageName");
            minSdk = positive(json, "minSdk");
        }

        static AppRelease fromJson(JSONObject json) throws JSONException {
            return new AppRelease(json);
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

        private PluginRelease(JSONObject json) throws JSONException {
            super(json);
            id = required(json, "id");
            title = required(json, "title");
            description = clean(json.optString("description"));
            author = clean(json.optString("author"));
            repositoryUrl = required(json, "repositoryUrl");
            minHostVersionCode = Math.max(0, json.optInt("minHostVersionCode", 0));
            sdkVersion = clean(json.optString("sdkVersion"));
            dependencies = Collections.unmodifiableSet(readStrings(json.optJSONArray("dependencies")));
        }

        static PluginRelease fromJson(JSONObject json) throws JSONException {
            return new PluginRelease(json);
        }
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
