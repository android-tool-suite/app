package com.androidtoolsuite.app.migration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class HostMigrationArchive {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;
    public static final int MAX_PLUGIN_BYTES = 128 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final String MANIFEST_PATH = "migration.json";

    private HostMigrationArchive() {
    }

    public static void write(OutputStream output, Snapshot snapshot) throws IOException, JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("type", "android-tool-suite-migration");
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        manifest.put("exportedAt", timestamp.format(new Date()));
        manifest.put("sourcePackage", snapshot.sourcePackage);
        manifest.put("sourceVersionName", snapshot.sourceVersionName);
        manifest.put("sourceVersionCode", snapshot.sourceVersionCode);
        manifest.put("host", new JSONObject(snapshot.host.toString()));
        manifest.put("builtInEnabledIds", strings(snapshot.builtInEnabledIds));

        JSONArray plugins = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (PluginEntry plugin : snapshot.plugins) {
            validateId(plugin.id);
            if (!ids.add(plugin.id)) {
                throw new IOException("迁移包包含重复插件：" + plugin.id);
            }
            if (plugin.packageBytes.length == 0 || plugin.packageBytes.length > MAX_PLUGIN_BYTES) {
                throw new IOException("插件包大小无效：" + plugin.id);
            }
            JSONObject item = new JSONObject();
            item.put("id", plugin.id);
            item.put("enabled", plugin.enabled);
            item.put("path", pluginPath(plugin.id));
            item.put("size", plugin.packageBytes.length);
            item.put("sha256", sha256(plugin.packageBytes));
            plugins.put(item);
        }
        manifest.put("plugins", plugins);

        byte[] manifestBytes = (manifest.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, MANIFEST_PATH, manifestBytes);
            for (PluginEntry plugin : snapshot.plugins) {
                writeEntry(zip, pluginPath(plugin.id), plugin.packageBytes);
            }
        }
    }

    public static Snapshot read(byte[] archiveBytes) throws IOException, JSONException {
        if (archiveBytes.length == 0 || archiveBytes.length > MAX_ARCHIVE_BYTES) {
            throw new IOException("迁移包大小超出限制");
        }
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archiveBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (!MANIFEST_PATH.equals(name) && !name.matches("plugins/[A-Za-z0-9._-]+\\.atsplugin")) {
                    throw new IOException("迁移包包含非法路径：" + name);
                }
                if (entries.containsKey(name)) {
                    throw new IOException("迁移包包含重复文件：" + name);
                }
                int limit = MANIFEST_PATH.equals(name) ? MAX_MANIFEST_BYTES : MAX_PLUGIN_BYTES;
                byte[] bytes = readEntry(zip, limit);
                total += bytes.length;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new IOException("迁移包解压后大小超出限制");
                }
                entries.put(name, bytes);
                zip.closeEntry();
            }
        }

        byte[] manifestBytes = entries.remove(MANIFEST_PATH);
        if (manifestBytes == null) {
            throw new IOException("迁移包缺少 migration.json");
        }
        JSONObject manifest = new JSONObject(new String(manifestBytes, StandardCharsets.UTF_8));
        if (manifest.optInt("schemaVersion", 0) != SCHEMA_VERSION
                || !"android-tool-suite-migration".equals(manifest.optString("type"))) {
            throw new IOException("不支持的迁移包格式");
        }
        String sourcePackage = required(manifest, "sourcePackage");
        String sourceVersionName = required(manifest, "sourceVersionName");
        int sourceVersionCode = manifest.optInt("sourceVersionCode", 0);
        if (sourceVersionCode <= 0) {
            throw new IOException("迁移包来源版本无效");
        }
        JSONObject host = manifest.optJSONObject("host");
        if (host == null) {
            throw new IOException("迁移包缺少宿主设置");
        }
        Set<String> builtIns = stringSet(manifest.optJSONArray("builtInEnabledIds"));

        List<PluginEntry> plugins = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        JSONArray pluginArray = manifest.optJSONArray("plugins");
        if (pluginArray == null) {
            throw new IOException("迁移包缺少插件列表");
        }
        for (int index = 0; index < pluginArray.length(); index++) {
            JSONObject item = pluginArray.optJSONObject(index);
            if (item == null) {
                throw new IOException("迁移包插件记录无效");
            }
            String id = required(item, "id");
            validateId(id);
            if (!ids.add(id)) {
                throw new IOException("迁移包包含重复插件：" + id);
            }
            String path = required(item, "path");
            if (!pluginPath(id).equals(path)) {
                throw new IOException("插件路径与 ID 不匹配：" + id);
            }
            byte[] bytes = entries.remove(path);
            if (bytes == null) {
                throw new IOException("迁移包缺少插件文件：" + id);
            }
            if (item.optLong("size", -1L) != bytes.length
                    || !required(item, "sha256").equalsIgnoreCase(sha256(bytes))) {
                throw new IOException("插件文件校验失败：" + id);
            }
            plugins.add(new PluginEntry(id, item.optBoolean("enabled", false), bytes));
        }
        if (!entries.isEmpty()) {
            throw new IOException("迁移包包含未声明的插件文件");
        }
        return new Snapshot(
                sourcePackage,
                sourceVersionName,
                sourceVersionCode,
                new JSONObject(host.toString()),
                builtIns,
                plugins
        );
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] readEntry(ZipInputStream zip, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            if (output.size() + read > limit) {
                throw new IOException("迁移包文件大小超出限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String pluginPath(String id) {
        return "plugins/" + id + ".atsplugin";
    }

    private static void validateId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("插件 ID 不安全：" + id);
        }
    }

    private static String required(JSONObject json, String name) throws IOException {
        String value = json.optString(name, "").trim();
        if (value.isEmpty()) {
            throw new IOException("迁移包缺少字段：" + name);
        }
        return value;
    }

    private static JSONArray strings(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static Set<String> stringSet(JSONArray array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "").trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前系统不支持 SHA-256", error);
        }
    }

    public static final class Snapshot {
        public final String sourcePackage;
        public final String sourceVersionName;
        public final int sourceVersionCode;
        public final JSONObject host;
        public final Set<String> builtInEnabledIds;
        public final List<PluginEntry> plugins;

        public Snapshot(
                String sourcePackage,
                String sourceVersionName,
                int sourceVersionCode,
                JSONObject host,
                Set<String> builtInEnabledIds,
                List<PluginEntry> plugins
        ) {
            this.sourcePackage = sourcePackage;
            this.sourceVersionName = sourceVersionName;
            this.sourceVersionCode = sourceVersionCode;
            this.host = host;
            this.builtInEnabledIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(builtInEnabledIds)
            );
            this.plugins = Collections.unmodifiableList(new ArrayList<>(plugins));
        }
    }

    public static final class PluginEntry {
        public final String id;
        public final boolean enabled;
        public final byte[] packageBytes;

        public PluginEntry(String id, boolean enabled, byte[] packageBytes) {
            this.id = id;
            this.enabled = enabled;
            this.packageBytes = packageBytes.clone();
        }
    }
}
