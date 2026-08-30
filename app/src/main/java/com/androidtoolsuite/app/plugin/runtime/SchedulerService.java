package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.androidtoolsuite.app.plugin.runtime.CapabilityCall;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.CapabilityProvider;
import com.androidtoolsuite.runtime.contract.ContractException;
import com.androidtoolsuite.runtime.contract.GeneratedContract;
import com.androidtoolsuite.runtime.contract.OriginKey;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manifest-bound WorkManager scheduler and the host implementation of capability scheduler@1. */
public final class SchedulerService implements CapabilityProvider {
    static final String KEY_PLUGIN_ID = "pluginId";
    static final String KEY_TASK_ID = "taskId";
    static final String KEY_GENERATION = "generation";
    static final String KEY_RUN_ID = "runId";
    static final String KEY_SOURCE = "source";
    static final String KEY_SCHEDULED = "scheduled";

    private static final String PREFS_NAME = "runtime_v2_scheduler";
    private static final String PREF_REGISTERED = "registered_tasks";
    private static final long CONDITIONAL_POLL_MINUTES = 15L;

    private final Context context;
    private final PluginPackageStore packages;
    private final TaskRunStore runs;
    private final CapabilityRouter events;
    private final WorkManager workManager;
    private final SharedPreferences preferences;
    private final ExecutorService maintenance = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processSynced = new AtomicBoolean();

