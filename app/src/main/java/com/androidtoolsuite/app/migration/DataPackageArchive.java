package com.androidtoolsuite.app.migration;

import com.androidtoolsuite.app.plugin.migration.DatasetCategory;
import com.androidtoolsuite.app.plugin.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.plugin.migration.LegacyDatasetDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Unified .atsbackup v3 codec.
 *
 * <p>A package can contain the Host migration snapshot and any subset of plugin Datasets. Plain
 * and password-protected items live in separate nested sections, so a caller can restore the
 * plain section without exposing or decrypting the protected section. ACCOUNT is reserved as a
 * stable wire value for a future account-bound key provider; this codec intentionally refuses to
 * create or restore that section until such a provider exists.</p>
 */
public final class DataPackageArchive {
    public static final int FORMAT_VERSION = 3;
    public static final long MAX_ITEM_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int KEY_BITS = 256;
    private static final int KDF_ITERATIONS = 310_000;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] PASSWORD_AAD =
            "Android Tool Suite data package v3/password".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private DataPackageArchive() {
    }

    public enum ItemKind {
        PLUGIN_DATA,
        HOST_SETTINGS,
        HOST_PLUGIN_STATE,
        HOST_PLUGIN_PACKAGE,
        HOST_MIGRATION
    }

    public enum Protection {
        NONE,
        PASSWORD,
        ACCOUNT
    }

    @FunctionalInterface
    public interface ItemWriter {
        void write(OutputStream output) throws IOException;
    }

    @FunctionalInterface
    public interface ItemReader {
        void read(ItemRecord item, InputStream input) throws IOException;
    }

    public static final class ItemSource {
        public final String ownerId;
        public final String ownerName;
        public final ItemKind kind;
        public final LegacyDatasetDescriptor descriptor;
        public final Protection protection;
        public final ItemWriter writer;

        public ItemSource(
                String ownerId,
                String ownerName,
                ItemKind kind,
                LegacyDatasetDescriptor descriptor,
                Protection protection,
                ItemWriter writer
        ) {
            this.ownerId = requireId(ownerId, "owner id");
            this.ownerName = requireText(ownerName, "owner name");
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
            this.descriptor = java.util.Objects.requireNonNull(descriptor, "descriptor");
            this.protection = java.util.Objects.requireNonNull(protection, "protection");
            this.writer = java.util.Objects.requireNonNull(writer, "writer");
        }

        public String key() {
            return ownerId + "/" + descriptor.id;
        }
    }

    public static final class ItemRecord {
        public final String ownerId;
        public final String ownerName;
        public final ItemKind kind;
        public final LegacyDatasetDescriptor descriptor;
        public final Protection protection;

        private ItemRecord(
                String ownerId,
                String ownerName,
                ItemKind kind,
                LegacyDatasetDescriptor descriptor,
                Protection protection
        ) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.kind = kind;
            this.descriptor = descriptor;
            this.protection = protection;
        }

        public String key() {
            return ownerId + "/" + descriptor.id;
        }
    }

    public static final class WriteRequest {
        public final String sourcePackage;
        public final String sourceVersionName;
        public final int sourceVersionCode;
        public final List<ItemSource> items;
        public final char[] password;

        public WriteRequest(
                String sourcePackage,
                String sourceVersionName,
                int sourceVersionCode,
                List<ItemSource> items,
                char[] password
        ) {
            this.sourcePackage = requireText(sourcePackage, "source package");
            this.sourceVersionName = requireText(sourceVersionName, "source version");
            if (sourceVersionCode <= 0) {
                throw new IllegalArgumentException("sourceVersionCode must be positive");
            }
            this.sourceVersionCode = sourceVersionCode;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.password = password == null ? new char[0] : password.clone();
        }
    }

    public static final class ReadResult {
        public final String sourcePackage;
        public final String sourceVersionName;
        public final int sourceVersionCode;
        public final List<ItemRecord> items;

        private ReadResult(
                String sourcePackage,
                String sourceVersionName,
                int sourceVersionCode,
                List<ItemRecord> items
        ) {
            this.sourcePackage = sourcePackage;
            this.sourceVersionName = sourceVersionName;
            this.sourceVersionCode = sourceVersionCode;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }

        public boolean hasProtection(Protection protection) {
            for (ItemRecord item : items) {
                if (item.protection == protection) return true;
            }
            return false;
        }
    }

    public static void write(OutputStream target, WriteRequest request) throws IOException {
        validateSources(request.items);
        boolean hasPassword = request.items.stream()
                .anyMatch(item -> item.protection == Protection.PASSWORD);
        if (hasPassword && request.password.length == 0) {
            throw new IOException("密码保护区需要密码");
        }
        if (request.items.stream().anyMatch(item -> item.protection == Protection.ACCOUNT)) {
            throw new IOException("账号绑定加密尚未配置");
        }

        byte[] salt = hasPassword ? randomBytes(16) : null;
        byte[] nonce = hasPassword ? randomBytes(12) : null;
        List<Section> sections = sectionsForSources(request.items, salt, nonce);
        try (ZipOutputStream outer = new ZipOutputStream(target)) {
            writeJsonEntry(outer, "manifest.json", manifest(request));
            writeJsonEntry(outer, "items.json", itemsJson(request.items));
            writeJsonEntry(outer, "sections.json", sectionsJson(sections));
            for (Section section : sections) {
                List<ItemSource> items = new ArrayList<>();
                for (ItemSource item : request.items) {
                    if (item.protection == section.protection) items.add(item);
                }
                putEntry(outer, section.path);
                if (section.protection == Protection.NONE) {
                    ZipOutputStream payload = new ZipOutputStream(new NonClosingOutputStream(outer));
                    writeSection(payload, items);
                    payload.finish();
                    payload.close();
                } else if (section.protection == Protection.PASSWORD) {
                    writePasswordSection(outer, request.password, section, items);
                } else {
                    throw new IOException("账号绑定加密尚未配置");
                }
                outer.closeEntry();
            }
        } finally {
            Arrays.fill(request.password, '\0');
        }
    }

    public static ReadResult read(
            InputStream source,
            char[] suppliedPassword,
            Set<String> requestedKeys,
            ItemReader reader
    ) throws IOException {
        char[] password = suppliedPassword == null ? new char[0] : suppliedPassword.clone();
        JSONObject manifest = null;
        List<ItemRecord> items = null;
        List<Section> sections = null;
        Set<String> requested = requestedKeys == null
                ? null
                : new LinkedHashSet<>(requestedKeys);
        Set<String> delivered = new LinkedHashSet<>();
        Set<String> seenSections = new LinkedHashSet<>();
        try (ZipInputStream outer = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = outer.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    outer.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if ("manifest.json".equals(name)) {
                    ensure(manifest == null, "数据包包含重复 manifest.json");
                    manifest = json(readSmall(outer));
                    validateManifest(manifest);
                } else if ("items.json".equals(name)) {
                    ensure(items == null, "数据包包含重复 items.json");
                    items = parseItems(json(readSmall(outer)));
                } else if ("sections.json".equals(name)) {
                    ensure(sections == null, "数据包包含重复 sections.json");
                    sections = parseSections(json(readSmall(outer)));
                } else if (name.startsWith("sections/")) {
                    ensure(manifest != null && items != null && sections != null,
                            "数据包元数据必须位于数据区之前");
                    validateMetadata(items, sections);
                    Section section = findSection(sections, name);
                    ensure(seenSections.add(section.id), "数据包包含重复数据区：" + section.id);
                    Set<String> effective = requested == null ? keys(items) : requested;
                    if (sectionContainsRequested(items, section, effective)) {
                        if (section.protection == Protection.NONE) {
                            ZipInputStream payload = new ZipInputStream(new NonClosingInputStream(outer));
                            readSection(payload, recordsForSection(items, section), effective, reader, delivered);
                        } else if (section.protection == Protection.PASSWORD) {
                            ensure(password.length > 0, "所选数据位于密码保护区");
                            readPasswordSection(
                                    outer,
                                    password,
                                    section,
                                    recordsForSection(items, section),
                                    effective,
                                    reader,
                                    delivered
                            );
                        } else {
                            throw new IOException("所选数据使用尚未支持的账号绑定加密");
                        }
                    }
                } else {
                    throw new IOException("数据包包含未知文件：" + name);
                }
                outer.closeEntry();
            }
        } catch (JSONException error) {
            throw new IOException("数据包 JSON 无效", error);
        } finally {
            Arrays.fill(password, '\0');
        }

        ensure(manifest != null, "数据包缺少 manifest.json");
        ensure(items != null, "数据包缺少 items.json");
        ensure(sections != null, "数据包缺少 sections.json");
        validateMetadata(items, sections);
        ensure(manifest.optInt("itemCount", -1) == items.size(), "数据包项目数量不一致");
        ensure(seenSections.size() == sections.size(), "数据包缺少声明的数据区");
        Set<String> expected = requested == null ? keys(items) : requested;
        ensure(keys(items).containsAll(expected), "选择中包含数据包未声明的项目");
        ensure(delivered.containsAll(expected), "数据包缺少所选项目的载荷");
        return result(manifest, items);
    }

    /** Reads schema metadata for selection. Item payload integrity is verified by {@link #read}. */
    public static ReadResult inspect(InputStream source) throws IOException {
        JSONObject manifest = null;
        List<ItemRecord> items = null;
        List<Section> sections = null;
        try (ZipInputStream outer = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = outer.getNextEntry()) != null) {
                String name = entry.getName();
                if ("manifest.json".equals(name)) {
                    ensure(manifest == null, "数据包包含重复 manifest.json");
                    manifest = json(readSmall(outer));
                    validateManifest(manifest);
                } else if ("items.json".equals(name)) {
                    ensure(items == null, "数据包包含重复 items.json");
                    items = parseItems(json(readSmall(outer)));
                } else if ("sections.json".equals(name)) {
                    ensure(sections == null, "数据包包含重复 sections.json");
                    sections = parseSections(json(readSmall(outer)));
                } else if (name.startsWith("sections/")) {
                    break;
                } else if (!entry.isDirectory()) {
                    throw new IOException("数据包包含未知文件：" + name);
                }
                outer.closeEntry();
            }
        } catch (JSONException error) {
            throw new IOException("数据包 JSON 无效", error);
        }
        ensure(manifest != null, "数据包缺少 manifest.json");
        ensure(items != null, "数据包缺少 items.json");
        ensure(sections != null, "数据包缺少 sections.json");
        validateMetadata(items, sections);
        ensure(manifest.optInt("itemCount", -1) == items.size(), "数据包项目数量不一致");
        return result(manifest, items);
    }

    private static ReadResult result(JSONObject manifest, List<ItemRecord> items) {
        return new ReadResult(
                manifest.optString("sourcePackage"),
                manifest.optString("sourceVersionName"),
                manifest.optInt("sourceVersionCode"),
                items
        );
    }

    private static void writePasswordSection(
            ZipOutputStream outer,
            char[] password,
            Section section,
            List<ItemSource> items
    ) throws IOException {
        try {
            Cipher cipher = passwordCipher(
                    Cipher.ENCRYPT_MODE,
                    password,
                    section.salt,
                    section.nonce
            );
            CipherOutputStream encrypted = new CipherOutputStream(
                    new NonClosingOutputStream(outer),
                    cipher
            );
            ZipOutputStream payload = new ZipOutputStream(encrypted);
            writeSection(payload, items);
            payload.finish();
            payload.close();
        } catch (GeneralSecurityException error) {
            throw new IOException("无法初始化数据包加密", error);
        }
    }

    private static void readPasswordSection(
            ZipInputStream outer,
            char[] password,
            Section section,
            List<ItemRecord> items,
            Set<String> requested,
            ItemReader reader,
            Set<String> delivered
    ) throws IOException {
        try {
            Cipher cipher = passwordCipher(
                    Cipher.DECRYPT_MODE,
                    password,
                    section.salt,
                    section.nonce
            );
            CipherInputStream decrypted = new CipherInputStream(
                    new NonClosingInputStream(outer),
                    cipher
            );
            ZipInputStream payload = new ZipInputStream(decrypted);
            readSection(payload, items, requested, reader, delivered);
        } catch (IOException error) {
            if (hasCause(error, AEADBadTagException.class)) {
                throw new IOException("数据包密码错误或密码保护区已损坏", error);
            }
            throw error;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("数据包密码错误或加密参数无效", error);
        }
    }

    private static void writeSection(ZipOutputStream payload, List<ItemSource> items)
            throws IOException {
        JSONObject integrity = new JSONObject();
        try {
            for (ItemSource item : items) {
                putEntry(payload, itemPath(item.ownerId, item.descriptor.id));
                MessageDigest digest = sha256Digest();
                CountingDigestOutputStream output = new CountingDigestOutputStream(
                        new NonClosingOutputStream(payload),
                        digest,
                        MAX_ITEM_BYTES
                );
                item.writer.write(output);
                output.flush();
                payload.closeEntry();
                integrity.put(item.key(), new JSONObject()
                        .put("size", output.count)
                        .put("sha256", hex(digest.digest())));
            }
            writeJsonEntry(payload, "integrity.json", integrity);
        } catch (JSONException error) {
            throw new IOException("无法生成数据包完整性清单", error);
        }
    }

    private static void readSection(
            ZipInputStream payload,
            List<ItemRecord> items,
            Set<String> requested,
            ItemReader reader,
            Set<String> delivered
    ) throws IOException {
        Map<String, String> digests = new LinkedHashMap<>();
        Map<String, Long> sizes = new LinkedHashMap<>();
        JSONObject integrity = null;
        ZipEntry entry;
        while ((entry = payload.getNextEntry()) != null) {
            if (entry.isDirectory()) {
                payload.closeEntry();
                continue;
            }
            if ("integrity.json".equals(entry.getName())) {
                ensure(integrity == null, "数据区包含重复 integrity.json");
                integrity = json(readSmall(payload));
            } else {
                ensure(integrity == null, "integrity.json 后不能再包含数据项目");
                ItemRecord item = findByPath(items, entry.getName());
                ensure(!digests.containsKey(item.key()), "数据区包含重复项目：" + item.key());
                MessageDigest digest = sha256Digest();
                CountingDigestInputStream input = new CountingDigestInputStream(
                        payload,
                        digest,
                        MAX_ITEM_BYTES
                );
                if (requested.contains(item.key())) {
                    reader.read(item, input);
                    delivered.add(item.key());
                }
                drain(input);
                digests.put(item.key(), hex(digest.digest()));
                sizes.put(item.key(), input.count);
            }
            payload.closeEntry();
        }
        validateIntegrity(items, integrity, digests, sizes);
    }

    private static JSONObject manifest(WriteRequest request) throws IOException {
        try {
            SimpleDateFormat timestamp = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    Locale.ROOT
            );
            timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new JSONObject()
                    .put("format", "atsbackup")
                    .put("formatVersion", FORMAT_VERSION)
                    .put("exportedAt", timestamp.format(new Date()))
                    .put("sourcePackage", request.sourcePackage)
                    .put("sourceVersionName", request.sourceVersionName)
                    .put("sourceVersionCode", request.sourceVersionCode)
                    .put("itemCount", request.items.size());
        } catch (JSONException error) {
            throw new IOException("无法生成数据包清单", error);
        }
    }

    private static JSONObject itemsJson(List<ItemSource> sources) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (ItemSource source : sources) {
                LegacyDatasetDescriptor item = source.descriptor;
                array.put(new JSONObject()
                        .put("ownerId", source.ownerId)
                        .put("ownerName", source.ownerName)
                        .put("kind", source.kind.name())
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("category", item.category.name())
                        .put("estimatedSize", item.estimatedSize)
                        .put("dataFormatVersion", item.dataFormatVersion)
                        .put("sensitive", item.sensitive)
                        .put("restoreModes", new JSONArray(item.restoreModes.stream()
                                .map(DatasetRestoreMode::name)
                                .toArray()))
                        .put("dependencies", new JSONArray(item.dependencies))
                        .put("protection", source.protection.name()));
            }
            return new JSONObject().put("items", array);
        } catch (JSONException error) {
            throw new IOException("无法生成数据项目清单", error);
        }
    }

    private static List<ItemRecord> parseItems(JSONObject root) throws IOException, JSONException {
        JSONArray array = root.optJSONArray("items");
        ensure(array != null, "数据包缺少项目列表");
        List<ItemRecord> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String ownerId = requireId(item.getString("ownerId"), "owner id");
            String ownerName = requireText(item.getString("ownerName"), "owner name");
            String id = requireId(item.getString("id"), "item id");
            JSONArray dependencyArray = item.optJSONArray("dependencies");
            List<String> dependencies = new ArrayList<>();
            if (dependencyArray != null) {
                for (int dependencyIndex = 0;
                     dependencyIndex < dependencyArray.length();
                     dependencyIndex++) {
                    dependencies.add(dependencyArray.getString(dependencyIndex));
                }
            }
            LegacyDatasetDescriptor descriptor;
            try {
                JSONArray restoreModeArray = item.optJSONArray("restoreModes");
                List<DatasetRestoreMode> restoreModes = new ArrayList<>();
                if (restoreModeArray != null) {
                    for (int modeIndex = 0; modeIndex < restoreModeArray.length(); modeIndex++) {
                        restoreModes.add(DatasetRestoreMode.valueOf(
                                restoreModeArray.getString(modeIndex)
                        ));
                    }
                } else {
                    restoreModes.add(DatasetRestoreMode.valueOf(item.getString("restoreMode")));
                }
                descriptor = new LegacyDatasetDescriptor(
                        id,
                        item.getString("name"),
                        DatasetCategory.valueOf(item.getString("category")),
                        item.optLong("estimatedSize", 0L),
                        item.getInt("dataFormatVersion"),
                        item.optBoolean("sensitive", false),
                        restoreModes,
                        dependencies
                );
            } catch (IllegalArgumentException error) {
                throw new IOException("数据项目描述无效", error);
            }
            ItemKind kind;
            Protection protection;
            try {
                kind = ItemKind.valueOf(item.getString("kind"));
                protection = Protection.valueOf(item.getString("protection"));
            } catch (IllegalArgumentException error) {
                throw new IOException("数据项目包含不支持的类型或保护方式", error);
            }
            ItemRecord record = new ItemRecord(
                    ownerId,
                    ownerName,
                    kind,
                    descriptor,
                    protection
            );
            ensure(keys.add(record.key()), "数据包包含重复项目：" + record.key());
            result.add(record);
        }
        return result;
    }

    private static List<Section> sectionsForSources(
            List<ItemSource> items,
            byte[] salt,
            byte[] nonce
    ) {
        boolean hasPlain = items.stream().anyMatch(item -> item.protection == Protection.NONE);
        boolean hasPassword = items.stream().anyMatch(item -> item.protection == Protection.PASSWORD);
        List<Section> result = new ArrayList<>();
        if (hasPlain) {
            result.add(new Section("plain", Protection.NONE, "sections/plain.zip", null, null));
        }
        if (hasPassword) {
            result.add(new Section(
                    "password",
                    Protection.PASSWORD,
                    "sections/password.enc",
                    salt,
                    nonce
            ));
        }
        return result;
    }

    private static JSONObject sectionsJson(List<Section> sections) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (Section section : sections) {
                JSONObject item = new JSONObject()
                        .put("id", section.id)
                        .put("protection", section.protection.name())
                        .put("path", section.path);
                if (section.protection == Protection.PASSWORD) {
                    item.put("crypto", cryptoJson(section.salt, section.nonce));
                }
                array.put(item);
            }
            return new JSONObject().put("sections", array);
        } catch (JSONException error) {
            throw new IOException("无法生成数据区清单", error);
        }
    }

    private static List<Section> parseSections(JSONObject root) throws IOException, JSONException {
        JSONArray array = root.optJSONArray("sections");
        ensure(array != null, "数据包缺少数据区列表");
        List<Section> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String id = requireId(item.getString("id"), "section id");
            Protection protection;
            try {
                protection = Protection.valueOf(item.getString("protection"));
            } catch (IllegalArgumentException error) {
                throw new IOException("数据区包含不支持的保护方式", error);
            }
            String path = item.getString("path");
            ensure(path.matches("sections/[A-Za-z0-9._-]+"), "数据区路径无效");
            byte[] salt = null;
            byte[] nonce = null;
            if (protection == Protection.PASSWORD) {
                JSONObject crypto = item.getJSONObject("crypto");
                validateCrypto(crypto);
                salt = decodeBase64(crypto.getString("salt"));
                nonce = decodeBase64(crypto.getString("nonce"));
            }
            ensure(ids.add(id) && paths.add(path), "数据包包含重复数据区");
            result.add(new Section(id, protection, path, salt, nonce));
        }
        return result;
    }

    private static JSONObject cryptoJson(byte[] salt, byte[] nonce) throws JSONException {
        return new JSONObject()
                .put("formatVersion", 1)
                .put("kdf", "PBKDF2WithHmacSHA256")
                .put("iterations", KDF_ITERATIONS)
                .put("salt", encodeBase64(salt))
                .put("cipher", "AES/GCM/NoPadding")
                .put("nonce", encodeBase64(nonce))
                .put("tagBits", GCM_TAG_BITS);
    }

    private static void validateCrypto(JSONObject crypto) throws IOException {
        ensure(crypto.optInt("formatVersion", 0) == 1
                        && "PBKDF2WithHmacSHA256".equals(crypto.optString("kdf"))
                        && crypto.optInt("iterations", 0) == KDF_ITERATIONS
                        && "AES/GCM/NoPadding".equals(crypto.optString("cipher"))
                        && crypto.optInt("tagBits", 0) == GCM_TAG_BITS,
                "不支持的数据包加密参数");
    }

    private static void validateManifest(JSONObject manifest) throws IOException, JSONException {
        ensure("atsbackup".equals(manifest.optString("format")), "不支持的数据包类型");
        ensure(manifest.optInt("formatVersion", 0) == FORMAT_VERSION, "不支持的数据包版本");
        ensure(manifest.getInt("sourceVersionCode") > 0, "数据包来源版本无效");
        required(manifest, "sourcePackage");
        required(manifest, "sourceVersionName");
    }

    private static void validateSources(List<ItemSource> sources) throws IOException {
        ensure(!sources.isEmpty(), "没有选择要导出的数据项目");
        Set<String> keys = new LinkedHashSet<>();
        for (ItemSource source : sources) {
            ensure(source != null, "数据项目来源无效");
            ensure(keys.add(source.key()), "包含重复数据项目：" + source.key());
        }
        for (ItemSource source : sources) {
            for (String dependency : source.descriptor.dependencies) {
                ensure(keys.contains(source.ownerId + "/" + dependency),
                        "数据项目缺少依赖：" + source.ownerId + "/" + dependency);
            }
        }
    }

    private static void validateMetadata(List<ItemRecord> items, List<Section> sections)
            throws IOException {
        ensure(!items.isEmpty(), "数据包没有数据项目");
        ensure(!sections.isEmpty(), "数据包没有数据区");
        Set<Protection> protections = new LinkedHashSet<>();
        for (Section section : sections) {
            ensure(protections.add(section.protection), "同一保护方式只能有一个数据区");
        }
        Set<String> keys = keys(items);
        for (ItemRecord item : items) {
            ensure(protections.contains(item.protection), "数据项目缺少对应数据区：" + item.key());
            for (String dependency : item.descriptor.dependencies) {
                ensure(keys.contains(item.ownerId + "/" + dependency),
                        "数据项目缺少依赖：" + item.ownerId + "/" + dependency);
            }
        }
        for (Section section : sections) {
            boolean used = items.stream().anyMatch(item -> item.protection == section.protection);
            ensure(used, "数据包包含空数据区：" + section.id);
        }
    }

    private static void validateIntegrity(
            List<ItemRecord> items,
            JSONObject integrity,
            Map<String, String> actualDigests,
            Map<String, Long> actualSizes
    ) throws IOException {
        ensure(integrity != null, "数据区缺少 integrity.json");
        ensure(actualDigests.size() == items.size(), "数据区缺少项目载荷");
        try {
            for (ItemRecord item : items) {
                JSONObject expected = integrity.optJSONObject(item.key());
                ensure(expected != null, "数据区缺少项目校验：" + item.key());
                ensure(expected.getLong("size") == actualSizes.getOrDefault(item.key(), -1L),
                        "数据项目大小校验失败：" + item.key());
                ensure(expected.getString("sha256").equalsIgnoreCase(actualDigests.get(item.key())),
                        "数据项目摘要校验失败：" + item.key());
            }
        } catch (JSONException error) {
            throw new IOException("数据区完整性清单无效", error);
        }
    }

    private static Section findSection(List<Section> sections, String path) throws IOException {
        for (Section section : sections) {
            if (section.path.equals(path)) return section;
        }
        throw new IOException("数据包包含未声明的数据区：" + path);
    }

    private static ItemRecord findByPath(List<ItemRecord> items, String path) throws IOException {
        for (ItemRecord item : items) {
            if (itemPath(item.ownerId, item.descriptor.id).equals(path)) return item;
        }
        throw new IOException("数据区包含未声明的项目：" + path);
    }

    private static List<ItemRecord> recordsForSection(List<ItemRecord> items, Section section) {
        List<ItemRecord> result = new ArrayList<>();
        for (ItemRecord item : items) {
            if (item.protection == section.protection) result.add(item);
        }
        return result;
    }

    private static boolean sectionContainsRequested(
            List<ItemRecord> items,
            Section section,
            Set<String> requested
    ) {
        for (ItemRecord item : items) {
            if (item.protection == section.protection && requested.contains(item.key())) return true;
        }
        return false;
    }

    private static Set<String> keys(List<ItemRecord> items) {
        Set<String> result = new LinkedHashSet<>();
        for (ItemRecord item : items) result.add(item.key());
        return result;
    }

    private static Cipher passwordCipher(
            int mode,
            char[] password,
            byte[] salt,
            byte[] nonce
    ) throws GeneralSecurityException, IOException {
        ensure(salt != null && salt.length == 16 && nonce != null && nonce.length == 12,
                "数据包加密参数长度无效");
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
        byte[] rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .getEncoded();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(rawKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(PASSWORD_AAD);
            return cipher;
        } finally {
            spec.clearPassword();
            Arrays.fill(rawKey, (byte) 0);
        }
    }

    private static void writeJsonEntry(ZipOutputStream zip, String path, JSONObject value)
            throws IOException {
        putEntry(zip, path);
        zip.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putEntry(ZipOutputStream zip, String path) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
    }

    private static String itemPath(String ownerId, String itemId) {
        return "payload/" + ownerId + "/" + itemId;
    }

    private static String readSmall(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_JSON_BYTES) throw new IOException("数据包 JSON 过大");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static JSONObject json(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("数据包 JSON 无效", error);
        }
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) {
            // Complete the digest even when a consumer stops reading early.
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException error) {
            throw new IOException("SHA-256 不可用", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] randomBytes(int count) {
        byte[] result = new byte[count];
        RANDOM.nextBytes(result);
        return result;
    }

    private static String encodeBase64(byte[] bytes) {
        final char[] alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder result = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int index = 0; index < bytes.length; index += 3) {
            int first = bytes[index] & 0xff;
            int second = index + 1 < bytes.length ? bytes[index + 1] & 0xff : 0;
            int third = index + 2 < bytes.length ? bytes[index + 2] & 0xff : 0;
            result.append(alphabet[first >>> 2]);
            result.append(alphabet[((first & 3) << 4) | (second >>> 4)]);
            result.append(index + 1 < bytes.length
                    ? alphabet[((second & 15) << 2) | (third >>> 6)] : '=');
            result.append(index + 2 < bytes.length ? alphabet[third & 63] : '=');
        }
        return result.toString();
    }

    private static byte[] decodeBase64(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() % 4 != 0) {
            throw new IllegalArgumentException("invalid base64");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(clean.length() * 3 / 4);
        for (int index = 0; index < clean.length(); index += 4) {
            int a = base64Value(clean.charAt(index));
            int b = base64Value(clean.charAt(index + 1));
            int c = clean.charAt(index + 2) == '=' ? -1 : base64Value(clean.charAt(index + 2));
            int d = clean.charAt(index + 3) == '=' ? -1 : base64Value(clean.charAt(index + 3));
            output.write((a << 2) | (b >>> 4));
            if (c >= 0) output.write(((b & 15) << 4) | (c >>> 2));
            if (d >= 0) output.write(((c & 3) << 6) | d);
            if ((c < 0 && d >= 0) || (c < 0 && index + 4 != clean.length())
                    || (d < 0 && index + 4 != clean.length())) {
                throw new IllegalArgumentException("invalid base64 padding");
            }
        }
        return output.toByteArray();
    }

    private static int base64Value(char value) {
        if (value >= 'A' && value <= 'Z') return value - 'A';
        if (value >= 'a' && value <= 'z') return value - 'a' + 26;
        if (value >= '0' && value <= '9') return value - '0' + 52;
        if (value == '+') return 62;
        if (value == '/') return 63;
        throw new IllegalArgumentException("invalid base64 character");
    }

    private static String required(JSONObject object, String key) throws JSONException, IOException {
        String value = object.optString(key).trim();
        ensure(!value.isEmpty(), "数据包缺少 " + key);
        return value;
    }

    private static String requireId(String value, String label) {
        String clean = requireText(value, label);
        if (!clean.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return clean;
    }

    private static String requireText(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException(label + " must not be blank");
        return clean;
    }

    private static void ensure(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static final class Section {
        final String id;
        final Protection protection;
        final String path;
        final byte[] salt;
        final byte[] nonce;

        Section(String id, Protection protection, String path, byte[] salt, byte[] nonce) {
            this.id = id;
            this.protection = protection;
            this.path = path;
            this.salt = salt;
            this.nonce = nonce;
        }
    }

    private static final class CountingDigestOutputStream extends FilterOutputStream {
        private final MessageDigest digest;
        private final long limit;
        long count;

        CountingDigestOutputStream(OutputStream output, MessageDigest digest, long limit) {
            super(output);
            this.digest = digest;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(bytes, offset, length);
            digest.update(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int added) throws IOException {
            if (added < 0 || count > limit - added) throw new IOException("数据项目大小超出限制");
        }
    }

    private static final class CountingDigestInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private final long limit;
        long count;

        CountingDigestInputStream(InputStream input, MessageDigest digest, long limit) {
            super(input);
            this.digest = digest;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                ensureCapacity(1);
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                ensureCapacity(read);
                digest.update(bytes, offset, read);
                count += read;
            }
            return read;
        }

        private void ensureCapacity(int added) throws IOException {
            if (count > limit - added) throw new IOException("数据项目解压大小超出限制");
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public void close() {
        }
    }
}
