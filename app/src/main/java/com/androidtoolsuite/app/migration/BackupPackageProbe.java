package com.androidtoolsuite.app.migration;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Identifies supported .atsbackup generations without buffering their payloads. */
public final class BackupPackageProbe {
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

    private BackupPackageProbe() {
    }

    public enum Format {
        DATA_PACKAGE_V3,
        BRIDGE_V2,
        HOST_MIGRATION_V1
    }

    public static Format detect(InputStream source) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            int visited = 0;
            while ((entry = zip.getNextEntry()) != null && visited++ < 16) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if ("manifest.json".equals(entry.getName())) {
                    JSONObject manifest = new JSONObject(readSmall(zip));
                    if (!"atsbackup".equals(manifest.optString("format"))) {
                        throw new IOException("不支持的数据包类型");
                    }
                    int version = manifest.optInt("formatVersion", 0);
                    if (version == DataPackageArchive.FORMAT_VERSION) {
                        return Format.DATA_PACKAGE_V3;
                    }
                    if (version == BackupArchiveV2.FORMAT_VERSION) {
                        return Format.BRIDGE_V2;
                    }
                    throw new IOException("不支持的数据包版本：" + version);
                }
                if ("migration.json".equals(entry.getName())) {
                    JSONObject manifest = new JSONObject(readSmall(zip));
                    if (manifest.optInt("schemaVersion", 0) == 1
                            && "android-tool-suite-migration".equals(manifest.optString("type"))) {
                        return Format.HOST_MIGRATION_V1;
                    }
                    throw new IOException("不支持的旧迁移包版本");
                }
                zip.closeEntry();
            }
        } catch (JSONException error) {
            throw new IOException("数据包清单 JSON 无效", error);
        }
        throw new IOException("无法识别 Android Tool Suite 数据包");
    }

    private static String readSmall(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_JSON_BYTES) throw new IOException("数据包清单过大");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
