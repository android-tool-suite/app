package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;
import android.util.AtomicFile;

import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.OriginKey;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Durable, bounded task inputs, run history, and cross-worker concurrency leases. */
public final class V2TaskRunStore {
    public static final int MAX_INPUT_BYTES = 128 * 1024;
    public static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_RUNS_PER_TASK = 32;
    private static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final Object FILE_LOCK = new Object();

    private final File root;

    public V2TaskRunStore(Context context) {
        root = new File(context.getApplicationContext().getFilesDir(), "runtime-v2/task-runs");
    }

    public Run create(
            String pluginId,
            String generation,
            String taskId,
            JSONObject input,
            String source
    ) throws IOException {
        JSONObject safeInput = input == null ? new JSONObject() : input;
        byte[] inputBytes = safeInput.toString().getBytes(StandardCharsets.UTF_8);
        if (inputBytes.length > MAX_INPUT_BYTES) throw new IOException("Task input exceeds 128 KiB");
        long now = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        try {
            JSONObject record = new JSONObject()
                    .put("runId", runId)
                    .put("pluginId", pluginId)
                    .put("generation", generation)
                    .put("taskId", taskId)
                    .put("source", clean(source))
                    .put("status", "queued")
                    .put("attempt", 0)
                    .put("createdAt", now)
                    .put("startedAt", JSONObject.NULL)
                    .put("finishedAt", JSONObject.NULL)
                    .put("input", safeInput);
            synchronized (FILE_LOCK) {
                File directory = taskDirectory(pluginId, taskId);
                ensureDirectory(directory);
                writeAtomic(new File(directory, runId + ".json"), record);
                prune(directory);
            }
            return new Run(runId, record);
        } catch (JSONException error) {
            throw new IOException("Cannot create task run", error);
        }
    }

    public Run read(String pluginId, String taskId, String runId) throws IOException {
        requireRunId(runId);
        synchronized (FILE_LOCK) {
            JSONObject record = readRecord(new File(taskDirectory(pluginId, taskId), runId + ".json"));
            return new Run(runId, record);
        }
    }

    public JSONObject lastRun(String pluginId, String taskId) throws IOException {
        synchronized (FILE_LOCK) {
            File directory = taskDirectory(pluginId, taskId);
            File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
            if (files == null || files.length == 0) {
                try {
                    return new JSONObject().put("status", "never");
                } catch (JSONException impossible) {
                    throw new IOException("Cannot create empty task history", impossible);
                }
            }
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            return readRecord(files[0]);
        }
    }

    public void markRunning(String pluginId, String taskId, String runId, int attempt) throws IOException {
        update(pluginId, taskId, runId, record -> {
            record.put("status", "running");
            record.put("attempt", attempt);
            if (record.isNull("startedAt")) record.put("startedAt", System.currentTimeMillis());
            record.remove("error");
        });
    }

