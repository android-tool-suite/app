package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;

import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.runtime.contract.ContractLimits;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.OriginKey;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Opaque Dataset payloads with staged whole-generation activation and bounded rollback. */
public final class DatasetService implements AutoCloseable {
    private static final String ACTIVE_GENERATION = "active-generation";
    private static final String INITIAL_GENERATION = "g1";
    private static final String INDEX_FILE = "datasets.json";
    private static final String SECRET_INDEX_FILE = "secrets.json";
    private static final long MAX_GENERATION_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_CHUNK_BYTES = 192 * 1024;

    private final Context context;
    private final PluginPackageStore packages;
    private final StorageService storage;
    private final SecretStore secrets;
    private final File root;
    private final Map<String, PayloadHandle> handles = new HashMap<>();

    public DatasetService(
            Context context,
            PluginPackageStore packages,
            StorageService storage,
            SecretStore secrets
    ) {
        this.context = context.getApplicationContext();
        this.packages = packages;
        this.storage = storage;
        this.secrets = secrets;
        this.root = storage.storageRoot();
    }

    public synchronized JSONObject secretGet(String pluginId, String datasetId, String key)
            throws CapabilityFailure {
        validateKey(key);
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, true);
        try {
            File generation = activeGeneration(pluginId);
            JSONObject index = readIndex(new File(generation, SECRET_INDEX_FILE), "datasets");
            JSONObject datasetIndex = index.getJSONObject("datasets").optJSONObject(dataset.id);
            JSONObject keys = datasetIndex == null ? null : datasetIndex.optJSONObject("keys");
            String relative = keys == null ? "" : keys.optString(key, "");
            if (relative.isEmpty()) return new JSONObject().put("found", false);
            File file = safeRelative(generation, relative, "secrets/");
            byte[] ciphertext = readBounded(file, dataset.maxBytes + 1024L);
            byte[] plaintext = secrets.decrypt(pluginId, dataset.id, key, dataset.formatVersion, ciphertext);
            Object value = new JSONTokener(new String(plaintext, StandardCharsets.UTF_8)).nextValue();
            return new JSONObject().put("found", true).put("value", value);
        } catch (CapabilityFailure failure) {
            throw failure;
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject secretSet(String pluginId, String datasetId, String key, Object value)
            throws CapabilityFailure {
        validateKey(key);
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, true);
        try {
            byte[] plaintext = encodeJsonValue(value).getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > Math.min(dataset.maxBytes, ContractLimits.MAX_RPC_BYTES)) {
                throw resource("Secret value exceeds the declared limit");
            }
            byte[] ciphertext = secrets.encrypt(pluginId, dataset.id, key, dataset.formatVersion, plaintext);
            File generation = activeGeneration(pluginId);
            File directory = new File(generation, "secrets");
            ensureDirectory(directory);
            String relative = "secrets/" + sha256((dataset.id + "\n" + key).getBytes(StandardCharsets.UTF_8))
                    + ".secret";
            File destination = safeRelative(generation, relative, "secrets/");
            File backup = destination.isFile() ? new File(directory, destination.getName() + ".previous") : null;
            File temporary = new File(directory, destination.getName() + ".pending-" + UUID.randomUUID());
            writeFile(temporary, ciphertext);
            JSONObject index = readIndex(new File(generation, SECRET_INDEX_FILE), "datasets");
            JSONObject datasets = index.getJSONObject("datasets");
            JSONObject datasetIndex = datasets.optJSONObject(dataset.id);
            if (datasetIndex == null) {
                datasetIndex = new JSONObject()
                        .put("formatVersion", dataset.formatVersion)
                        .put("keys", new JSONObject());
                datasets.put(dataset.id, datasetIndex);
            }
            datasetIndex.getJSONObject("keys").put(key, relative);
            if (backup != null && !destination.renameTo(backup)) {
                temporary.delete();
                throw new IOException("Cannot stage previous secret");
            }
            if (!temporary.renameTo(destination)) {
                if (backup != null) backup.renameTo(destination);
                throw new IOException("Cannot activate secret");
            }
            try {
                ensureGenerationQuota(generation);
                writeJsonAtomic(new File(generation, SECRET_INDEX_FILE), index);
            } catch (IOException error) {
                destination.delete();
                if (backup != null) backup.renameTo(destination);
                throw error;
            }
            if (backup != null) backup.delete();
            return new JSONObject().put("stored", true);
        } catch (CapabilityFailure failure) {
            throw failure;
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject secretDelete(String pluginId, String datasetId, String key)
            throws CapabilityFailure {
        validateKey(key);
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, true);
        try {
            File generation = activeGeneration(pluginId);
            JSONObject index = readIndex(new File(generation, SECRET_INDEX_FILE), "datasets");
            JSONObject datasetIndex = index.getJSONObject("datasets").optJSONObject(dataset.id);
            JSONObject keys = datasetIndex == null ? null : datasetIndex.optJSONObject("keys");
            String relative = keys == null ? "" : keys.optString(key, "");
            if (relative.isEmpty()) return new JSONObject().put("deleted", false);
            File file = safeRelative(generation, relative, "secrets/");
            File backup = new File(file.getParentFile(), file.getName() + ".delete-" + UUID.randomUUID());
            if (file.exists() && !file.renameTo(backup)) throw new IOException("Cannot stage secret deletion");
            keys.remove(key);
            if (keys.length() == 0) index.getJSONObject("datasets").remove(dataset.id);
            try {
                writeJsonAtomic(new File(generation, SECRET_INDEX_FILE), index);
            } catch (IOException error) {
                if (backup.exists() && !backup.renameTo(file)) {
                    throw new IOException("Cannot restore secret after index failure", error);
                }
                throw error;
            }
            backup.delete();
            return new JSONObject().put("deleted", true);
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject openRead(String pluginId, String sessionId, String datasetId)
            throws CapabilityFailure {
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, false);
        try {
            File generation = activeGeneration(pluginId);
            JSONObject index = readIndex(new File(generation, INDEX_FILE), "items");
            JSONObject item = index.getJSONObject("items").optJSONObject(dataset.id);
            if (item == null) return new JSONObject().put("found", false);
            if (item.optInt("formatVersion", -1) != dataset.formatVersion) {
                throw new IOException("Stored Dataset format is not readable by this plugin generation");
            }
            String relative = item.optString("file", "");
            File stored = safeRelative(generation, relative, "datasets/");
            File readable = stored;
            boolean temporary = false;
            if (item.optBoolean("encrypted", false)) {
                byte[] ciphertext = readBounded(stored, (long) dataset.maxBytes + 1024L);
                byte[] plaintext = secrets.decrypt(
                        pluginId, dataset.id, "dataset-payload", dataset.formatVersion, ciphertext
                );
                File cache = new File(context.getCacheDir(), "plugin-runtime-dataset-read");
                ensureDirectory(cache);
                readable = new File(cache, UUID.randomUUID() + ".payload");
                writeFile(readable, plaintext);
                temporary = true;
            }
            long size = readable.length();
            String digest = sha256File(readable, dataset.maxBytes);
            if (size != item.optLong("size", -1L) || !digest.equals(item.optString("sha256", ""))) {
                if (temporary) readable.delete();
                throw new IOException("Stored Dataset failed size or digest validation");
            }
            String handleId = randomHandle();
            handles.put(handleId, PayloadHandle.reader(
                    pluginId, sessionId, dataset.id, readable, temporary, dataset.maxBytes
            ));
            return new JSONObject()
                    .put("found", true)
                    .put("handle", handleId)
                    .put("size", size)
                    .put("sha256", digest)
                    .put("formatVersion", dataset.formatVersion)
                    .put("mediaType", dataset.mediaType)
                    .put("sensitive", dataset.sensitive);
        } catch (CapabilityFailure failure) {
            throw failure;
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject openWrite(String pluginId, String sessionId, String datasetId)
            throws CapabilityFailure {
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, false);
        try {
            File directory = new File(pluginRoot(pluginId), "staging");
            ensureDirectory(directory);
            String handleId = randomHandle();
            File file = new File(directory, "dataset-" + handleId + ".pending");
            handles.put(handleId, PayloadHandle.writer(
                    pluginId, sessionId, dataset.id, file, dataset.maxBytes
            ));
            return new JSONObject()
                    .put("handle", handleId)
                    .put("formatVersion", dataset.formatVersion)
                    .put("maxBytes", dataset.maxBytes);
        } catch (IOException | JSONException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject read(
            String pluginId,
            String sessionId,
            String handleId,
            long offset,
            int maxBytes
    ) throws CapabilityFailure {
        PayloadHandle handle = requireHandle(pluginId, sessionId, handleId, false);
        if (offset < 0 || maxBytes < 1 || maxBytes > MAX_CHUNK_BYTES) {
            throw CapabilityFailure.invalid("Invalid Dataset read range");
        }
        try (RandomAccessFile input = new RandomAccessFile(handle.file, "r")) {
            if (offset > input.length()) throw CapabilityFailure.invalid("Dataset offset is beyond end of file");
            int count = (int) Math.min((long) maxBytes, input.length() - offset);
            byte[] bytes = new byte[count];
            input.readFully(bytes);
            return new JSONObject()
                    .put("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .put("offset", offset)
                    .put("eof", offset + count >= input.length());
        } catch (IOException | JSONException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject write(String pluginId, String sessionId, String handleId, String encoded)
            throws CapabilityFailure {
        PayloadHandle handle = requireHandle(pluginId, sessionId, handleId, true);
        final byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw CapabilityFailure.invalid("bytes must be base64");
        }
        if (bytes.length > MAX_CHUNK_BYTES || handle.size + bytes.length > handle.maxBytes) {
            throw resource("Dataset exceeds its declared maxBytes");
        }
        try {
            handle.output.write(bytes);
            handle.digest.update(bytes);
            handle.size += bytes.length;
            return new JSONObject().put("written", bytes.length).put("size", handle.size);
        } catch (IOException | JSONException error) {
            abortHandle(handleId, handle);
            throw internal(error);
        }
    }

    public synchronized JSONObject commit(String pluginId, String sessionId, String handleId)
            throws CapabilityFailure {
        PayloadHandle handle = requireHandle(pluginId, sessionId, handleId, true);
        handles.remove(handleId);
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, handle.datasetId, false);
        try {
            handle.output.flush();
            handle.output.getFD().sync();
            handle.output.close();
            validatePayload(handle.file, dataset);
            String digest = hex(handle.digest.digest());
            String generation = activateDataset(pluginId, dataset, handle.file, digest);
            return new JSONObject()
                    .put("committed", true)
                    .put("generation", generation)
                    .put("size", handle.size)
                    .put("sha256", digest);
        } catch (CapabilityFailure failure) {
            handle.file.delete();
            throw failure;
        } catch (IOException | JSONException | RuntimeException error) {
            handle.file.delete();
            throw internal(error);
        }
    }

    public synchronized JSONObject abort(String pluginId, String sessionId, String handleId)
            throws CapabilityFailure {
        PayloadHandle handle = requireHandle(pluginId, sessionId, handleId, null);
        abortHandle(handleId, handle);
        try {
            return new JSONObject().put("aborted", true);
        } catch (JSONException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject delete(String pluginId, String datasetId) throws CapabilityFailure {
        RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, false);
        try {
            String generation = activateDataset(pluginId, dataset, null, "");
            return new JSONObject().put("deleted", true).put("generation", generation);
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized boolean hasDataset(String pluginId, String datasetId) throws IOException {
        try {
            RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, false);
            JSONObject index = readIndex(new File(activeGeneration(pluginId), INDEX_FILE), "items");
            return index.getJSONObject("items").has(dataset.id);
        } catch (CapabilityFailure | JSONException error) {
            throw new IOException(safeMessage(error), error);
        }
    }

    public synchronized long datasetSize(String pluginId, String datasetId) throws IOException {
        try {
            RuntimePluginManifest.Dataset dataset = requireDataset(pluginId, datasetId, false);
            JSONObject index = readIndex(new File(activeGeneration(pluginId), INDEX_FILE), "items");
            JSONObject item = index.getJSONObject("items").optJSONObject(dataset.id);
            return item == null ? 0L : Math.max(0L, item.optLong("size", 0L));
        } catch (CapabilityFailure | JSONException error) {
            throw new IOException(safeMessage(error), error);
        }
    }

    public synchronized void exportDataset(String pluginId, String datasetId, OutputStream output)
            throws IOException {
        String session = "export:" + UUID.randomUUID();
        String handle = null;
        try {
            JSONObject opened = openRead(pluginId, session, datasetId);
            if (!opened.optBoolean("found", false)) throw new IOException("Dataset does not exist");
            handle = opened.getString("handle");
            PayloadHandle payload = requireHandle(pluginId, session, handle, false);
            try (FileInputStream input = new FileInputStream(payload.file)) {
                copyBounded(input, output, payload.maxBytes);
            }
        } catch (CapabilityFailure | JSONException error) {
            throw new IOException(safeMessage(error), error);
        } finally {
            if (handle != null) {
                PayloadHandle value = handles.get(handle);
                if (value != null) abortHandle(handle, value);
            }
        }
    }

    public synchronized void importDataset(
            String pluginId,
            String datasetId,
            String restoreMode,
            InputStream input
    ) throws IOException {
        RuntimePluginManifest.Dataset dataset;
        try {
            dataset = requireDataset(pluginId, datasetId, false);
        } catch (CapabilityFailure error) {
            throw new IOException(error.getMessage(), error);
        }
        if (!dataset.restoreModes.contains(restoreMode)) throw new IOException("Dataset restore mode is unsupported");
        if ("skip".equals(restoreMode) && hasDataset(pluginId, datasetId)) return;
        if (!"replace".equals(restoreMode)) {
            throw new IOException("Opaque 插件运行时 Dataset import currently requires replace mode");
        }
        String session = "import:" + UUID.randomUUID();
        String handle = null;
        try {
            handle = openWrite(pluginId, session, datasetId).getString("handle");
            PayloadHandle payload = requireHandle(pluginId, session, handle, true);
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (payload.size + read > payload.maxBytes) throw new IOException("Dataset exceeds maxBytes");
                payload.output.write(buffer, 0, read);
                payload.digest.update(buffer, 0, read);
                payload.size += read;
            }
            commit(pluginId, session, handle);
            handle = null;
        } catch (CapabilityFailure | JSONException error) {
            throw new IOException(safeMessage(error), error);
        } finally {
            if (handle != null) {
                PayloadHandle value = handles.get(handle);
                if (value != null) abortHandle(handle, value);
            }
        }
    }

    public synchronized void closeSession(String sessionId) {
        List<String> matching = new ArrayList<>();
        for (Map.Entry<String, PayloadHandle> entry : handles.entrySet()) {
            if (entry.getValue().sessionId.equals(sessionId)) matching.add(entry.getKey());
        }
        for (String handle : matching) abortHandle(handle, handles.get(handle));
    }

    public synchronized void closePluginSessions(String pluginId) {
        List<String> matching = new ArrayList<>();
        for (Map.Entry<String, PayloadHandle> entry : handles.entrySet()) {
            if (entry.getValue().pluginId.equals(pluginId)) matching.add(entry.getKey());
        }
        for (String handleId : matching) abortHandle(handleId, handles.get(handleId));
    }

    @Override
    public synchronized void close() {
        for (Map.Entry<String, PayloadHandle> entry : new ArrayList<>(handles.entrySet())) {
            abortHandle(entry.getKey(), entry.getValue());
        }
    }

    private String activateDataset(
            String pluginId,
            RuntimePluginManifest.Dataset dataset,
            File source,
            String digest
    ) throws IOException, CapabilityFailure, JSONException {
        synchronized (storage) {
            if (storage.hasOpenHandles(pluginId) || hasOpenPayloadHandles(pluginId)) {
                throw new CapabilityFailure("CONFLICT", "Close all storage handles before switching Dataset generation", true);
            }
            File pluginRoot = pluginRoot(pluginId);
            File previous = activeGeneration(pluginId);
            File stagingParent = new File(pluginRoot, "staging");
            ensureDirectory(stagingParent);
            File staging = new File(stagingParent, "generation-" + UUID.randomUUID());
            ensureDirectory(staging);
            try {
                copyDirectory(previous, staging);
                deleteRecursively(new File(staging, "staging"));
                JSONObject index = readIndex(new File(staging, INDEX_FILE), "items");
                JSONObject items = index.getJSONObject("items");
                JSONObject old = items.optJSONObject(dataset.id);
                if (old != null) {
                    String oldFile = old.optString("file", "");
                    if (!oldFile.isEmpty()) safeRelative(staging, oldFile, "datasets/").delete();
                }
                if (source == null) {
                    for (RuntimePluginManifest.Dataset candidate : requireInstalledManifest(pluginId).datasets) {
                        if (candidate.dependsOn.contains(dataset.id) && items.has(candidate.id)) {
                            throw new CapabilityFailure(
                                    "CONFLICT",
                                    "Dataset is still required by " + candidate.id,
                                    false
                            );
                        }
                    }
                    items.remove(dataset.id);
                } else {
                    for (String dependency : dataset.dependsOn) {
                        if (!items.has(dependency)) {
                            throw new CapabilityFailure(
                                    "CONFLICT",
                                    "Dataset dependency is missing: " + dependency,
                                    false
                            );
                        }
                    }
                    File datasetsDirectory = new File(staging, "datasets");
                    ensureDirectory(datasetsDirectory);
                    String extension = dataset.sensitive ? ".secret" : ".payload";
                    String relative = "datasets/" + sha256(dataset.id.getBytes(StandardCharsets.UTF_8)) + extension;
                    File destination = safeRelative(staging, relative, "datasets/");
                    if (dataset.sensitive) {
                        byte[] plaintext = readBounded(source, dataset.maxBytes);
                        writeFile(destination, secrets.encrypt(
                                pluginId, dataset.id, "dataset-payload", dataset.formatVersion, plaintext
                        ));
                    } else {
                        copyFile(source, destination, dataset.maxBytes);
                    }
                    items.put(dataset.id, new JSONObject()
                            .put("formatVersion", dataset.formatVersion)
                            .put("mediaType", dataset.mediaType)
                            .put("validator", dataset.validator)
                            .put("sensitive", dataset.sensitive)
                            .put("encrypted", dataset.sensitive)
                            .put("size", source.length())
                            .put("sha256", digest)
                            .put("file", relative)
                            .put("updatedAt", System.currentTimeMillis()));
                }
                writeJsonAtomic(new File(staging, INDEX_FILE), index);
                ensureGenerationQuota(staging);
                File generations = new File(pluginRoot, "generations");
                ensureDirectory(generations);
                String generationName = nextGenerationName(generations);
                File generation = new File(generations, generationName);
                if (!staging.renameTo(generation)) throw new IOException("Cannot activate staged Dataset generation");
                try {
                    writePointer(new File(pluginRoot, ACTIVE_GENERATION), generationName);
                } catch (IOException error) {
                    deleteRecursively(generation);
                    throw error;
                }
                pruneGenerations(generations, generationName, previous.getName());
                if (source != null) source.delete();
                return generationName;
            } catch (IOException | CapabilityFailure | JSONException | RuntimeException error) {
                deleteRecursively(staging);
                throw error;
            }
        }
    }

    private RuntimePluginManifest.Dataset requireDataset(String pluginId, String datasetId, boolean secretOnly)
            throws CapabilityFailure {
        RuntimePluginManifest manifest = requireInstalledManifest(pluginId);
        for (RuntimePluginManifest.Dataset dataset : manifest.datasets) {
            if (!dataset.id.equals(datasetId)) continue;
            if (secretOnly && !(dataset.sensitive || "secret".equals(dataset.category))) {
                throw new CapabilityFailure("CAPABILITY_UNDECLARED", "Dataset is not declared sensitive", false);
            }
            return dataset;
        }
        throw new CapabilityFailure("CAPABILITY_UNDECLARED", "Dataset is not declared by this plugin", false);
    }

    private RuntimePluginManifest requireInstalledManifest(String pluginId) throws CapabilityFailure {
        PluginPackageStore.InstalledPlugin installed = packages.find(pluginId);
        if (installed == null) {
            throw CapabilityFailure.unavailable("Plugin generation is not installed", false);
        }
        return installed.manifest;
    }

    private File pluginRoot(String pluginId) throws CapabilityFailure {
        try {
            return new File(root, OriginKey.fromPluginId(pluginId));
        } catch (ContractException error) {
            throw CapabilityFailure.invalid("Invalid plugin ID");
        }
    }

    private File activeGeneration(String pluginId) throws IOException, CapabilityFailure {
        File pluginRoot = pluginRoot(pluginId);
        ensureDirectory(pluginRoot);
        File pointer = new File(pluginRoot, ACTIVE_GENERATION);
        String generation = readPointer(pointer);
        if (generation.isEmpty()) {
            generation = INITIAL_GENERATION;
            File directory = new File(new File(pluginRoot, "generations"), generation);
            ensureDirectory(directory);
            writePointer(pointer, generation);
        }
        if (!generation.matches("g[1-9][0-9]*")) throw new IOException("Invalid data generation pointer");
        File directory = new File(new File(pluginRoot, "generations"), generation);
        ensureDirectory(directory);
        return directory;
    }

    private boolean hasOpenPayloadHandles(String pluginId) {
        for (PayloadHandle handle : handles.values()) if (handle.pluginId.equals(pluginId)) return true;
        return false;
    }

    private PayloadHandle requireHandle(String pluginId, String sessionId, String id, Boolean write)
            throws CapabilityFailure {
        PayloadHandle handle = handles.get(id);
        if (handle == null || !handle.pluginId.equals(pluginId) || !handle.sessionId.equals(sessionId)
                || (write != null && handle.write != write)) {
            throw CapabilityFailure.invalid("Unknown or mismatched Dataset handle");
        }
        return handle;
    }

    private void abortHandle(String id, PayloadHandle handle) {
        handles.remove(id);
        if (handle == null) return;
        try {
            if (handle.output != null) handle.output.close();
        } catch (IOException ignored) {
        }
        if (handle.write || handle.deleteOnClose) handle.file.delete();
    }

    private static void validatePayload(File file, RuntimePluginManifest.Dataset dataset)
            throws IOException, CapabilityFailure {
        if (!file.isFile() || file.length() > dataset.maxBytes) throw resource("Dataset exceeds maxBytes");
        if ("json".equals(dataset.validator)) {
            byte[] bytes = readBounded(file, dataset.maxBytes);
            try {
                Object value = new JSONTokener(new String(bytes, StandardCharsets.UTF_8)).nextValue();
                if (!(value instanceof JSONObject)) throw new JSONException("root must be an object");
                if (((JSONObject) value).optInt("formatVersion", -1) != dataset.formatVersion) {
                    throw new JSONException("formatVersion does not match manifest");
                }
            } catch (JSONException error) {
                throw new IOException("Dataset JSON validation failed: " + safeMessage(error), error);
            }
        } else if ("zip".equals(dataset.validator)) {
            validateZip(file, dataset.maxBytes);
        }
    }

    private static void validateZip(File file, long maxBytes) throws IOException {
        Set<String> paths = new HashSet<>();
        long expanded = 0L;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > ContractLimits.MAX_PACKAGE_ENTRIES) throw new IOException("Dataset ZIP has too many entries");
                String path = entry.getName().replace('\\', '/');
                if (path.isEmpty() || path.startsWith("/") || path.contains(":") || !paths.add(path)) {
                    throw new IOException("Dataset ZIP contains an invalid or duplicate path");
                }
                for (String segment : path.split("/")) {
                    if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                        throw new IOException("Dataset ZIP contains path traversal");
                    }
                }
                int read;
                while ((read = input.read(buffer)) != -1) {
                    expanded += read;
                    if (expanded > Math.min(MAX_GENERATION_BYTES, Math.max(maxBytes, maxBytes * 8L))) {
                        throw new IOException("Dataset ZIP expands beyond its limit");
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static JSONObject readIndex(File file, String collection) throws IOException, JSONException {
        if (!file.isFile()) return new JSONObject().put("formatVersion", 1).put(collection, new JSONObject());
        byte[] bytes = readBounded(file, ContractLimits.MAX_MANIFEST_BYTES);
        JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (value.optInt("formatVersion", -1) != 1 || value.optJSONObject(collection) == null) {
            throw new IOException("插件运行时 data index is invalid");
        }
        return value;
    }

    private static File safeRelative(File root, String relative, String requiredPrefix) throws IOException {
        if (relative == null || !relative.startsWith(requiredPrefix)
                || !relative.matches("[A-Za-z0-9._/-]+") || relative.contains("..")) {
            throw new IOException("Data index contains an unsafe path");
        }
        File canonicalRoot = root.getCanonicalFile();
        File target = new File(canonicalRoot, relative).getCanonicalFile();
        if (!target.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("Data index path escapes generation");
        }
        return target;
    }

    private static String nextGenerationName(File generations) {
        int maximum = 0;
        File[] children = generations.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (!child.getName().matches("g[1-9][0-9]*")) continue;
                try {
                    maximum = Math.max(maximum, Integer.parseInt(child.getName().substring(1)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return "g" + (maximum + 1);
    }

    private static void pruneGenerations(File generations, String active, String previous) {
        File[] children = generations.listFiles(File::isDirectory);
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().equals(active) && !child.getName().equals(previous)) deleteRecursively(child);
        }
    }

    private static void copyDirectory(File source, File destination) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                ensureDirectory(target);
                copyDirectory(child, target);
            } else {
                copyFile(child, target, MAX_GENERATION_BYTES);
            }
        }
    }

    private static void copyFile(File source, File destination, long limit) throws IOException {
        ensureDirectory(destination.getParentFile());
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            copyBounded(input, output, limit);
            output.getFD().sync();
        }
    }

    private static void copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Data exceeds size limit");
            output.write(buffer, 0, read);
        }
    }

    private static byte[] readBounded(File file, long limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyBounded(input, output, limit);
            return output.toByteArray();
        }
    }

    private static String sha256File(File file, long limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IOException("Data exceeds size limit");
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static void ensureGenerationQuota(File generation) throws IOException, CapabilityFailure {
        long size = directorySize(generation, MAX_GENERATION_BYTES + 1L);
        if (size > MAX_GENERATION_BYTES) throw resource("插件运行时 generation exceeds 512 MiB host limit");
    }

    private static long directorySize(File file, long stopAfter) {
        if (file == null || !file.exists()) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child, stopAfter - total);
                if (total >= stopAfter) break;
            }
        }
        return total;
    }

    private static String readPointer(File file) throws IOException {
        if (!file.isFile()) return "";
        return new String(readBounded(file, 64), StandardCharsets.UTF_8).trim();
    }

    private static void writePointer(File file, String generation) throws IOException {
        if (!generation.matches("g[1-9][0-9]*")) throw new IOException("Invalid generation name");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(generation.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    private static void writeJsonAtomic(File file, JSONObject value) throws IOException {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > ContractLimits.MAX_MANIFEST_BYTES) throw new IOException("Data index is too large");
        ensureDirectory(file.getParentFile());
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(bytes);
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    private static void writeFile(File file, byte[] bytes) throws IOException {
        ensureDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create 插件运行时 data directory");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static void validateKey(String key) throws CapabilityFailure {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw CapabilityFailure.invalid("Invalid secret key");
        }
    }

    private static String encodeJsonValue(Object value) throws JSONException {
        org.json.JSONArray wrapper = new org.json.JSONArray().put(value);
        String encoded = wrapper.toString();
        if (encoded.length() < 2) throw new JSONException("Cannot encode JSON value");
        return encoded.substring(1, encoded.length() - 1);
    }

    private static String randomHandle() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static CapabilityFailure resource(String message) {
        return new CapabilityFailure("RESOURCE_LIMIT", message, false);
    }

    private static CapabilityFailure internal(Throwable error) {
        return new CapabilityFailure("INTERNAL", safeMessage(error), true);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class PayloadHandle {
        final String pluginId;
        final String sessionId;
        final String datasetId;
        final File file;
        final boolean write;
        final boolean deleteOnClose;
        final long maxBytes;
        final FileOutputStream output;
        final MessageDigest digest;
        long size;

        private PayloadHandle(
                String pluginId,
                String sessionId,
                String datasetId,
                File file,
                boolean write,
                boolean deleteOnClose,
                long maxBytes,
                FileOutputStream output,
                MessageDigest digest
        ) {
            this.pluginId = pluginId;
            this.sessionId = sessionId;
            this.datasetId = datasetId;
            this.file = file;
            this.write = write;
            this.deleteOnClose = deleteOnClose;
            this.maxBytes = maxBytes;
            this.output = output;
            this.digest = digest;
        }

        static PayloadHandle reader(
                String pluginId,
                String sessionId,
                String datasetId,
                File file,
                boolean deleteOnClose,
                long maxBytes
        ) {
            return new PayloadHandle(
                    pluginId, sessionId, datasetId, file, false, deleteOnClose,
                    maxBytes, null, null
            );
        }

        static PayloadHandle writer(
                String pluginId,
                String sessionId,
                String datasetId,
                File file,
                long maxBytes
        ) throws IOException {
            try {
                return new PayloadHandle(
                        pluginId, sessionId, datasetId, file, true, true, maxBytes,
                        new FileOutputStream(file), MessageDigest.getInstance("SHA-256")
                );
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException(impossible);
            }
        }
    }
}
