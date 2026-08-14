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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/** Streaming .atsbackup v2 codec shared by the Migration Bridge and v2 importer. */
public final class BackupArchiveV2 {
    public static final int FORMAT_VERSION = 2;
    public static final long MAX_DATASET_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int KEY_BITS = 256;
    private static final int KDF_ITERATIONS = 310_000;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] AAD = "Android Tool Suite backup v2".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupArchiveV2() {
    }

    @FunctionalInterface
    public interface DatasetWriter {
        void write(OutputStream output) throws IOException;
    }

    @FunctionalInterface
    public interface DatasetReader {
        void read(DatasetRecord dataset, InputStream input) throws IOException;
    }

    public static final class DatasetSource {
        public final String pluginId;
        public final LegacyDatasetDescriptor descriptor;
        public final DatasetWriter writer;

        public DatasetSource(String pluginId, LegacyDatasetDescriptor descriptor, DatasetWriter writer) {
            this.pluginId = requireId(pluginId, "plugin id");
            this.descriptor = descriptor;
            this.writer = writer;
        }
    }

    public static final class DatasetRecord {
        public final String pluginId;
        public final LegacyDatasetDescriptor descriptor;

        private DatasetRecord(String pluginId, LegacyDatasetDescriptor descriptor) {
            this.pluginId = pluginId;
            this.descriptor = descriptor;
        }

        public String key() {
            return pluginId + "/" + descriptor.id;
        }
    }

    public static final class WriteRequest {
        public final String sourcePackage;
        public final String sourceVersionName;
        public final int sourceVersionCode;
        public final List<DatasetSource> datasets;
        public final char[] password;

        public WriteRequest(
                String sourcePackage,
                String sourceVersionName,
                int sourceVersionCode,
                List<DatasetSource> datasets,
                char[] password
        ) {
            this.sourcePackage = requireText(sourcePackage, "source package");
            this.sourceVersionName = requireText(sourceVersionName, "source version");
            if (sourceVersionCode <= 0) throw new IllegalArgumentException("sourceVersionCode must be positive");
            this.sourceVersionCode = sourceVersionCode;
            this.datasets = Collections.unmodifiableList(new ArrayList<>(datasets));
            this.password = password == null ? new char[0] : password.clone();
        }
    }

    public static final class ReadResult {
        public final String sourcePackage;
        public final String sourceVersionName;
        public final int sourceVersionCode;
        public final boolean encrypted;
        public final List<DatasetRecord> datasets;

        private ReadResult(
                String sourcePackage,
                String sourceVersionName,
                int sourceVersionCode,
                boolean encrypted,
                List<DatasetRecord> datasets
        ) {
            this.sourcePackage = sourcePackage;
            this.sourceVersionName = sourceVersionName;
            this.sourceVersionCode = sourceVersionCode;
            this.encrypted = encrypted;
            this.datasets = Collections.unmodifiableList(new ArrayList<>(datasets));
        }
    }

    public static void write(OutputStream target, WriteRequest request) throws IOException {
        validateSources(request.datasets);
        boolean containsSecret = request.datasets.stream().anyMatch(
                source -> source.descriptor.category == DatasetCategory.SECRET || source.descriptor.sensitive
        );
        boolean encrypted = request.password.length > 0;
        if (containsSecret && !encrypted) {
            throw new IOException("包含敏感数据的迁移包必须设置密码");
        }

        byte[] salt = encrypted ? randomBytes(16) : null;
        byte[] nonce = encrypted ? randomBytes(12) : null;
        JSONObject manifest = manifest(request, encrypted);
        JSONObject datasets = datasetsJson(request.datasets);

        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            writeJsonEntry(zip, "manifest.json", manifest);
            writeJsonEntry(zip, "datasets.json", datasets);
            if (encrypted) {
                writeJsonEntry(zip, "crypto.json", cryptoJson(salt, nonce));
                putEntry(zip, "payload.enc");
                try {
                    Cipher cipher = archiveCipher(Cipher.ENCRYPT_MODE, request.password, salt, nonce);
                    CipherOutputStream cipherOutput = new CipherOutputStream(new NonClosingOutputStream(zip), cipher);
                    ZipOutputStream payload = new ZipOutputStream(cipherOutput);
                    writePayload(payload, request.datasets);
                    payload.finish();
                    payload.close();
                } catch (GeneralSecurityException error) {
                    throw new IOException("无法初始化迁移包加密", error);
                } finally {
                    zip.closeEntry();
                }
            } else {
                writePayload(zip, request.datasets);
            }
        } finally {
            java.util.Arrays.fill(request.password, '\0');
        }
    }

    public static ReadResult read(
            InputStream source,
            char[] suppliedPassword,
            DatasetReader reader
    ) throws IOException {
        char[] password = suppliedPassword == null ? new char[0] : suppliedPassword.clone();
        JSONObject manifest = null;
        List<DatasetRecord> datasets = null;
        JSONObject crypto = null;
        boolean payloadRead = false;
        PlainReadState plainState = null;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                switch (name) {
                    case "manifest.json":
                        ensure(manifest == null, "迁移包包含重复 manifest.json");
                        manifest = new JSONObject(readSmall(zip));
                        validateManifest(manifest);
                        break;
                    case "datasets.json":
                        ensure(datasets == null, "迁移包包含重复 datasets.json");
                        datasets = parseDatasets(new JSONObject(readSmall(zip)));
                        break;
                    case "crypto.json":
                        ensure(crypto == null, "迁移包包含重复 crypto.json");
                        crypto = new JSONObject(readSmall(zip));
                        break;
                    case "payload.enc":
                        ensure(!payloadRead, "迁移包包含重复 payload");
                        ensure(manifest != null && datasets != null && crypto != null,
                                "迁移包元数据必须位于加密载荷之前");
                        ensure(manifest.optBoolean("encrypted", false), "迁移包加密标记不一致");
                        ensure(password.length > 0, "迁移包需要密码");
                        readEncryptedPayload(zip, password, crypto, datasets, reader);
                        payloadRead = true;
                        break;
                    default:
                        if (name.startsWith("payload/") || "integrity.json".equals(name)) {
                            ensure(manifest != null && datasets != null, "迁移包元数据必须位于载荷之前");
                            ensure(!manifest.optBoolean("encrypted", false), "加密迁移包包含明文载荷");
                            if (plainState == null) plainState = new PlainReadState(datasets);
                            readPlainPayload(zip, entry, datasets, reader, plainState);
                            payloadRead = true;
                        } else {
                            throw new IOException("迁移包包含未知文件：" + name);
                        }
                }
                zip.closeEntry();
            }
        } catch (JSONException error) {
            throw new IOException("迁移包 JSON 无效", error);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        ensure(manifest != null, "迁移包缺少 manifest.json");
        ensure(datasets != null, "迁移包缺少 datasets.json");
        ensure(payloadRead || datasets.isEmpty(), "迁移包缺少 payload");
        ensure(manifest.optBoolean("encrypted", false) || datasets.isEmpty()
                        || (plainState != null && plainState.validated),
                "迁移包缺少 integrity.json");
        return new ReadResult(
                manifest.optString("sourcePackage"),
                manifest.optString("sourceVersionName"),
                manifest.optInt("sourceVersionCode"),
                manifest.optBoolean("encrypted", false),
                datasets
        );
    }

    /** Reads only authenticated-by-schema metadata for UI selection; payload integrity is checked by read(). */
    public static ReadResult inspect(InputStream source) throws IOException {
        JSONObject manifest = null;
        List<DatasetRecord> datasets = null;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("manifest.json".equals(name)) {
                    ensure(manifest == null, "迁移包包含重复 manifest.json");
                    manifest = new JSONObject(readSmall(zip));
                    validateManifest(manifest);
                } else if ("datasets.json".equals(name)) {
                    ensure(datasets == null, "迁移包包含重复 datasets.json");
                    datasets = parseDatasets(new JSONObject(readSmall(zip)));
                } else if ("payload.enc".equals(name) || name.startsWith("payload/")) {
                    break;
                } else if (!entry.isDirectory() && !"crypto.json".equals(name)) {
                    throw new IOException("迁移包包含未知文件：" + name);
                }
                zip.closeEntry();
            }
        } catch (JSONException error) {
            throw new IOException("迁移包 JSON 无效", error);
        }
        ensure(manifest != null, "迁移包缺少 manifest.json");
        ensure(datasets != null, "迁移包缺少 datasets.json");
        return new ReadResult(
                manifest.optString("sourcePackage"),
                manifest.optString("sourceVersionName"),
                manifest.optInt("sourceVersionCode"),
                manifest.optBoolean("encrypted", false),
                datasets
        );
    }

    private static void readEncryptedPayload(
            ZipInputStream outer,
            char[] password,
            JSONObject crypto,
            List<DatasetRecord> datasets,
            DatasetReader reader
    ) throws IOException {
        try {
            byte[] salt = decode(crypto, "salt");
            byte[] nonce = decode(crypto, "nonce");
            ensure(crypto.optInt("formatVersion", 0) == 1
                            && "PBKDF2WithHmacSHA256".equals(crypto.optString("kdf"))
                            && crypto.optInt("iterations", 0) == KDF_ITERATIONS
                            && "AES/GCM/NoPadding".equals(crypto.optString("cipher"))
                            && crypto.optInt("tagBits", 0) == GCM_TAG_BITS,
                    "不支持的迁移包加密参数");
            Cipher cipher = archiveCipher(Cipher.DECRYPT_MODE, password, salt, nonce);
            CipherInputStream decrypted = new CipherInputStream(new NonClosingInputStream(outer), cipher);
            try (ZipInputStream payload = new ZipInputStream(decrypted)) {
                readNestedPayload(payload, datasets, reader);
            } catch (IOException error) {
                if (hasCause(error, AEADBadTagException.class)) {
                    throw new IOException("迁移包密码错误或加密载荷已损坏", error);
                }
                throw error;
            }
        } catch (GeneralSecurityException | IllegalArgumentException | JSONException error) {
            throw new IOException("迁移包密码错误或加密参数无效", error);
        }
    }

    private static void readNestedPayload(
            ZipInputStream payload,
            List<DatasetRecord> datasets,
            DatasetReader reader
    ) throws IOException {
        Map<String, String> actualDigests = new LinkedHashMap<>();
        Map<String, Long> actualSizes = new LinkedHashMap<>();
        JSONObject integrity = null;
        ZipEntry entry;
        while ((entry = payload.getNextEntry()) != null) {
            if ("integrity.json".equals(entry.getName())) {
                integrity = json(readSmall(payload));
            } else {
                DatasetRecord dataset = findByPath(datasets, entry.getName());
                readOneDataset(payload, dataset, reader, actualDigests, actualSizes);
            }
            payload.closeEntry();
        }
        validateIntegrity(datasets, integrity, actualDigests, actualSizes);
    }

    private static void readPlainPayload(
            ZipInputStream zip,
            ZipEntry entry,
            List<DatasetRecord> datasets,
            DatasetReader reader,
            PlainReadState state
    ) throws IOException {
        if ("integrity.json".equals(entry.getName())) {
            ensure(!state.validated, "迁移包包含重复 integrity.json");
            validateIntegrity(datasets, json(readSmall(zip)), state.digests, state.sizes);
            state.validated = true;
            return;
        }
        ensure(!state.validated, "integrity.json 后不能再包含 Dataset");
        DatasetRecord dataset = findByPath(datasets, entry.getName());
        readOneDataset(zip, dataset, reader, state.digests, state.sizes);
    }

    private static void readOneDataset(
            InputStream input,
            DatasetRecord dataset,
            DatasetReader reader,
            Map<String, String> digests,
            Map<String, Long> sizes
    ) throws IOException {
        ensure(!digests.containsKey(dataset.key()), "迁移包包含重复 Dataset：" + dataset.key());
        MessageDigest digest = sha256Digest();
        CountingDigestInputStream bounded = new CountingDigestInputStream(input, digest, MAX_DATASET_BYTES);
        reader.read(dataset, bounded);
        drain(bounded);
        digests.put(dataset.key(), hex(digest.digest()));
        sizes.put(dataset.key(), bounded.count);
    }

    private static void writePayload(ZipOutputStream payload, List<DatasetSource> sources) throws IOException {
        JSONObject integrity = new JSONObject();
        try {
            for (DatasetSource source : sources) {
                putEntry(payload, datasetPath(source.pluginId, source.descriptor.id));
                MessageDigest digest = sha256Digest();
                CountingDigestOutputStream output = new CountingDigestOutputStream(
                        new NonClosingOutputStream(payload), digest, MAX_DATASET_BYTES
                );
                source.writer.write(output);
                output.flush();
                payload.closeEntry();
                integrity.put(source.pluginId + "/" + source.descriptor.id, new JSONObject()
                        .put("size", output.count)
                        .put("sha256", hex(digest.digest())));
            }
            writeJsonEntry(payload, "integrity.json", integrity);
        } catch (JSONException error) {
            throw new IOException("无法生成完整性清单", error);
        }
    }

    private static JSONObject manifest(WriteRequest request, boolean encrypted) throws IOException {
        try {
            SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
            timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new JSONObject()
                    .put("format", "atsbackup")
                    .put("formatVersion", FORMAT_VERSION)
                    .put("exportedAt", timestamp.format(new Date()))
                    .put("sourcePackage", request.sourcePackage)
                    .put("sourceVersionName", request.sourceVersionName)
                    .put("sourceVersionCode", request.sourceVersionCode)
                    .put("encrypted", encrypted)
                    .put("datasetCount", request.datasets.size());
        } catch (JSONException error) {
            throw new IOException("无法生成迁移包清单", error);
        }
    }

    private static JSONObject datasetsJson(List<DatasetSource> sources) throws IOException {
        try {
            JSONArray array = new JSONArray();
            for (DatasetSource source : sources) {
                LegacyDatasetDescriptor item = source.descriptor;
                array.put(new JSONObject()
                        .put("pluginId", source.pluginId)
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("category", item.category.name())
                        .put("estimatedSize", item.estimatedSize)
                        .put("dataFormatVersion", item.dataFormatVersion)
                        .put("sensitive", item.sensitive)
                        .put("restoreMode", item.restoreMode.name())
                        .put("dependencies", new JSONArray(item.dependencies)));
            }
            return new JSONObject().put("datasets", array);
        } catch (JSONException error) {
            throw new IOException("无法生成 Dataset 清单", error);
        }
    }

    private static JSONObject cryptoJson(byte[] salt, byte[] nonce) throws IOException {
        try {
            return new JSONObject()
                    .put("formatVersion", 1)
                    .put("kdf", "PBKDF2WithHmacSHA256")
                    .put("iterations", KDF_ITERATIONS)
                    .put("salt", encodeBase64(salt))
                    .put("cipher", "AES/GCM/NoPadding")
                    .put("nonce", encodeBase64(nonce))
                    .put("tagBits", GCM_TAG_BITS);
        } catch (JSONException error) {
            throw new IOException("无法生成加密清单", error);
        }
    }

    private static List<DatasetRecord> parseDatasets(JSONObject root) throws IOException, JSONException {
        JSONArray array = root.optJSONArray("datasets");
        ensure(array != null, "迁移包缺少 Dataset 列表");
        List<DatasetRecord> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            String pluginId = requireId(item.getString("pluginId"), "plugin id");
            String id = requireId(item.getString("id"), "dataset id");
            JSONArray dependencyArray = item.optJSONArray("dependencies");
            List<String> dependencies = new ArrayList<>();
            if (dependencyArray != null) {
                for (int dependencyIndex = 0; dependencyIndex < dependencyArray.length(); dependencyIndex++) {
                    dependencies.add(dependencyArray.getString(dependencyIndex));
                }
            }
            LegacyDatasetDescriptor descriptor = new LegacyDatasetDescriptor(
                    id,
                    item.getString("name"),
                    DatasetCategory.valueOf(item.getString("category")),
                    item.optLong("estimatedSize", 0L),
                    item.getInt("dataFormatVersion"),
                    item.optBoolean("sensitive", false),
                    DatasetRestoreMode.valueOf(item.getString("restoreMode")),
                    dependencies
            );
            DatasetRecord record = new DatasetRecord(pluginId, descriptor);
            ensure(keys.add(record.key()), "迁移包包含重复 Dataset：" + record.key());
            result.add(record);
        }
        return result;
    }

    private static void validateManifest(JSONObject manifest) throws IOException, JSONException {
        ensure("atsbackup".equals(manifest.optString("format")), "不支持的迁移包类型");
        ensure(manifest.optInt("formatVersion", 0) == FORMAT_VERSION, "不支持的迁移包版本");
        ensure(manifest.getInt("sourceVersionCode") > 0, "迁移包来源版本无效");
        required(manifest, "sourcePackage");
        required(manifest, "sourceVersionName");
    }

    private static void validateIntegrity(
            List<DatasetRecord> datasets,
            JSONObject integrity,
            Map<String, String> actualDigests,
            Map<String, Long> actualSizes
    ) throws IOException {
        ensure(integrity != null, "迁移包缺少 integrity.json");
        ensure(actualDigests.size() == datasets.size(), "迁移包缺少 Dataset 载荷");
        try {
            for (DatasetRecord dataset : datasets) {
                JSONObject expected = integrity.optJSONObject(dataset.key());
                ensure(expected != null, "迁移包缺少 Dataset 校验：" + dataset.key());
                ensure(expected.getLong("size") == actualSizes.getOrDefault(dataset.key(), -1L),
                        "Dataset 大小校验失败：" + dataset.key());
                ensure(expected.getString("sha256").equalsIgnoreCase(actualDigests.get(dataset.key())),
                        "Dataset 摘要校验失败：" + dataset.key());
            }
        } catch (JSONException error) {
            throw new IOException("迁移包完整性清单无效", error);
        }
    }

    private static void validateSources(List<DatasetSource> sources) throws IOException {
        Set<String> keys = new HashSet<>();
        for (DatasetSource source : sources) {
            ensure(source != null && source.descriptor != null && source.writer != null, "Dataset 来源无效");
            String key = source.pluginId + "/" + source.descriptor.id;
            ensure(keys.add(key), "包含重复 Dataset：" + key);
        }
        for (DatasetSource source : sources) {
            for (String dependency : source.descriptor.dependencies) {
                ensure(keys.contains(source.pluginId + "/" + dependency),
                        "Dataset 缺少依赖：" + source.pluginId + "/" + dependency);
            }
        }
    }

    private static DatasetRecord findByPath(List<DatasetRecord> datasets, String path) throws IOException {
        for (DatasetRecord dataset : datasets) {
            if (datasetPath(dataset.pluginId, dataset.descriptor.id).equals(path)) return dataset;
        }
        throw new IOException("迁移包包含未声明的 Dataset：" + path);
    }

    private static Cipher archiveCipher(int mode, char[] password, byte[] salt, byte[] nonce)
            throws GeneralSecurityException, IOException {
        ensure(salt.length == 16 && nonce.length == 12, "迁移包加密参数长度无效");
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS);
        byte[] rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return cipher;
        } finally {
            spec.clearPassword();
            java.util.Arrays.fill(rawKey, (byte) 0);
        }
    }

    private static byte[] decode(JSONObject object, String key) throws JSONException {
        return decodeBase64(object.getString(key));
    }

    private static String encodeBase64(byte[] bytes) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder result = new StringBuilder((bytes.length + 2) / 3 * 4);
        for (int index = 0; index < bytes.length; index += 3) {
            int first = bytes[index] & 0xff;
            int second = index + 1 < bytes.length ? bytes[index + 1] & 0xff : 0;
            int third = index + 2 < bytes.length ? bytes[index + 2] & 0xff : 0;
            result.append(alphabet[first >>> 2]);
            result.append(alphabet[((first & 3) << 4) | (second >>> 4)]);
            result.append(index + 1 < bytes.length ? alphabet[((second & 15) << 2) | (third >>> 6)] : '=');
            result.append(index + 2 < bytes.length ? alphabet[third & 63] : '=');
        }
        return result.toString();
    }

    private static byte[] decodeBase64(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() % 4 != 0) throw new IllegalArgumentException("invalid base64");
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

    private static JSONObject json(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException error) {
            throw new IOException("迁移包 JSON 无效", error);
        }
    }

    private static void writeJsonEntry(ZipOutputStream zip, String path, JSONObject value) throws IOException {
        putEntry(zip, path);
        zip.write((value.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void putEntry(ZipOutputStream zip, String path) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setTime(0L);
        zip.putNextEntry(entry);
    }

    private static String readSmall(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_JSON_BYTES) throw new IOException("迁移包 JSON 过大");
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (input.read(buffer) != -1) {
            // Readers may stop early; drain so the digest covers the complete declared Dataset.
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

    private static String datasetPath(String pluginId, String datasetId) {
        return "payload/" + pluginId + "/" + datasetId;
    }

    private static String required(JSONObject object, String key) throws JSONException, IOException {
        String value = object.optString(key).trim();
        ensure(!value.isEmpty(), "迁移包缺少 " + key);
        return value;
    }

    private static String requireId(String value, String label) {
        String clean = requireText(value, label);
        if (!clean.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException(label + " is invalid");
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

    private static final class PlainReadState {
        final List<DatasetRecord> datasets;
        final Map<String, String> digests = new HashMap<>();
        final Map<String, Long> sizes = new HashMap<>();
        boolean validated;

        PlainReadState(List<DatasetRecord> datasets) {
            this.datasets = datasets;
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
            if (added < 0 || count > limit - added) throw new IOException("Dataset 大小超出限制");
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
            if (count > limit - added) throw new IOException("Dataset 解压大小超出限制");
        }
    }

    private static final class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream output) { super(output); }
        @Override public void close() throws IOException { flush(); }
    }

    private static final class NonClosingInputStream extends FilterInputStream {
        NonClosingInputStream(InputStream input) { super(input); }
        @Override public void close() { }
    }
}
