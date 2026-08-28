package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.OriginKey;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class V2PackageStore {
    private static final String PREFS_NAME = "runtime_v2_packages";
    private static final String PREF_ENABLED_IDS = "enabled_ids";
    private static final String PREF_PENDING_ORIGINS = "pending_origins";
    private static final String PREF_ROLLBACK_PREFIX = "rollback_";
    private static final String PREF_ROLLBACK_PLUGIN_PREFIX = "rollback_plugin_";
    private static final String PREF_ROLLBACK_SOURCE_PREFIX = "rollback_source_";
    private static final String PREF_ROLLBACK_CHANNEL_PREFIX = "rollback_channel_";
    private static final String PREF_ROLLBACK_VERIFIED_PREFIX = "rollback_verified_";
    private static final String PREF_SOURCE_PREFIX = "source_";
    private static final String PREF_CHANNEL_PREFIX = "channel_";
    private static final String PREF_VERIFIED_PREFIX = "verified_";
    private static final String ACTIVE_FILE = "active-generation";
    private static final String PACKAGE_FILE = "package.atsplugin";

    private final Context context;
    private final File root;
    private final File stagingRoot;
    private final SharedPreferences preferences;
    private final V2PublisherTrustStore trustStore;

    public V2PackageStore(Context context) {
        this.context = context.getApplicationContext();
        this.root = new File(this.context.getFilesDir(), "runtime-v2/packages");
        this.stagingRoot = new File(root, "staging");
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.trustStore = new V2PublisherTrustStore(this.context);
        recoverInterruptedInstalls();
    }

    public synchronized InstallSession install(
            byte[] packageBytes,
            String source,
            String channel,
            boolean repositoryVerified
    ) throws IOException, ContractException {
        return install(packageBytes, source, channel, repositoryVerified, false);
    }

    /** Debug tooling may explicitly replace a same-version development generation. */
    public synchronized InstallSession install(
            byte[] packageBytes,
            String source,
            String channel,
            boolean repositoryVerified,
            boolean allowSameVersionReplacement
    ) throws IOException, ContractException {
        ensureDirectory(stagingRoot);
        File staging = new File(stagingRoot, UUID.randomUUID().toString());
        V2PluginPackageArchive.VerifiedPackage verified = V2PluginPackageArchive.extract(
                packageBytes, staging, trustStore
        );
        RuntimePluginManifest manifest = verified.manifest;
        InstalledPlugin existing = find(manifest.plugin.id);
        if (existing != null && manifest.plugin.versionCode < existing.manifest.plugin.versionCode) {
            deleteRecursively(staging);
            throw new ContractException("默认禁止降级 Runtime v2 插件");
        }
        String originKey = OriginKey.fromPluginId(manifest.plugin.id);
        File pluginDirectory = pluginDirectory(originKey);
        File generations = new File(pluginDirectory, "generations");
        ensureDirectory(generations);

        File storedPackage = new File(staging, PACKAGE_FILE);
        writeFile(storedPackage, packageBytes);
        V2PluginPackageArchive.sealGeneration(staging);

        String generationName = "v" + manifest.plugin.versionCode + "-"
                + verified.packageSha256.substring(0, 16);
        if (existing != null
                && manifest.plugin.versionCode == existing.manifest.plugin.versionCode
                && !generationName.equals(existing.generationDirectory.getName())
                && !allowSameVersionReplacement) {
            deleteRecursively(staging);
            throw new ContractException("相同 versionCode 的 Runtime v2 插件内容不同");
        }
        File generation = new File(generations, generationName);
        if (generation.exists()) {
            deleteRecursively(staging);
            if (!new File(generation, MANIFEST_FILE()).isFile()
                    || !new File(generation, PACKAGE_FILE).isFile()) {
                throw new IOException("目标 generation 已存在但内容不完整");
            }
        } else if (!staging.renameTo(generation)) {
            deleteRecursively(staging);
            throw new IOException("无法把已验证插件切换到 generation 目录");
        }

        String previous = readActiveGeneration(pluginDirectory);
        String previousSource = preferences.getString(PREF_SOURCE_PREFIX + manifest.plugin.id, "");
        String previousChannel = preferences.getString(PREF_CHANNEL_PREFIX + manifest.plugin.id, "");
        boolean previousVerified = preferences.getBoolean(PREF_VERIFIED_PREFIX + manifest.plugin.id, false);
        writeActiveGeneration(pluginDirectory, generationName);
        LinkedHashSet<String> pending = new LinkedHashSet<>(
                preferences.getStringSet(PREF_PENDING_ORIGINS, Collections.emptySet())
        );
        pending.add(originKey);
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet(PREF_PENDING_ORIGINS, pending)
                .putString(PREF_ROLLBACK_PREFIX + originKey, previous)
                .putString(PREF_ROLLBACK_PLUGIN_PREFIX + originKey, manifest.plugin.id)
                .putString(PREF_ROLLBACK_SOURCE_PREFIX + originKey, previousSource)
                .putString(PREF_ROLLBACK_CHANNEL_PREFIX + originKey, previousChannel)
                .putBoolean(PREF_ROLLBACK_VERIFIED_PREFIX + originKey, previousVerified)
                .putString(PREF_SOURCE_PREFIX + manifest.plugin.id, clean(source))
                .putString(PREF_CHANNEL_PREFIX + manifest.plugin.id, clean(channel))
                .putBoolean(PREF_VERIFIED_PREFIX + manifest.plugin.id, repositoryVerified);
        if (!editor.commit()) {
            restorePointerAfterFailedInstall(pluginDirectory, previous, generationName);
            throw new IOException("无法提交 Runtime v2 安装记录");
        }
        return new InstallSession(
                manifest.plugin.id,
                originKey,
                generationName,
                previous,
                previous == null || previous.isEmpty(),
                previousSource,
                previousChannel,
                previousVerified
        );
    }

    public synchronized void confirmInstall(InstallSession session) {
        clearPending(session.originKey);
        pruneGenerations(session.originKey, session.generationName, session.previousGeneration);
    }

    public synchronized void rollbackInstall(InstallSession session) {
        File pluginDirectory = pluginDirectory(session.originKey);
        try {
            if (session.previousGeneration == null || session.previousGeneration.isEmpty()) {
                deleteActiveGeneration(pluginDirectory);
            } else {
                writeActiveGeneration(pluginDirectory, session.previousGeneration);
            }
        } catch (IOException ignored) {
        }
        if (!session.generationName.equals(session.previousGeneration)) {
            deleteRecursively(new File(new File(pluginDirectory, "generations"), session.generationName));
        }
        preferences.edit()
                .putString(PREF_SOURCE_PREFIX + session.pluginId, session.previousSource)
                .putString(PREF_CHANNEL_PREFIX + session.pluginId, session.previousChannel)
                .putBoolean(PREF_VERIFIED_PREFIX + session.pluginId, session.previousVerified)
                .commit();
        clearPending(session.originKey);
    }

    public synchronized List<InstalledPlugin> load() {
        List<InstalledPlugin> result = new ArrayList<>();
        File[] pluginDirectories = root.listFiles(file -> file.isDirectory() && !"staging".equals(file.getName()));
        if (pluginDirectories == null) {
            return result;
        }
        for (File pluginDirectory : pluginDirectories) {
            String generationName = readActiveGeneration(pluginDirectory);
            if (!isSafeGenerationName(generationName)) {
                continue;
            }
            File generation = new File(new File(pluginDirectory, "generations"), generationName);
            File manifestFile = new File(generation, MANIFEST_FILE());
            File packageFile = new File(generation, PACKAGE_FILE);
            if (!manifestFile.isFile() || !packageFile.isFile()) {
                continue;
            }
            try {
                String raw = new String(readFile(manifestFile, ContractLimits.MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
                RuntimePluginManifest manifest = RuntimePluginManifest.parse(raw);
                if (!OriginKey.fromPluginId(manifest.plugin.id).equals(pluginDirectory.getName())) {
                    continue;
                }
                result.add(new InstalledPlugin(
                        manifest,
                        generation,
                        packageFile,
                        isEnabled(manifest.plugin.id),
                        preferences.getString(PREF_SOURCE_PREFIX + manifest.plugin.id, ""),
                        preferences.getString(PREF_CHANNEL_PREFIX + manifest.plugin.id, ""),
                        preferences.getBoolean(PREF_VERIFIED_PREFIX + manifest.plugin.id, false)
                ));
            } catch (IOException | ContractException ignored) {
            }
        }
        result.sort(Comparator.comparing(item -> item.manifest.plugin.title, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized InstalledPlugin find(String pluginId) {
        for (InstalledPlugin installed : load()) {
            if (installed.manifest.plugin.id.equals(pluginId)) {
                return installed;
            }
        }
        return null;
    }

    public boolean isEnabled(String pluginId) {
        return preferences.getStringSet(PREF_ENABLED_IDS, Collections.emptySet()).contains(pluginId);
    }

    public void setEnabled(String pluginId, boolean enabled) {
        LinkedHashSet<String> values = new LinkedHashSet<>(
                preferences.getStringSet(PREF_ENABLED_IDS, Collections.emptySet())
        );
        if (enabled) {
            values.add(pluginId);
        } else {
            values.remove(pluginId);
        }
        preferences.edit().putStringSet(PREF_ENABLED_IDS, values).apply();
    }

    public synchronized byte[] exportPackage(String pluginId) throws IOException {
        InstalledPlugin installed = find(pluginId);
        if (installed == null) {
            throw new IOException("Runtime v2 插件不存在");
        }
        return readFile(installed.packageFile, ContractLimits.MAX_PACKAGE_BYTES);
    }

    public synchronized void delete(String pluginId) throws IOException, ContractException {
        String originKey = OriginKey.fromPluginId(pluginId);
        File pluginDirectory = pluginDirectory(originKey);
        if (!pluginDirectory.exists()) {
            return;
        }
        File trashRoot = new File(context.getCacheDir(), "runtime-v2-package-trash");
        ensureDirectory(trashRoot);
        File trash = new File(trashRoot, originKey + "-" + UUID.randomUUID());
        if (!pluginDirectory.renameTo(trash)) {
            throw new IOException("无法移除 Runtime v2 插件目录");
        }
        LinkedHashSet<String> enabled = new LinkedHashSet<>(
                preferences.getStringSet(PREF_ENABLED_IDS, Collections.emptySet())
        );
        enabled.remove(pluginId);
        boolean committed = preferences.edit()
                .putStringSet(PREF_ENABLED_IDS, enabled)
                .remove(PREF_SOURCE_PREFIX + pluginId)
                .remove(PREF_CHANNEL_PREFIX + pluginId)
                .remove(PREF_VERIFIED_PREFIX + pluginId)
                .commit();
        if (!committed) {
            trash.renameTo(pluginDirectory);
            throw new IOException("无法提交 Runtime v2 删除记录");
        }
        deleteRecursively(trash);
    }

    private void recoverInterruptedInstalls() {
        Set<String> pending = new HashSet<>(
                preferences.getStringSet(PREF_PENDING_ORIGINS, Collections.emptySet())
        );
        for (String originKey : pending) {
            if (!originKey.matches("[0-9a-f]{40}")) {
                clearPending(originKey);
                continue;
            }
            File pluginDirectory = pluginDirectory(originKey);
            String current = readActiveGeneration(pluginDirectory);
            String previous = preferences.getString(PREF_ROLLBACK_PREFIX + originKey, "");
            String pluginId = preferences.getString(PREF_ROLLBACK_PLUGIN_PREFIX + originKey, "");
            try {
                if (previous.isEmpty()) {
                    deleteActiveGeneration(pluginDirectory);
                } else if (isSafeGenerationName(previous)) {
                    writeActiveGeneration(pluginDirectory, previous);
                }
            } catch (IOException ignored) {
            }
            if (isSafeGenerationName(current) && !current.equals(previous)) {
                deleteRecursively(new File(new File(pluginDirectory, "generations"), current));
            }
            if (!pluginId.isEmpty()) {
                preferences.edit()
                        .putString(
                                PREF_SOURCE_PREFIX + pluginId,
                                preferences.getString(PREF_ROLLBACK_SOURCE_PREFIX + originKey, "")
                        )
                        .putString(
                                PREF_CHANNEL_PREFIX + pluginId,
                                preferences.getString(PREF_ROLLBACK_CHANNEL_PREFIX + originKey, "")
                        )
                        .putBoolean(
                                PREF_VERIFIED_PREFIX + pluginId,
                                preferences.getBoolean(PREF_ROLLBACK_VERIFIED_PREFIX + originKey, false)
                        )
                        .commit();
            }
            clearPending(originKey);
        }
        deleteRecursively(stagingRoot);
    }

    private void restorePointerAfterFailedInstall(File pluginDirectory, String previous, String current) {
        try {
            if (previous == null || previous.isEmpty()) {
                deleteActiveGeneration(pluginDirectory);
            } else {
                writeActiveGeneration(pluginDirectory, previous);
            }
        } catch (IOException ignored) {
        }
        deleteRecursively(new File(new File(pluginDirectory, "generations"), current));
    }

    private void clearPending(String originKey) {
        LinkedHashSet<String> pending = new LinkedHashSet<>(
                preferences.getStringSet(PREF_PENDING_ORIGINS, Collections.emptySet())
        );
        pending.remove(originKey);
        preferences.edit()
                .putStringSet(PREF_PENDING_ORIGINS, pending)
                .remove(PREF_ROLLBACK_PREFIX + originKey)
                .remove(PREF_ROLLBACK_PLUGIN_PREFIX + originKey)
                .remove(PREF_ROLLBACK_SOURCE_PREFIX + originKey)
                .remove(PREF_ROLLBACK_CHANNEL_PREFIX + originKey)
                .remove(PREF_ROLLBACK_VERIFIED_PREFIX + originKey)
                .commit();
    }

    private void pruneGenerations(String originKey, String active, String previous) {
        File generations = new File(pluginDirectory(originKey), "generations");
        File[] children = generations.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.getName().equals(active) && !child.getName().equals(previous)) {
                deleteRecursively(child);
            }
        }
    }

    private File pluginDirectory(String originKey) {
        return new File(root, originKey);
    }

    private static String MANIFEST_FILE() {
        return "manifest.json";
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建目录：" + directory.getName());
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            ensureDirectory(parent);
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static byte[] readFile(File file, long limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if ((long) output.size() + read > limit) {
                    throw new IOException("文件大小超出限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readActiveGeneration(File pluginDirectory) {
        File file = new File(pluginDirectory, ACTIVE_FILE);
        if (!file.isFile()) {
            return "";
        }
        try {
            return new String(readFile(file, 256), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void writeActiveGeneration(File pluginDirectory, String generation) throws IOException {
        if (!isSafeGenerationName(generation)) {
            throw new IOException("generation 名称无效");
        }
        ensureDirectory(pluginDirectory);
        AtomicFile atomicFile = new AtomicFile(new File(pluginDirectory, ACTIVE_FILE));
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(generation.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomicFile.finishWrite(output);
        } catch (IOException error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            throw error;
        }
    }

    private static void deleteActiveGeneration(File pluginDirectory) throws IOException {
        File active = new File(pluginDirectory, ACTIVE_FILE);
        if (active.exists() && !active.delete()) {
            throw new IOException("无法删除 active generation 指针");
        }
    }

    private static boolean isSafeGenerationName(String value) {
        return value != null && value.matches("v[1-9][0-9]*-[0-9a-f]{16}");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        file.setWritable(true);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    public static final class InstallSession {
        public final String pluginId;
        public final String originKey;
        public final String generationName;
        public final String previousGeneration;
        public final boolean newInstall;
        final String previousSource;
        final String previousChannel;
        final boolean previousVerified;

        InstallSession(
                String pluginId,
                String originKey,
                String generationName,
                String previousGeneration,
                boolean newInstall,
                String previousSource,
                String previousChannel,
                boolean previousVerified
        ) {
            this.pluginId = pluginId;
            this.originKey = originKey;
            this.generationName = generationName;
            this.previousGeneration = previousGeneration == null ? "" : previousGeneration;
            this.newInstall = newInstall;
            this.previousSource = previousSource;
            this.previousChannel = previousChannel;
            this.previousVerified = previousVerified;
        }
    }

    public static final class InstalledPlugin {
        public final RuntimePluginManifest manifest;
        public final File generationDirectory;
        public final File packageFile;
        public final boolean enabled;
        public final String source;
        public final String channel;
        public final boolean repositoryVerified;

        InstalledPlugin(
                RuntimePluginManifest manifest,
                File generationDirectory,
                File packageFile,
                boolean enabled,
                String source,
                String channel,
                boolean repositoryVerified
        ) {
            this.manifest = manifest;
            this.generationDirectory = generationDirectory;
            this.packageFile = packageFile;
            this.enabled = enabled;
            this.source = source;
            this.channel = channel;
            this.repositoryVerified = repositoryVerified;
        }
    }
}