    public SchedulerService(
            Context context,
            PluginPackageStore packages,
            TaskRunStore runs,
            CapabilityRouter events
    ) {
        this.context = context.getApplicationContext();
        this.packages = packages;
        this.runs = runs;
        this.events = events;
        this.events.subscribeCapabilityEvents(this::onProviderEvent);
        this.workManager = WorkManager.getInstance(this.context);
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public String capabilityId() {
        return GeneratedContract.Capabilities.SCHEDULER;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public Set<String> methods() {
        return Set.of(
                GeneratedContract.Methods.SCHEDULER_REGISTER,
                GeneratedContract.Methods.SCHEDULER_RUNNOW,
                GeneratedContract.Methods.SCHEDULER_CANCEL,
                GeneratedContract.Methods.SCHEDULER_LASTRUN
        );
    }

    @Override
    public JSONObject call(CapabilityCall call) throws CapabilityFailure {
        PluginPackageStore.InstalledPlugin installed = requireEnabled(call.pluginId);
        String taskId = requiredTaskId(call.payload);
        RuntimePluginManifest.Task task = requireTask(installed.manifest, taskId);
        try {
            switch (call.method) {
                case GeneratedContract.Methods.SCHEDULER_REGISTER:
                    int scheduled = scheduleTask(installed, task);
                    return new JSONObject()
                            .put("taskId", taskId)
                            .put("scheduledTriggers", scheduled)
                            .put("generation", installed.generationDirectory.getName());
                case GeneratedContract.Methods.SCHEDULER_RUNNOW:
                    JSONObject input = optionalObject(call.payload, "input");
                    return enqueueNow(installed, task, input, "manual");
                case GeneratedContract.Methods.SCHEDULER_CANCEL:
                    cancelTask(call.pluginId, taskId);
                    return new JSONObject().put("taskId", taskId).put("cancelled", true);
                case GeneratedContract.Methods.SCHEDULER_LASTRUN:
                    return runs.lastRun(call.pluginId, taskId);
                default:
                    throw CapabilityFailure.invalid("Unsupported scheduler method");
            }
        } catch (IOException | JSONException error) {
            throw new CapabilityFailure("SCHEDULER_ERROR", safeMessage(error), true);
        }
    }

    public synchronized void syncEnabledPlugins() {
        LinkedHashSet<String> desired = new LinkedHashSet<>();
        for (PluginPackageStore.InstalledPlugin installed : packages.load()) {
            if (!installed.enabled) continue;
            workManager.cancelAllWorkByTag(pluginTag(installed.manifest.plugin.id));
            if (!events.isPermissionGranted(installed.manifest, "scheduler")) continue;
            for (RuntimePluginManifest.Task task : installed.manifest.tasks) {
                String key = taskKey(installed.manifest.plugin.id, task.id);
                desired.add(key);
                scheduleTask(installed, task);
            }
        }
        Set<String> previous = preferences.getStringSet(PREF_REGISTERED, Collections.emptySet());
        for (String stale : previous) {
            if (!desired.contains(stale)) workManager.cancelAllWorkByTag(taskTagFromKey(stale));
        }
        preferences.edit().putStringSet(PREF_REGISTERED, desired).apply();
    }

    public void syncEnabledPluginsAsync() {
        maintenance.execute(this::syncEnabledPlugins);
    }

    public void syncPlugin(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = packages.find(pluginId);
        if (installed == null || !installed.enabled) {
            cancelPlugin(pluginId);
            return;
        }
        workManager.cancelAllWorkByTag(pluginTag(pluginId));
        for (RuntimePluginManifest.Task task : installed.manifest.tasks) runs.cancelActive(pluginId, task.id);
        if (!events.isPermissionGranted(installed.manifest, "scheduler")) {
            LinkedHashSet<String> registered = new LinkedHashSet<>(
                    preferences.getStringSet(PREF_REGISTERED, Collections.emptySet())
            );
            registered.removeIf(value -> value.startsWith(pluginId + "#"));
            preferences.edit().putStringSet(PREF_REGISTERED, registered).apply();
            return;
        }
        for (RuntimePluginManifest.Task task : installed.manifest.tasks) scheduleTask(installed, task);
        LinkedHashSet<String> registered = new LinkedHashSet<>(
                preferences.getStringSet(PREF_REGISTERED, Collections.emptySet())
        );
        registered.removeIf(value -> value.startsWith(pluginId + "#"));
        for (RuntimePluginManifest.Task task : installed.manifest.tasks) {
            registered.add(taskKey(pluginId, task.id));
        }
        preferences.edit().putStringSet(PREF_REGISTERED, registered).apply();
    }

    public void syncPluginAsync(String pluginId) {
        maintenance.execute(() -> syncPlugin(pluginId));
    }

    public void onAppForeground() {
        for (PluginPackageStore.InstalledPlugin installed : packages.load()) {
            if (!installed.enabled) continue;
            if (!events.isPermissionGranted(installed.manifest, "scheduler")) continue;
            for (RuntimePluginManifest.Task task : installed.manifest.tasks) {
                if (hasTrigger(task, "app-foreground")) {
                    try {
                        enqueueNow(installed, task, new JSONObject(), "app-foreground");
                    } catch (CapabilityFailure ignored) {
                    }
                }
            }
        }
    }

    public void onAppForegroundAsync() {
        maintenance.execute(this::onAppForeground);
    }

    public void onHostStartedAsync() {
        maintenance.execute(() -> {
            if (processSynced.compareAndSet(false, true)) syncEnabledPlugins();
            onAppForeground();
        });
    }

    public void onProviderEvent(String capability, String event, JSONObject input) {
        for (PluginPackageStore.InstalledPlugin installed : packages.load()) {
            if (!installed.enabled) continue;
            if (!events.isPermissionGranted(installed.manifest, "scheduler")) continue;
            for (RuntimePluginManifest.Task task : installed.manifest.tasks) {
                for (RuntimePluginManifest.Trigger trigger : task.triggers) {
                    if ("provider-event".equals(trigger.type)
                            && trigger.capability.equals(capability)
                            && trigger.event.equals(event)) {
                        try {
                            enqueueNow(installed, task, input, "provider-event");
                        } catch (CapabilityFailure ignored) {
                        }
                    }
                }
            }
        }
    }

    public void cancelPlugin(String pluginId) {
        PluginPackageStore.InstalledPlugin installed = packages.find(pluginId);
        if (installed != null) {
            for (RuntimePluginManifest.Task task : installed.manifest.tasks) runs.cancelActive(pluginId, task.id);
        }
        try {
            String prefix = "ats-v2-plugin:" + OriginKey.fromPluginId(pluginId);
            workManager.cancelAllWorkByTag(prefix);
        } catch (ContractException ignored) {
        }
        LinkedHashSet<String> registered = new LinkedHashSet<>(
                preferences.getStringSet(PREF_REGISTERED, Collections.emptySet())
        );
        registered.removeIf(value -> value.startsWith(pluginId + "#"));
        preferences.edit().putStringSet(PREF_REGISTERED, registered).apply();
    }

    JSONObject enqueueNow(
            PluginPackageStore.InstalledPlugin installed,
            RuntimePluginManifest.Task task,
            JSONObject input,
            String source
    ) throws CapabilityFailure {
        String pluginId = installed.manifest.plugin.id;
        if ("replace".equals(task.concurrencyPolicy)) {
            workManager.cancelAllWorkByTag(taskTag(pluginId, task.id));
            runs.cancelActive(pluginId, task.id);
            scheduleTask(installed, task);
        } else if ("forbid".equals(task.concurrencyPolicy)) {
            try {
                JSONObject previous = runs.lastRun(pluginId, task.id);
                String status = previous.optString("status", "never");
                if ("queued".equals(status) || "running".equals(status) || "retrying".equals(status)) {
                    RuntimePluginManifest.BackgroundEntry background = backgroundEntry(installed.manifest, task);
                    long since = previous.optLong("startedAt", previous.optLong("createdAt", 0L));
                    long lifetime = background == null ? 15 * 60_000L : background.timeoutMs + 60_000L;
                    if (since > 0L && System.currentTimeMillis() - since <= lifetime) {
                        return new JSONObject()
                                .put("taskId", task.id)
                                .put("runId", previous.optString("runId", ""))
                                .put("status", status)
                                .put("coalesced", true);
                    }
                    runs.cancelActive(pluginId, task.id);
                }
            } catch (IOException | JSONException error) {
                throw new CapabilityFailure("SCHEDULER_ERROR", safeMessage(error), true);
            }
        }
        try {
            TaskRunStore.Run run = runs.create(
                    pluginId,
                    installed.generationDirectory.getName(),
                    task.id,
                    input,
                    source
            );
            OneTimeWorkRequest request = oneTimeRequest(
                    installed, task, run.runId, source, false, task.constraints
            );
            workManager.enqueueUniqueWork(
                    runWorkName(pluginId, task.id, run.runId),
                    ExistingWorkPolicy.REPLACE,
                    request
            );
            emitRun(pluginId, task.id, run.runId, "queued", source);
            return new JSONObject()
                    .put("taskId", task.id)
                    .put("runId", run.runId)
                    .put("workId", request.getId().toString())
                    .put("status", "queued");
        } catch (IOException | JSONException error) {
            throw new CapabilityFailure("SCHEDULER_ERROR", safeMessage(error), true);
        }
    }

    private synchronized int scheduleTask(
            PluginPackageStore.InstalledPlugin installed,
            RuntimePluginManifest.Task task
    ) {
        int scheduled = 0;
        for (int index = 0; index < task.triggers.size(); index++) {
            RuntimePluginManifest.Trigger trigger = task.triggers.get(index);
            if ("periodic".equals(trigger.type)) {
                enqueuePeriodic(installed, task, trigger, index, trigger.intervalMinutes);
                scheduled++;
            } else if ("network-available".equals(trigger.type) || "charging".equals(trigger.type)) {
                enqueuePeriodic(installed, task, trigger, index, CONDITIONAL_POLL_MINUTES);
                scheduled++;
            }
        }
        return scheduled;
    }

    private void enqueuePeriodic(
            PluginPackageStore.InstalledPlugin installed,
            RuntimePluginManifest.Task task,
            RuntimePluginManifest.Trigger trigger,
            int triggerIndex,
            long intervalMinutes
    ) {
        Constraints constraints = constraints(task.constraints, trigger.type);
        Data data = workerData(
                installed.manifest.plugin.id,
                task.id,
                installed.generationDirectory.getName(),
                "",
                trigger.type,
                true
        );
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PluginTaskWorker.class,
                Math.max(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS / 60_000L, intervalMinutes),
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(data)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        clampBackoff(task.initialBackoffMs),
                        TimeUnit.MILLISECONDS
                )
                .addTag(pluginTag(installed.manifest.plugin.id))
                .addTag(taskTag(installed.manifest.plugin.id, task.id))
                .build();
        workManager.enqueueUniquePeriodicWork(
                triggerWorkName(installed.manifest.plugin.id, task.id, triggerIndex),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    private OneTimeWorkRequest oneTimeRequest(
            PluginPackageStore.InstalledPlugin installed,
            RuntimePluginManifest.Task task,
            String runId,
            String source,
            boolean scheduled,
            RuntimePluginManifest.Constraints taskConstraints
    ) {
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(PluginTaskWorker.class)
                .setConstraints(constraints(taskConstraints, ""))
                .setInputData(workerData(
                        installed.manifest.plugin.id,
                        task.id,
                        installed.generationDirectory.getName(),
                        runId,
                        source,
                        scheduled
                ))
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        clampBackoff(task.initialBackoffMs),
                        TimeUnit.MILLISECONDS
                )
                .addTag(pluginTag(installed.manifest.plugin.id))
                .addTag(taskTag(installed.manifest.plugin.id, task.id));
        if ("app-foreground".equals(source)) builder.setInitialDelay(3, TimeUnit.SECONDS);
        return builder.build();
    }

    private static Data workerData(
            String pluginId,
            String taskId,
            String generation,
            String runId,
            String source,
            boolean scheduled
    ) {
        return new Data.Builder()
                .putString(KEY_PLUGIN_ID, pluginId)
                .putString(KEY_TASK_ID, taskId)
                .putString(KEY_GENERATION, generation)
                .putString(KEY_RUN_ID, runId)
                .putString(KEY_SOURCE, source)
                .putBoolean(KEY_SCHEDULED, scheduled)
                .build();
    }

    private static Constraints constraints(RuntimePluginManifest.Constraints declared, String triggerType) {
        Constraints.Builder builder = new Constraints.Builder();
        String network = declared.network;
        if ("network-available".equals(triggerType) && "none".equals(network)) network = "connected";
        if ("unmetered".equals(network)) builder.setRequiredNetworkType(NetworkType.UNMETERED);
        else if ("connected".equals(network)) builder.setRequiredNetworkType(NetworkType.CONNECTED);
        if (declared.charging || "charging".equals(triggerType)) builder.setRequiresCharging(true);
        if (declared.batteryNotLow) builder.setRequiresBatteryNotLow(true);
        if (declared.storageNotLow) builder.setRequiresStorageNotLow(true);
        return builder.build();
    }

    private void cancelTask(String pluginId, String taskId) {
        workManager.cancelAllWorkByTag(taskTag(pluginId, taskId));
        runs.cancelActive(pluginId, taskId);
        emitRun(pluginId, taskId, "", "cancelled", "api");
    }

    private void emitRun(String pluginId, String taskId, String runId, String status, String source) {
        try {
            events.emitPluginEvent(pluginId, GeneratedContract.Events.SCHEDULER_RUNCHANGED, new JSONObject()
                    .put("taskId", taskId)
                    .put("runId", runId)
                    .put("status", status)
                    .put("source", source));
        } catch (JSONException ignored) {
        }
    }

    private PluginPackageStore.InstalledPlugin requireEnabled(String pluginId) throws CapabilityFailure {
        PluginPackageStore.InstalledPlugin installed = packages.find(pluginId);
        if (installed == null || !installed.enabled) {
            throw CapabilityFailure.unavailable("Plugin generation is not enabled", false);
        }
        return installed;
    }

    private static RuntimePluginManifest.Task requireTask(RuntimePluginManifest manifest, String taskId)
            throws CapabilityFailure {
        for (RuntimePluginManifest.Task task : manifest.tasks) {
            if (task.id.equals(taskId)) return task;
        }
        throw CapabilityFailure.invalid("Task is not declared by this plugin");
    }

    static RuntimePluginManifest.BackgroundEntry backgroundEntry(
            RuntimePluginManifest manifest,
            RuntimePluginManifest.Task task
    ) {
        for (RuntimePluginManifest.BackgroundEntry entry : manifest.backgroundEntries) {
            if (entry.id.equals(task.backgroundEntry)) return entry;
        }
        return null;
    }

    static RuntimePluginManifest.Task task(RuntimePluginManifest manifest, String taskId) {
        for (RuntimePluginManifest.Task task : manifest.tasks) if (task.id.equals(taskId)) return task;
        return null;
    }

    private static String requiredTaskId(JSONObject payload) throws CapabilityFailure {
        Object raw = payload == null ? null : payload.opt("taskId");
        if (!(raw instanceof String)) throw CapabilityFailure.invalid("taskId must be a string");
        String value = ((String) raw).trim();
        if (!value.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            throw CapabilityFailure.invalid("taskId is invalid");
        }
        return value;
    }

    private static JSONObject optionalObject(JSONObject payload, String key) throws CapabilityFailure {
        if (payload == null || !payload.has(key)) return new JSONObject();
        JSONObject value = payload.optJSONObject(key);
        if (value == null) throw CapabilityFailure.invalid(key + " must be an object");
        return value;
    }

    private static boolean hasTrigger(RuntimePluginManifest.Task task, String type) {
        for (RuntimePluginManifest.Trigger trigger : task.triggers) {
            if (type.equals(trigger.type)) return true;
        }
        return false;
    }

    private static long clampBackoff(long value) {
        return Math.max(WorkRequest.MIN_BACKOFF_MILLIS, Math.min(WorkRequest.MAX_BACKOFF_MILLIS, value));
    }

    private static String taskKey(String pluginId, String taskId) {
        return pluginId + "#" + taskId;
    }

    private static String taskTagFromKey(String key) {
        int separator = key.lastIndexOf('#');
        if (separator <= 0) return "ats-v2-task:invalid";
        return taskTag(key.substring(0, separator), key.substring(separator + 1));
    }

    private static String pluginTag(String pluginId) {
        try {
            return "ats-v2-plugin:" + OriginKey.fromPluginId(pluginId);
        } catch (ContractException error) {
            return "ats-v2-plugin:invalid";
        }
    }

    private static String taskTag(String pluginId, String taskId) {
        return pluginTag(pluginId) + ":task:" + taskId;
    }

    private static String runWorkName(String pluginId, String taskId, String runId) {
        return taskTag(pluginId, taskId) + ":run:" + runId;
    }

    private static String triggerWorkName(String pluginId, String taskId, int triggerIndex) {
        return taskTag(pluginId, taskId) + ":trigger:" + triggerIndex;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