    public void markSucceeded(String pluginId, String taskId, String runId, JSONObject output) throws IOException {
        String encoded = output == null ? "{}" : output.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            encoded = "{\"truncated\":true}";
        }
        final JSONObject safeOutput;
        try {
            safeOutput = new JSONObject(encoded);
        } catch (JSONException impossible) {
            throw new IOException("Task output is not an object", impossible);
        }
        update(pluginId, taskId, runId, record -> {
            record.put("status", "succeeded");
            record.put("finishedAt", System.currentTimeMillis());
            record.put("output", safeOutput);
            record.remove("error");
        });
    }

    public void markRetrying(
            String pluginId,
            String taskId,
            String runId,
            int attempt,
            String code,
            String message
    ) throws IOException {
        update(pluginId, taskId, runId, record -> {
            record.put("status", "retrying");
            record.put("attempt", attempt);
            record.put("error", error(code, message, true));
        });
    }

    public void markFailed(
            String pluginId,
            String taskId,
            String runId,
            int attempt,
            String code,
            String message,
            boolean retryable
    ) throws IOException {
        update(pluginId, taskId, runId, record -> {
            record.put("status", "failed");
            record.put("attempt", attempt);
            record.put("finishedAt", System.currentTimeMillis());
            record.put("error", error(code, message, retryable));
        });
    }

    public void markCoalesced(String pluginId, String taskId, String runId) throws IOException {
        update(pluginId, taskId, runId, record -> {
            record.put("status", "coalesced");
            record.put("finishedAt", System.currentTimeMillis());
        });
    }

    public void cancelActive(String pluginId, String taskId) {
        synchronized (FILE_LOCK) {
            try {
                File directory = taskDirectory(pluginId, taskId);
                File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
                if (files == null) return;
                for (File file : files) {
                    JSONObject record = readRecord(file);
                    String status = record.optString("status", "");
                    if (!"queued".equals(status) && !"running".equals(status) && !"retrying".equals(status)) {
                        continue;
                    }
                    record.put("status", "cancelled");
                    record.put("finishedAt", System.currentTimeMillis());
                    writeAtomic(file, record);
                }
            } catch (IOException | JSONException ignored) {
            }
        }
    }

    public Lease acquire(
            String pluginId,
            String taskId,
            String runId,
            int maxConcurrency,
            long staleAfterMillis
    ) throws IOException {
        if (maxConcurrency < 1 || maxConcurrency > 8) throw new IOException("Invalid task concurrency");
        long now = System.currentTimeMillis();
        synchronized (FILE_LOCK) {
            File directory = new File(taskDirectory(pluginId, taskId), ".leases");
            ensureDirectory(directory);
            for (int slot = 0; slot < maxConcurrency; slot++) {
                File file = new File(directory, "slot-" + slot);
                if (file.isFile() && now - file.lastModified() > staleAfterMillis) file.delete();
                if (!file.createNewFile()) continue;
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(runId.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                } catch (IOException error) {
                    file.delete();
                    throw error;
                }
                return new Lease(file, runId);
            }
        }
        return null;
    }

    private void update(String pluginId, String taskId, String runId, Mutator mutator) throws IOException {
        requireRunId(runId);
        synchronized (FILE_LOCK) {
            try {
                File file = new File(taskDirectory(pluginId, taskId), runId + ".json");
                JSONObject record = readRecord(file);
                mutator.apply(record);
                writeAtomic(file, record);
            } catch (JSONException error) {
                throw new IOException("Cannot update task run", error);
            }
        }
    }

    private File taskDirectory(String pluginId, String taskId) throws IOException {
        final String origin;
        try {
            origin = OriginKey.fromPluginId(pluginId);
        } catch (ContractException error) {
            throw new IOException("Invalid plugin ID", error);
        }
        if (taskId == null || !taskId.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            throw new IOException("Invalid task ID");
        }
        return new File(new File(root, origin), taskId);
    }

    private static JSONObject error(String code, String message, boolean retryable) throws JSONException {
        return new JSONObject()
                .put("code", clean(code))
                .put("message", clean(message))
                .put("retryable", retryable);
    }

    private static void requireRunId(String runId) throws IOException {
        if (runId == null || !runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid task run ID");
        }
    }

    private static JSONObject readRecord(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Task run does not exist");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RECORD_BYTES) throw new IOException("Task run record is too large");
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } catch (JSONException error) {
            throw new IOException("Task run record is invalid", error);
        }
    }

    private static void writeAtomic(File file, JSONObject record) throws IOException {
        byte[] bytes = record.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Task run record is too large");
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

    private static void prune(File directory) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null || files.length <= MAX_RUNS_PER_TASK) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int index = MAX_RUNS_PER_TASK; index < files.length; index++) files[index].delete();
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create task run directory");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private interface Mutator {
        void apply(JSONObject value) throws JSONException;
    }

    public static final class Run {
        public final String runId;
        public final JSONObject record;

        Run(String runId, JSONObject record) {
            this.runId = runId;
            this.record = record;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final File file;
        private final String runId;
        private boolean closed;

        Lease(File file, String runId) {
            this.file = file;
            this.runId = runId;
        }

        @Override
        public void close() {
            synchronized (FILE_LOCK) {
                if (closed) return;
                closed = true;
                try {
                    String owner;
                    try (FileInputStream input = new FileInputStream(file);
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[128];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                        owner = output.toString(StandardCharsets.UTF_8.name());
                    }
                    if (runId.equals(owner)) file.delete();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
