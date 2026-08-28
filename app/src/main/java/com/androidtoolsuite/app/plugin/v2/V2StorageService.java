package com.androidtoolsuite.app.plugin.v2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import android.util.Base64;

import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;
import com.androidtoolsuite.runtime.contract.OriginKey;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Host-owned, generation-scoped storage used by Web, worker and provider callers. */
public final class V2StorageService implements AutoCloseable {
    public static final long DEFAULT_QUOTA_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_BLOB_BYTES = 32L * 1024L * 1024L;
    private static final String ACTIVE_GENERATION = "active-generation";
    private static final String INITIAL_GENERATION = "g1";

    private final File root;
    private final Map<String, BlobHandle> handles = new HashMap<>();

    public V2StorageService(Context context) {
        root = new File(context.getApplicationContext().getFilesDir(), "runtime-v2/plugins");
    }

    public synchronized JSONObject kvGet(String pluginId, String key) throws CapabilityFailure {
        validateKey(key);
        try (SQLiteDatabase database = openDatabase(pluginId);
             Cursor cursor = database.query("kv", new String[]{"value_json"}, "key = ?",
                     new String[]{key}, null, null, null, "1")) {
            JSONObject result = new JSONObject();
            if (!cursor.moveToFirst()) return result.put("found", false);
            return result.put("found", true).put("value", new JSONTokener(cursor.getString(0)).nextValue());
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject kvSet(String pluginId, String key, Object value) throws CapabilityFailure {
        validateKey(key);
        try {
            String encoded = encodeJsonValue(value);
            byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 256 * 1024) throw resource("KV value exceeds the RPC item limit");
            try (SQLiteDatabase database = openDatabase(pluginId)) {
                long previous = queryKvSize(database, key);
                ensureQuota(database, generationDirectory(pluginId), bytes.length - previous);
                ContentValues values = new ContentValues();
                values.put("key", key);
                values.put("value_json", encoded);
                values.put("updated_at", System.currentTimeMillis());
                if (database.insertWithOnConflict("kv", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                    throw new IOException("SQLite write failed");
                }
            }
            return new JSONObject().put("stored", true);
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject kvDelete(String pluginId, String key) throws CapabilityFailure {
        validateKey(key);
        try (SQLiteDatabase database = openDatabase(pluginId)) {
            int deleted = database.delete("kv", "key = ?", new String[]{key});
            return new JSONObject().put("deleted", deleted > 0);
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject blobOpenWrite(String pluginId, String sessionId, String blobId)
            throws CapabilityFailure {
        validateBlobId(blobId);
        try {
            File generation = generationDirectory(pluginId);
            File staging = new File(generation, "staging");
            ensureDirectory(staging);
            String handleId = randomHandle();
            File temporary = new File(staging, handleId + ".pending");
            BlobHandle handle = BlobHandle.writer(pluginId, sessionId, blobId, temporary);
            handles.put(handleId, handle);
            return new JSONObject().put("handle", handleId);
        } catch (IOException | JSONException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject blobWrite(String pluginId, String sessionId, String handleId, String encoded)
            throws CapabilityFailure {
        BlobHandle handle = requireHandle(pluginId, sessionId, handleId, true);
        final byte[] bytes;
        try {
            bytes = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw CapabilityFailure.invalid("bytes must be unpadded or padded base64");
        }
        if (handle.size + bytes.length > MAX_BLOB_BYTES) throw resource("Blob exceeds 32 MiB");
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

    public synchronized JSONObject blobOpenRead(String pluginId, String sessionId, String blobId)
            throws CapabilityFailure {
        validateBlobId(blobId);
        try (SQLiteDatabase database = openDatabase(pluginId);
             Cursor cursor = database.query("blobs", new String[]{"file_name", "size", "sha256"},
                     "id = ?", new String[]{blobId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return new JSONObject().put("found", false);
            File file = new File(new File(generationDirectory(pluginId), "blobs"), cursor.getString(0));
            if (!file.isFile()) throw new IOException("Blob index points to a missing file");
            String handleId = randomHandle();
            handles.put(handleId, BlobHandle.reader(pluginId, sessionId, blobId, file));
            return new JSONObject()
                    .put("found", true)
                    .put("handle", handleId)
                    .put("size", cursor.getLong(1))
                    .put("sha256", cursor.getString(2));
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject blobRead(
            String pluginId,
            String sessionId,
            String handleId,
            long offset,
            int maxBytes
    ) throws CapabilityFailure {
        BlobHandle handle = requireHandle(pluginId, sessionId, handleId, false);
        if (offset < 0 || maxBytes < 1 || maxBytes > 192 * 1024) {
            throw CapabilityFailure.invalid("Invalid blob read range");
        }
        try (RandomAccessFile input = new RandomAccessFile(handle.file, "r")) {
            if (offset > input.length()) throw CapabilityFailure.invalid("Blob offset is beyond end of file");
            input.seek(offset);
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

    public synchronized JSONObject blobClose(String pluginId, String sessionId, String handleId)
            throws CapabilityFailure {
        BlobHandle handle = requireHandle(pluginId, sessionId, handleId, null);
        handles.remove(handleId);
        if (!handle.write) {
            try {
                return new JSONObject().put("closed", true);
            } catch (JSONException error) {
                throw internal(error);
            }
        }
        try {
            handle.output.flush();
            handle.output.getFD().sync();
            handle.output.close();
            String digest = hex(handle.digest.digest());
            File generation = generationDirectory(pluginId);
            try (SQLiteDatabase database = openDatabase(pluginId)) {
                long previous = queryBlobSize(database, handle.blobId);
                ensureQuota(database, generation, handle.size - previous);
                File blobs = new File(generation, "blobs");
                ensureDirectory(blobs);
                String fileName = sha256(handle.blobId.getBytes(StandardCharsets.UTF_8)) + ".blob";
                File destination = new File(blobs, fileName);
                File backup = destination.isFile() ? new File(blobs, fileName + ".previous") : null;
                if (backup != null && (!destination.renameTo(backup))) throw new IOException("Cannot stage old blob");
                if (!handle.file.renameTo(destination)) {
                    if (backup != null) backup.renameTo(destination);
                    throw new IOException("Cannot activate blob");
                }
                try {
                    database.beginTransaction();
                    try {
                        ContentValues values = new ContentValues();
                        values.put("id", handle.blobId);
                        values.put("file_name", fileName);
                        values.put("size", handle.size);
                        values.put("sha256", digest);
                        if (database.insertWithOnConflict("blobs", null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                            throw new IOException("Cannot update blob index");
                        }
                        database.setTransactionSuccessful();
                    } finally {
                        database.endTransaction();
                    }
                } catch (IOException | RuntimeException error) {
                    destination.delete();
                    if (backup != null && !backup.renameTo(destination)) {
                        throw new IOException("Cannot restore previous blob after index failure", error);
                    }
                    throw error;
                }
                if (backup != null) backup.delete();
            }
            return new JSONObject().put("closed", true).put("size", handle.size).put("sha256", digest);
        } catch (IOException | JSONException | RuntimeException error) {
            handle.file.delete();
            throw internal(error);
        }
    }

    public synchronized JSONObject blobDelete(String pluginId, String blobId) throws CapabilityFailure {
        validateBlobId(blobId);
        try (SQLiteDatabase database = openDatabase(pluginId);
             Cursor cursor = database.query("blobs", new String[]{"file_name"}, "id = ?",
                     new String[]{blobId}, null, null, null, "1")) {
            if (!cursor.moveToFirst()) return new JSONObject().put("deleted", false);
            File file = new File(new File(generationDirectory(pluginId), "blobs"), cursor.getString(0));
            database.beginTransaction();
            try {
                database.delete("blobs", "id = ?", new String[]{blobId});
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            if (file.exists() && !file.delete()) throw new IOException("Cannot remove blob file");
            return new JSONObject().put("deleted", true);
        } catch (IOException | JSONException | RuntimeException error) {
            throw internal(error);
        }
    }

    public synchronized JSONObject importBlob(
            String pluginId,
            String sessionId,
            String blobId,
            byte[] bytes
    ) throws CapabilityFailure {
        if (bytes == null || bytes.length > MAX_BLOB_BYTES) throw resource("Imported file exceeds 32 MiB");
        JSONObject opened = blobOpenWrite(pluginId, sessionId, blobId);
        try {
            String handle = opened.getString("handle");
            for (int offset = 0; offset < bytes.length; offset += 128 * 1024) {
                int count = Math.min(128 * 1024, bytes.length - offset);
                byte[] chunk = new byte[count];
                System.arraycopy(bytes, offset, chunk, 0, count);
                blobWrite(pluginId, sessionId, handle, Base64.encodeToString(chunk, Base64.NO_WRAP));
            }
            JSONObject result = blobClose(pluginId, sessionId, handle);
            return result.put("id", blobId);
        } catch (JSONException error) {
            throw internal(error);
        }
    }

    public synchronized void closeSession(String sessionId) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, BlobHandle> entry : handles.entrySet()) {
            if (entry.getValue().sessionId.equals(sessionId)) ids.add(entry.getKey());
        }
        for (String id : ids) abortHandle(id, handles.get(id));
    }

    public synchronized void closePluginSessions(String pluginId) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, BlobHandle> entry : handles.entrySet()) {
            if (entry.getValue().pluginId.equals(pluginId)) ids.add(entry.getKey());
        }
        for (String id : ids) abortHandle(id, handles.get(id));
    }

    synchronized boolean hasOpenHandles(String pluginId) {
        for (BlobHandle handle : handles.values()) {
            if (handle.pluginId.equals(pluginId)) return true;
        }
        return false;
    }

    File storageRoot() {
        return root;
    }

    @Override
    public synchronized void close() {
        for (Map.Entry<String, BlobHandle> entry : new ArrayList<>(handles.entrySet())) {
            abortHandle(entry.getKey(), entry.getValue());
        }
    }

    private SQLiteDatabase openDatabase(String pluginId) throws IOException, CapabilityFailure {
        File generation = generationDirectory(pluginId);
        File databaseFile = new File(generation, "kv.sqlite");
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        database.execSQL("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY NOT NULL, value_json TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        database.execSQL("CREATE TABLE IF NOT EXISTS blobs (id TEXT PRIMARY KEY NOT NULL, file_name TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL)");
        return database;
    }

    private File generationDirectory(String pluginId) throws IOException, CapabilityFailure {
        final String origin;
        try {
            origin = OriginKey.fromPluginId(pluginId);
        } catch (com.androidtoolsuite.runtime.contract.ContractException error) {
            throw CapabilityFailure.invalid("Invalid plugin ID");
        }
        File pluginRoot = new File(root, origin);
        ensureDirectory(pluginRoot);
        File pointerFile = new File(pluginRoot, ACTIVE_GENERATION);
        String generation = readPointer(pointerFile);
        if (generation.isEmpty()) {
            generation = INITIAL_GENERATION;
            writePointer(pointerFile, generation);
            writeDescriptor(pluginRoot, pluginId);
        }
        if (!generation.matches("g[1-9][0-9]*")) throw new IOException("Invalid data generation pointer");
        File directory = new File(new File(pluginRoot, "generations"), generation);
        ensureDirectory(directory);
        return directory;
    }

    private static void writeDescriptor(File pluginRoot, String pluginId) throws IOException {
        AtomicFile file = new AtomicFile(new File(pluginRoot, "descriptor.json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(("{\"pluginId\":" + JSONObject.quote(pluginId) + ",\"formatVersion\":1}")
                    .getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    private static String readPointer(File file) throws IOException {
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 64) throw new IOException("Data generation pointer is too large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name()).trim();
        }
    }

    private static void writePointer(File target, String generation) throws IOException {
        AtomicFile file = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(generation.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    private static long queryKvSize(SQLiteDatabase database, String key) {
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(LENGTH(value_json), 0) FROM kv WHERE key = ?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static long queryBlobSize(SQLiteDatabase database, String id) {
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(size, 0) FROM blobs WHERE id = ?", new String[]{id})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static void ensureQuota(SQLiteDatabase database, File generation, long delta) throws CapabilityFailure {
        if (delta <= 0) return;
        long kv;
        long blobs;
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(SUM(LENGTH(value_json)), 0) FROM kv", null)) {
            kv = cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
        try (Cursor cursor = database.rawQuery("SELECT COALESCE(SUM(size), 0) FROM blobs", null)) {
            blobs = cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
        if (kv + blobs + delta > DEFAULT_QUOTA_BYTES) throw resource("Plugin storage quota exceeded");
    }

    private BlobHandle requireHandle(String pluginId, String sessionId, String handleId, Boolean write)
            throws CapabilityFailure {
        BlobHandle handle = handles.get(handleId);
        if (handle == null || !handle.pluginId.equals(pluginId) || !handle.sessionId.equals(sessionId)
                || (write != null && handle.write != write)) {
            throw CapabilityFailure.invalid("Unknown or mismatched blob handle");
        }
        return handle;
    }

    private void abortHandle(String handleId, BlobHandle handle) {
        handles.remove(handleId);
        if (handle == null) return;
        try {
            if (handle.output != null) handle.output.close();
        } catch (IOException ignored) {
        }
        if (handle.write) handle.file.delete();
    }

    private static void validateKey(String key) throws CapabilityFailure {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw CapabilityFailure.invalid("Invalid storage key");
        }
    }

    private static void validateBlobId(String id) throws CapabilityFailure {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw CapabilityFailure.invalid("Invalid blob ID");
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create storage directory");
    }

    private static String randomHandle() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static CapabilityFailure internal(Throwable error) {
        String message = error.getMessage();
        return new CapabilityFailure("INTERNAL", message == null ? "Storage operation failed" : message, true);
    }

    private static String encodeJsonValue(Object value) throws JSONException {
        org.json.JSONArray wrapper = new org.json.JSONArray().put(value);
        String encoded = wrapper.toString();
        if (encoded.length() < 2) throw new JSONException("Cannot encode JSON value");
        return encoded.substring(1, encoded.length() - 1);
    }

    private static CapabilityFailure resource(String message) {
        return new CapabilityFailure("RESOURCE_LIMIT", message, false);
    }

    private static final class BlobHandle {
        final String pluginId;
        final String sessionId;
        final String blobId;
        final File file;
        final boolean write;
        final FileOutputStream output;
        final MessageDigest digest;
        long size;

        private BlobHandle(String pluginId, String sessionId, String blobId, File file, boolean write,
                           FileOutputStream output, MessageDigest digest) {
            this.pluginId = pluginId;
            this.sessionId = sessionId;
            this.blobId = blobId;
            this.file = file;
            this.write = write;
            this.output = output;
            this.digest = digest;
        }

        static BlobHandle writer(String pluginId, String sessionId, String blobId, File file) throws IOException {
            try {
                return new BlobHandle(pluginId, sessionId, blobId, file, true,
                        new FileOutputStream(file), MessageDigest.getInstance("SHA-256"));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IOException(impossible);
            }
        }

        static BlobHandle reader(String pluginId, String sessionId, String blobId, File file) {
            return new BlobHandle(pluginId, sessionId, blobId, file, false, null, null);
        }
    }
}
