package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.androidtoolsuite.app.plugin.v2.BackgroundTaskCall;
import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** WorkManager entry point for provider-task and out-of-process JavaScript worker entries. */
public final class V2RuntimeWorker extends Worker {
    private static final ExecutorService PROVIDER_TASKS = Executors.newCachedThreadPool();

    private volatile V2TaskRunStore activeStore;
    private volatile String activePluginId;
    private volatile String activeTaskId;
    private volatile String activeRunId;

    public V2RuntimeWorker(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        String pluginId = clean(getInputData().getString(V2SchedulerService.KEY_PLUGIN_ID));
        String taskId = clean(getInputData().getString(V2SchedulerService.KEY_TASK_ID));
        String generation = clean(getInputData().getString(V2SchedulerService.KEY_GENERATION));
        String runId = clean(getInputData().getString(V2SchedulerService.KEY_RUN_ID));
        String source = clean(getInputData().getString(V2SchedulerService.KEY_SOURCE));
        boolean scheduled = getInputData().getBoolean(V2SchedulerService.KEY_SCHEDULED, false);
        if (pluginId.isEmpty() || taskId.isEmpty() || generation.isEmpty()) return Result.failure();

        V2RuntimeProcess runtime;
        try {
            runtime = V2RuntimeProcess.get(getApplicationContext());
        } catch (RuntimeException error) {
            return Result.retry();
        }
        V2TaskRunStore store = runtime.taskRuns();
        V2PackageStore.InstalledPlugin installed = runtime.packages().find(pluginId);
        if (installed == null || !installed.enabled
                || !generation.equals(installed.generationDirectory.getName())) {
            failIfKnown(store, pluginId, taskId, runId, "STALE_GENERATION", "Task generation is no longer active", false);
            return Result.failure();
        }
        RuntimePluginManifest.Task task = V2SchedulerService.task(installed.manifest, taskId);
        RuntimePluginManifest.BackgroundEntry entry = task == null
                ? null : V2SchedulerService.backgroundEntry(installed.manifest, task);
        if (task == null || entry == null) {
            failIfKnown(store, pluginId, taskId, runId, "INVALID_TASK", "Task declaration is missing", false);
            return Result.failure();
        }

        try {
            if (scheduled || runId.isEmpty()) {
                runId = store.create(pluginId, generation, taskId, new JSONObject(), source).runId;
            }
            V2TaskRunStore.Run run = store.read(pluginId, taskId, runId);
            if (!generation.equals(run.record.optString("generation", ""))) {
                store.markFailed(pluginId, taskId, runId, getRunAttemptCount() + 1,
                        "STALE_GENERATION", "Task input belongs to another generation", false);
                return terminalResult(scheduled, runId, "failed");
            }
            if ("cancelled".equals(run.record.optString("status", ""))) {
                return terminalResult(scheduled, runId, "cancelled");
            }
            activeStore = store;
            activePluginId = pluginId;
            activeTaskId = taskId;
            activeRunId = runId;

            int maxConcurrency = "parallel".equals(task.concurrencyPolicy) ? task.maxConcurrency : 1;
            V2TaskRunStore.Lease lease = store.acquire(
                    pluginId,
                    taskId,
                    runId,
                    maxConcurrency,
                    Math.max(15 * 60_000L, entry.timeoutMs + 60_000L)
            );
            if (lease == null) {
                if ("forbid".equals(task.concurrencyPolicy)) {
                    store.markCoalesced(pluginId, taskId, runId);
                    emit(runtime, pluginId, taskId, runId, "coalesced");
                    return terminalResult(scheduled, runId, "coalesced");
                }
                int attempt = getRunAttemptCount() + 1;
                if (attempt < task.maxAttempts) {
                    store.markRetrying(pluginId, taskId, runId, attempt, "CONCURRENCY_LIMIT",
                            "Task concurrency limit is busy");
                    emit(runtime, pluginId, taskId, runId, "retrying");
                    return Result.retry();
                }
                store.markFailed(pluginId, taskId, runId, attempt, "CONCURRENCY_LIMIT",
                        "Task concurrency limit remained busy", true);
                emit(runtime, pluginId, taskId, runId, "failed");
                return terminalResult(scheduled, runId, "failed");
            }

            try (V2TaskRunStore.Lease ignored = lease) {
                int attempt = getRunAttemptCount() + 1;
                store.markRunning(pluginId, taskId, runId, attempt);
                emit(runtime, pluginId, taskId, runId, "running");
                JSONObject input = run.record.optJSONObject("input");
                if (input == null) input = new JSONObject();
                try {
                    JSONObject output = execute(runtime, installed, task, entry, input, runId);
                    store.markSucceeded(pluginId, taskId, runId, output);
                    emit(runtime, pluginId, taskId, runId, "succeeded");
                    return terminalResult(scheduled, runId, "succeeded");
                } catch (TaskFailure failure) {
                    if (failure.retryable && attempt < task.maxAttempts && !isStopped()) {
                        store.markRetrying(pluginId, taskId, runId, attempt, failure.code, failure.getMessage());
                        emit(runtime, pluginId, taskId, runId, "retrying");
                        return Result.retry();
                    }
                    store.markFailed(pluginId, taskId, runId, attempt,
                            failure.code, failure.getMessage(), failure.retryable);
                    emit(runtime, pluginId, taskId, runId, "failed");
                    return terminalResult(scheduled, runId, "failed");
                }
            }
        } catch (IOException error) {
            failIfKnown(store, pluginId, taskId, runId, "STORAGE_ERROR", safeMessage(error), true);
            return Result.retry();
        } finally {
            activeStore = null;
            activePluginId = null;
            activeTaskId = null;
            activeRunId = null;
        }
    }

