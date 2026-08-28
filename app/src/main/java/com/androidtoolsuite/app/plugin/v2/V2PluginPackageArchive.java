package com.androidtoolsuite.app.plugin.v2;

import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.PackagePathPolicy;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class V2PluginPackageArchive {
    private static final String MANIFEST_PATH = "manifest.json";
    private static final String INTEGRITY_PATH = "META-INF/ats-integrity.json";
    private static final String SIGNATURE_PATH = "META-INF/ats-signature.sig";

    private V2PluginPackageArchive() {
    }

    /**
     * Performs a bounded manifest probe so the host can route legacy and format-v3 packages to
     * different installers. Full path, integrity, payload and signature validation still happens
     * in {@link #extract(byte[], File)} before any generation becomes active.
     */
    public static boolean hasFormatV3Manifest(byte[] packageBytes) throws IOException {
        if (packageBytes == null || packageBytes.length == 0
                || packageBytes.length > ContractLimits.MAX_PACKAGE_BYTES) {
            throw new IOException("插件包为空或超出大小限制");
        }
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > ContractLimits.MAX_PACKAGE_ENTRIES) {
                    throw new IOException("插件包项目数超出限制");
                }
                if (!entry.isDirectory() && MANIFEST_PATH.equals(entry.getName())) {
                    byte[] manifest = readBounded(zip, ContractLimits.MAX_MANIFEST_BYTES);
                    try {
                        JSONObject root = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
                        Object rawVersion = root.opt("formatVersion");
                        return "ats-plugin".equals(root.optString("format", ""))
                                && rawVersion instanceof Number
                                && ((Number) rawVersion).intValue() == RuntimePluginManifest.FORMAT_VERSION;
                    } catch (JSONException error) {
                        throw new IOException("manifest.json 不是有效 JSON", error);
                    }
                }
                zip.closeEntry();
            }
        }
        return false;
    }

    public static VerifiedPackage extract(byte[] packageBytes, File targetDirectory)
            throws IOException, ContractException {
        return extract(packageBytes, targetDirectory, null);
    }

    public static VerifiedPackage extract(
            byte[] packageBytes,
            File targetDirectory,
            V2PublisherTrustStore trustStore
    ) throws IOException, ContractException {
        if (packageBytes == null || packageBytes.length == 0
                || packageBytes.length > ContractLimits.MAX_PACKAGE_BYTES) {
            throw new IOException("插件包为空或超出大小限制");
        }
        if (targetDirectory.exists() || !targetDirectory.mkdir()) {
            throw new IOException("无法创建插件 staging 目录");
        }

        List<File> createdFiles = new ArrayList<>();
        Map<String, EntryDigest> digests = new LinkedHashMap<>();
        Set<String> exactPaths = new HashSet<>();
        Set<String> foldedPaths = new HashSet<>();
        byte[] manifestBytes = null;
        byte[] integrityBytes = null;
        byte[] signatureBytes = null;
        long totalBytes = 0L;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > ContractLimits.MAX_PACKAGE_ENTRIES) {
                    throw new IOException("插件包项目数超出限制");
                }
                if (entry.isDirectory()) {
                    throw new IOException("format v3 插件包不得包含目录占位项");
                }
                String path = entry.getName();
                PackagePathPolicy.addUnique(exactPaths, foldedPaths, path);
                if (!isAllowedPath(path)) {
                    throw new IOException("插件包包含未知路径：" + path);
                }
                if (entry.getSize() > ContractLimits.MAX_PACKAGE_ENTRY_BYTES) {
                    throw new IOException("插件包文件超出单项大小限制：" + path);
                }

                EntryOutput output = readEntry(zip, path, targetDirectory, createdFiles);
                totalBytes += output.size;
                if (totalBytes > ContractLimits.MAX_PACKAGE_BYTES) {
                    throw new IOException("插件包解压后总大小超出限制");
                }
                if (MANIFEST_PATH.equals(path)) {
                    manifestBytes = output.bytes;
                } else if (INTEGRITY_PATH.equals(path)) {
                    integrityBytes = output.bytes;
                } else if (SIGNATURE_PATH.equals(path)) {
                    signatureBytes = output.bytes;
                } else {
                    digests.put(path, new EntryDigest(path, output.size, output.sha256));
                }
                zip.closeEntry();
            }
        } catch (IOException | ContractException error) {
            deleteCreated(targetDirectory, createdFiles);
            throw error;
        }

        try {
            if (manifestBytes == null || integrityBytes == null) {
                throw new IOException("format v3 插件包缺少 manifest.json 或完整性清单");
            }
            if (manifestBytes.length > ContractLimits.MAX_MANIFEST_BYTES) {
                throw new IOException("manifest.json 超出大小限制");
            }
            EntryDigest manifestDigest = new EntryDigest(
                    MANIFEST_PATH,
                    manifestBytes.length,
                    sha256(manifestBytes)
            );
            digests.put(MANIFEST_PATH, manifestDigest);
            verifyIntegrity(integrityBytes, digests);
            RuntimePluginManifest manifest = RuntimePluginManifest.parse(
                    new String(manifestBytes, StandardCharsets.UTF_8)
            );
            if (!manifest.platforms.contains("android")) {
                throw new ContractException("当前 Host 只安装声明 android 平台的插件");
            }
            validateManifestPayloads(manifest, exactPaths, targetDirectory);
            String publisherKeyFingerprint = "";
            if (!manifest.providerEntries.isEmpty()) {
                if (signatureBytes == null || signatureBytes.length == 0) {
                    throw new ContractException("Native Provider 包缺少 publisher 签名");
                }
                if (trustStore == null) {
                    throw new ContractException("Native Provider 包需要受信 publisher verifier");
                }
                publisherKeyFingerprint = trustStore.verify(manifest.plugin.publisher, integrityBytes, signatureBytes);
            }
            return new VerifiedPackage(
                    manifest,
                    sha256Hex(packageBytes),
                    signatureBytes != null && signatureBytes.length > 0,
                    publisherKeyFingerprint,
                    targetDirectory
            );
        } catch (IOException | ContractException | JSONException error) {
            deleteCreated(targetDirectory, createdFiles);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            if (error instanceof ContractException) {
                throw (ContractException) error;
            }
            throw new ContractException("完整性清单不是有效 JSON：" + error.getMessage(), error);
        }
    }

    /** Seals a fully assembled generation, including the preserved source package. */
    public static void sealGeneration(File targetDirectory) {
        setTreeReadOnly(targetDirectory);
    }

    private static EntryOutput readEntry(
            InputStream input,
            String path,
            File root,
            List<File> createdFiles
    ) throws IOException {
        MessageDigest digest = sha256Digest();
        boolean keepInMemory = MANIFEST_PATH.equals(path)
                || INTEGRITY_PATH.equals(path)
                || SIGNATURE_PATH.equals(path);
        ByteArrayOutputStream memory = keepInMemory ? new ByteArrayOutputStream() : null;
        File destination = resolveDestination(root, path);
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建插件包目录：" + path);
        }
        createdFiles.add(destination);
        long count = 0L;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > ContractLimits.MAX_PACKAGE_ENTRY_BYTES) {
                    throw new IOException("插件包文件超出单项大小限制：" + path);
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                if (memory != null) {
                    memory.write(buffer, 0, read);
                }
            }
            output.getFD().sync();
        }
        return new EntryOutput(count, hex(digest.digest()), memory == null ? null : memory.toByteArray());
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > limit) {
                    throw new IOException("manifest.json 超出大小限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static File resolveDestination(File root, String path) throws IOException {
        File destination = new File(root, path.replace('/', File.separatorChar));
        String rootPath = root.getCanonicalPath() + File.separator;
        String destinationPath = destination.getCanonicalPath();
        if (!destinationPath.startsWith(rootPath)) {
            throw new IOException("插件包路径越过 staging 目录：" + path);
        }
        return destination;
    }

    private static void verifyIntegrity(byte[] raw, Map<String, EntryDigest> actual)
            throws JSONException, ContractException {
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (!"sha256".equals(root.optString("algorithm", "")) || root.optInt("formatVersion", -1) != 1) {
            throw new ContractException("完整性清单版本或摘要算法不受支持");
        }
        JSONArray files = root.optJSONArray("files");
        if (files == null || files.length() != actual.size()) {
            throw new ContractException("完整性清单项目数与实际文件不一致");
        }
        List<String> expectedOrder = new ArrayList<>(actual.keySet());
        Collections.sort(expectedOrder);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < files.length(); index++) {
            JSONObject item = files.optJSONObject(index);
            if (item == null) {
                throw new ContractException("完整性清单包含非对象项目");
            }
            String path = item.optString("path", "");
            if (!seen.add(path) || !expectedOrder.get(index).equals(path)) {
                throw new ContractException("完整性清单路径重复或排序不规范");
            }
            EntryDigest digest = actual.get(path);
            if (digest == null
                    || item.optLong("size", -1L) != digest.size
                    || !digest.sha256.equals(item.optString("sha256", "").toLowerCase(Locale.ROOT))) {
                throw new ContractException("插件包完整性校验失败：" + path);
            }
        }
    }

    private static void validateManifestPayloads(
            RuntimePluginManifest manifest,
            Set<String> paths,
            File targetDirectory
    ) throws ContractException, IOException {
        for (RuntimePluginManifest.UiEntry entry : manifest.uiEntries) {
            if (!paths.contains(entry.entry)) {
                throw new ContractException("manifest 引用的 UI 入口不存在：" + entry.entry);
            }
            if ("declarative".equals(entry.type)) {
                File documentFile = resolveDestination(targetDirectory, entry.entry);
                byte[] raw;
                try (FileInputStream input = new FileInputStream(documentFile)) {
                    raw = readBounded(input, ContractLimits.MAX_MANIFEST_BYTES);
                }
                com.androidtoolsuite.runtime.contract.DeclarativeUiDocument document =
                        com.androidtoolsuite.runtime.contract.DeclarativeUiDocument.parse(
                                new String(raw, StandardCharsets.UTF_8)
                        );
                document.validateAgainst(manifest);
                if (document.isWebView() && !paths.contains(document.webEntry())) {
                    throw new ContractException("声明式 UI 引用的 WebView 入口不存在：" + document.webEntry());
                }
            }
        }
        for (RuntimePluginManifest.BackgroundEntry entry : manifest.backgroundEntries) {
            if (("javascript-worker".equals(entry.type) || "wasm-worker".equals(entry.type))
                    && !paths.contains(entry.entry)) {
                throw new ContractException("manifest 引用的后台入口不存在：" + entry.entry);
            }
        }
        boolean hasProviderPayload = paths.contains("android/provider.apk");
        for (String path : paths) {
            if (path.startsWith("android/") && !"android/provider.apk".equals(path)) {
                throw new ContractException("android/ 只允许受信 Provider 载荷 android/provider.apk");
            }
        }
        if (!"trusted-provider".equals(manifest.plugin.kind) && hasProviderPayload) {
            throw new ContractException("普通插件不得夹带 android/provider.apk");
        }
        if (!manifest.providerEntries.isEmpty() && !hasProviderPayload) {
            throw new ContractException("manifest 声明 Native Provider 但载荷不存在");
        }
    }

    private static boolean isAllowedPath(String path) {
        if (!PackagePathPolicy.isAllowedTopLevel(path)) {
            return false;
        }
        if (!path.startsWith("META-INF/")) {
            return true;
        }
        return INTEGRITY_PATH.equals(path) || SIGNATURE_PATH.equals(path);
    }

    private static void setTreeReadOnly(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    setTreeReadOnly(child);
                }
            }
            return;
        }
        file.setReadOnly();
    }

    private static void deleteCreated(File root, List<File> files) {
        Collections.reverse(files);
        for (File file : files) {
            file.setWritable(true);
            file.delete();
        }
        deleteEmptyDirectories(root);
    }

    private static void deleteEmptyDirectories(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteEmptyDirectories(child);
                }
            }
        }
        directory.setWritable(true);
        directory.delete();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(byte[] value) {
        return hex(sha256Digest().digest(value));
    }

    private static String sha256Hex(byte[] value) {
        return sha256(value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    private static final class EntryOutput {
        final long size;
        final String sha256;
        final byte[] bytes;

        EntryOutput(long size, String sha256, byte[] bytes) {
            this.size = size;
            this.sha256 = sha256;
            this.bytes = bytes;
        }
    }

    private static final class EntryDigest {
        final String path;
        final long size;
        final String sha256;

        EntryDigest(String path, long size, String sha256) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final class VerifiedPackage {
        public final RuntimePluginManifest manifest;
        public final String packageSha256;
        public final boolean hasSignature;
        public final String publisherKeyFingerprint;
        public final File extractedDirectory;

        VerifiedPackage(
                RuntimePluginManifest manifest,
                String packageSha256,
                boolean hasSignature,
                String publisherKeyFingerprint,
                File extractedDirectory
        ) {
            this.manifest = manifest;
            this.packageSha256 = packageSha256;
            this.hasSignature = hasSignature;
            this.publisherKeyFingerprint = publisherKeyFingerprint;
            this.extractedDirectory = extractedDirectory;
        }
    }
}