    private JSONObject execute(
            V2RuntimeProcess runtime,
            V2PackageStore.InstalledPlugin installed,
            RuntimePluginManifest.Task task,
            RuntimePluginManifest.BackgroundEntry entry,
            JSONObject input,
            String runId
    ) throws TaskFailure {
        long deadline = System.currentTimeMillis() + entry.timeoutMs;
        if ("provider-task".equals(entry.type)) {
            Future<JSONObject> future = PROVIDER_TASKS.submit(() -> runtime.backgroundTasks().invoke(
                    installed.manifest.plugin.id,
                    entry.entry,
                    new BackgroundTaskCall(installed.manifest.plugin.id, task.id, input, deadline)
            ));
            try {
                return future.get(entry.timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                throw new TaskFailure("TIMEOUT", "Provider task timed out", true);
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new TaskFailure("CANCELLED", "Provider task was interrupted", true);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof CapabilityFailure) {
                    CapabilityFailure failure = (CapabilityFailure) cause;
                    throw new TaskFailure(failure.code, failure.getMessage(), failure.retryable);
                }
                throw new TaskFailure("WORKER_FAILED", safeMessage(cause), true);
            }
        }
        if ("javascript-worker".equals(entry.type)) {
            try {
                return V2JavaScriptWorkerEngine.run(
                        getApplicationContext(),
                        installed,
                        entry,
                        input,
                        runtime.capabilities(),
                        "worker:" + runId
                );
            } catch (V2JavaScriptWorkerEngine.WorkerFailure failure) {
                throw new TaskFailure(failure.code, failure.getMessage(), failure.retryable);
            }
        }
        throw new TaskFailure("NOT_SUPPORTED", "WASM worker runtime is not enabled in this release", false);
    }

    @Override
    public void onStopped() {
        super.onStopped();
        V2TaskRunStore store = activeStore;
        String pluginId = activePluginId;
        String taskId = activeTaskId;
        String runId = activeRunId;
        if (store != null && pluginId != null && taskId != null && runId != null) {
            try {
                store.markFailed(pluginId, taskId, runId, getRunAttemptCount() + 1,
                        "CANCELLED", "WorkManager stopped the task", true);
            } catch (IOException ignored) {
            }
        }
    }

    private static Result terminalResult(boolean scheduled, String runId, String status) {
        Data output = new Data.Builder().putString("runId", runId).putString("status", status).build();
        return scheduled || "succeeded".equals(status) || "coalesced".equals(status)
                ? Result.success(output) : Result.failure(output);
    }

    private static void emit(
            V2RuntimeProcess runtime,
            String pluginId,
            String taskId,
            String runId,
            String status
    ) {
        try {
            runtime.capabilities().emitPluginEvent(
                    pluginId,
                    com.androidtoolsuite.runtime.contract.GeneratedContract.Events.SCHEDULER_RUNCHANGED,
                    new JSONObject()
                            .put("taskId", taskId)
                            .put("runId", runId)
                            .put("status", status)
            );
        } catch (org.json.JSONException ignored) {
        }
    }

    private static void failIfKnown(
            V2TaskRunStore store,
            String pluginId,
            String taskId,
            String runId,
            String code,
            String message,
            boolean retryable
    ) {
        if (runId == null || runId.isEmpty()) return;
        try {
            store.markFailed(pluginId, taskId, runId, 1, code, message, retryable);
        } catch (IOException ignored) {
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static final class TaskFailure extends Exception {
        final String code;
        final boolean retryable;

        TaskFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }
}
