package com.androidtoolsuite.app.plugin.runtime;

import com.androidtoolsuite.app.plugin.api.PluginDependency;
import com.androidtoolsuite.app.plugin.runtime.CapabilityCall;
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;
import com.androidtoolsuite.app.plugin.runtime.CapabilityProvider;
import com.androidtoolsuite.app.plugin.runtime.CapabilityRegistrar;
import com.androidtoolsuite.app.plugin.runtime.BackgroundTaskProvider;
import com.androidtoolsuite.runtime.contract.GeneratedContract;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Definition/provider/consumer seam for 插件运行时. Registration is reversible, resolution is by
 * capability ID and compatible version, and provider failures enter a bounded backoff state.
 */
public final class CapabilityRouter implements CapabilityRegistrar, AutoCloseable {
    private static final int FAILURE_THRESHOLD = 3;
    private static final long OFFLINE_BACKOFF_MILLIS = 30_000L;

    private final Object lock = new Object();
    private final Map<String, List<ProviderRecord>> providers = new LinkedHashMap<>();
    private final Map<String, List<EventSubscription>> eventListeners = new LinkedHashMap<>();
    private final Map<String, List<ActiveCall>> activeCalls = new LinkedHashMap<>();
    private final List<CapabilityEventListener> capabilityEventListeners = new ArrayList<>();
    private final ExecutorService calls = Executors.newCachedThreadPool();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final BackgroundTaskRegistry backgroundTasks;
    private final PluginPermissionManager permissions;
    private final AutoCloseable permissionListener;

    public CapabilityRouter() {
        this(new BackgroundTaskRegistry(), null);
    }

    public CapabilityRouter(BackgroundTaskRegistry backgroundTasks) {
        this(backgroundTasks, null);
    }

    public CapabilityRouter(
            BackgroundTaskRegistry backgroundTasks,
            PluginPermissionManager permissions
    ) {
        this.backgroundTasks = backgroundTasks;
        this.permissions = permissions;
        this.permissionListener = permissions == null ? null : permissions.addListener(this::onPermissionChanged);
    }

    @Override
    public AutoCloseable register(CapabilityProvider provider) throws CapabilityFailure {
        return register(provider, "external", 0);
    }

    @Override
    public AutoCloseable registerBackgroundTask(BackgroundTaskProvider provider) throws CapabilityFailure {
        return backgroundTasks.register(provider, "external");
    }

    @Override
    public void emitEvent(String capabilityId, String event, JSONObject payload) throws CapabilityFailure {
        if (clean(capabilityId).isEmpty() || clean(event).isEmpty()) {
            throw CapabilityFailure.invalid("Capability event descriptor is incomplete");
        }
        emitCapabilityEvent(capabilityId, event, payload);
    }

    public AutoCloseable register(CapabilityProvider provider, String source, int priority)
            throws CapabilityFailure {
        Objects.requireNonNull(provider, "provider");
        String capabilityId = clean(provider.capabilityId());
        String version = clean(provider.version());
        if (capabilityId.isEmpty() || version.isEmpty() || provider.methods().isEmpty()) {
            throw CapabilityFailure.invalid("Provider descriptor is incomplete");
        }
        for (String method : provider.methods()) {
            String knownCapability = GeneratedContract.capabilityForMethod(method);
            if ((knownCapability != null && !capabilityId.equals(knownCapability))
                    || (knownCapability == null
                    && !(method.equals(capabilityId) || method.startsWith(capabilityId + ".")))) {
                throw CapabilityFailure.invalid("Provider registered a method outside " + capabilityId + ": " + method);
            }
        }
        ProviderRecord record = new ProviderRecord(provider, clean(source), priority);
        synchronized (lock) {
            for (Map.Entry<String, List<ProviderRecord>> entry : providers.entrySet()) {
                if (entry.getKey().equals(capabilityId)) continue;
                for (ProviderRecord existing : entry.getValue()) {
                    for (String method : provider.methods()) {
                        if (existing.provider.methods().contains(method)) {
                            throw CapabilityFailure.invalid(
                                    "Capability method is already owned by " + entry.getKey() + ": " + method
                            );
                        }
                    }
                }
            }
            List<ProviderRecord> records = providers.computeIfAbsent(capabilityId, ignored -> new ArrayList<>());
            for (ProviderRecord existing : records) {
                if (existing.source.equals(record.source) && existing.version.equals(record.version)) {
                    throw CapabilityFailure.invalid("Provider generation already registered: " + source);
                }
            }
            records.add(record);
            records.sort(Comparator
                    .comparingInt((ProviderRecord item) -> item.priority).reversed()
                    .thenComparing((left, right) -> PluginDependency.compareVersions(right.version, left.version)));
        }
        return () -> unregister(record);
    }

    public boolean canResolve(String capabilityId, String versionRequirement) {
        try {
            resolve(capabilityId, versionRequirement);
            return true;
        } catch (CapabilityFailure ignored) {
            return false;
        }
    }

    public List<String> availableCapabilities(RuntimePluginManifest manifest) {
        List<String> available = new ArrayList<>();
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            try {
                ProviderRecord record = resolve(requirement.id, requirement.version);
                if (permissions == null || permissions.isGranted(manifest, requirement.id)) {
                    available.add(requirement.id + "@" + record.version);
                }
            } catch (CapabilityFailure ignored) {
            }
        }
        return Collections.unmodifiableList(available);
    }

    public JSONArray permissionSnapshot(RuntimePluginManifest manifest) {
        JSONArray values = new JSONArray();
        if (permissions == null) {
            for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
                values.put(permissionItem(requirement.id, "granted", requirement.optional));
            }
            return values;
        }
        for (PluginPermissionManager.Permission permission : permissions.permissions(manifest)) {
            values.put(permissionItem(
                    permission.capabilityId,
                    permission.state.name().toLowerCase(java.util.Locale.ROOT),
                    permission.optional
            ));
        }
        return values;
    }

    public boolean isPermissionGranted(RuntimePluginManifest manifest, String capabilityId) {
        return permissions == null || permissions.isGranted(manifest, capabilityId);
    }

    private static JSONObject permissionItem(String capabilityId, String state, boolean optional) {
        try {
            return new JSONObject()
                    .put("capability", capabilityId)
                    .put("title", GeneratedContract.permissionTitle(capabilityId))
                    .put("risk", GeneratedContract.permissionRisk(capabilityId))
                    .put("state", state)
                    .put("optional", optional);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    public CompletableFuture<JSONObject> invoke(
            RuntimePluginManifest manifest,
            String pluginId,
            String sessionId,
            String method,
            Object rawPayload,
            boolean userGesture,
            int deadlineMs
    ) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        String capabilityId = capabilityForMethod(method);
        if (capabilityId == null) {
            result.completeExceptionally(CapabilityFailure.invalid("Unknown capability method: " + method));
            return result;
        }
        RuntimePluginManifest.CapabilityRequirement requirement = declaredRequirement(manifest, capabilityId);
        if (requirement == null) {
            result.completeExceptionally(new CapabilityFailure(
                    "CAPABILITY_UNDECLARED",
                    "Plugin did not declare capability " + capabilityId,
                    false
            ));
            return result;
        }
        if (!(rawPayload instanceof JSONObject)) {
            result.completeExceptionally(CapabilityFailure.invalid("Capability payload must be an object"));
            return result;
        }
        if (permissions != null && !permissions.isGranted(manifest, capabilityId)) {
            permissions.recordDenied(pluginId, capabilityId, "not-granted");
            result.completeExceptionally(new CapabilityFailure(
                    "PERMISSION_DENIED",
                    "请在管理页允许插件权限：" + GeneratedContract.permissionTitle(capabilityId),
                    false
            ));
            return result;
        }
        final ProviderRecord provider;
        try {
            provider = resolve(capabilityId, requirement.version);
            if (!provider.provider.methods().contains(method)) {
                throw new CapabilityFailure(
                        "CAPABILITY_VERSION_MISMATCH",
                        "Selected provider does not implement " + method,
                        false
                );
            }
        } catch (CapabilityFailure failure) {
            result.completeExceptionally(failure);
            return result;
        }

        long deadlineEpoch = System.currentTimeMillis() + deadlineMs;
        ActiveCall activeCall = new ActiveCall(pluginId, capabilityId, result);
        synchronized (lock) {
            activeCalls.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(activeCall);
        }
        Future<?> work = calls.submit(() -> {
            try {
                JSONObject value = provider.provider.call(new CapabilityCall(
                        pluginId,
                        sessionId,
                        method,
                        (JSONObject) rawPayload,
                        parseScopes(requirement.scopesJson),
                        userGesture,
                        deadlineEpoch
                ));
                provider.recordSuccess();
                result.complete(value == null ? new JSONObject() : value);
            } catch (CapabilityFailure failure) {
                provider.recordFailure(failure.retryable && "INTERNAL".equals(failure.code));
                result.completeExceptionally(failure);
            } catch (Throwable failure) {
                provider.recordFailure(true);
                result.completeExceptionally(new CapabilityFailure(
                        "INTERNAL",
                        "系统功能暂时失败：" + safeMessage(failure),
                        true
                ));
            }
        });
        activeCall.setWork(work);
        ScheduledFuture<?> timeout = deadlines.schedule(() -> {
            if (result.completeExceptionally(new CapabilityFailure("TIMEOUT", "Capability call timed out", true))) {
                work.cancel(true);
                provider.recordFailure(true);
            }
        }, deadlineMs, TimeUnit.MILLISECONDS);
        result.whenComplete((ignored, failure) -> {
            timeout.cancel(false);
            removeActiveCall(activeCall);
        });
        return result;
    }

    private String capabilityForMethod(String method) {
        String known = GeneratedContract.capabilityForMethod(method);
        if (known != null) return known;
        synchronized (lock) {
            String resolved = null;
            for (Map.Entry<String, List<ProviderRecord>> entry : providers.entrySet()) {
                for (ProviderRecord record : entry.getValue()) {
                    if (!record.provider.methods().contains(method)) continue;
                    if (resolved != null && !resolved.equals(entry.getKey())) return null;
                    resolved = entry.getKey();
                }
            }
            return resolved;
        }
    }

    public JSONObject debugGraph() {
        JSONArray nodes = new JSONArray();
        synchronized (lock) {
            for (Map.Entry<String, List<ProviderRecord>> entry : providers.entrySet()) {
                for (ProviderRecord record : entry.getValue()) {
                    try {
                        nodes.put(new JSONObject()
                                .put("capability", entry.getKey())
                                .put("version", record.version)
                                .put("source", record.source)
                                .put("state", record.state().name().toLowerCase(java.util.Locale.ROOT))
                                .put("failures", record.failures));
                    } catch (JSONException ignored) {
                    }
                }
            }
        }
        try {
            return new JSONObject().put("providers", nodes);
        } catch (JSONException impossible) {
            return new JSONObject();
        }
    }

    public AutoCloseable subscribeEvents(
            String pluginId,
            Set<String> declaredCapabilities,
            EventListener listener
    ) {
        Objects.requireNonNull(listener, "listener");
        String cleanPluginId = clean(pluginId);
        EventSubscription subscription = new EventSubscription(
                cleanPluginId,
                listener,
                declaredCapabilities == null
                        ? Collections.emptySet()
                        : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(declaredCapabilities))
        );
        synchronized (lock) {
            eventListeners.computeIfAbsent(cleanPluginId, ignored -> new ArrayList<>()).add(subscription);
        }
        return () -> {
            synchronized (lock) {
                List<EventSubscription> listeners = eventListeners.get(cleanPluginId);
                if (listeners == null) return;
                listeners.remove(subscription);
                if (listeners.isEmpty()) eventListeners.remove(cleanPluginId);
            }
        };
    }

    public void emitPluginEvent(String pluginId, String event, JSONObject payload) {
        List<EventSubscription> listeners;
        synchronized (lock) {
            listeners = new ArrayList<>(eventListeners.getOrDefault(clean(pluginId), Collections.emptyList()));
        }
        JSONObject safePayload;
        try {
            safePayload = payload == null ? new JSONObject() : new JSONObject(payload.toString());
        } catch (JSONException error) {
            return;
        }
        for (EventSubscription subscription : listeners) {
            try {
                subscription.listener.onEvent(event, safePayload);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public AutoCloseable subscribeCapabilityEvents(CapabilityEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            capabilityEventListeners.add(listener);
        }
        return () -> {
            synchronized (lock) {
                capabilityEventListeners.remove(listener);
            }
        };
    }

    public void emitCapabilityEvent(String capabilityId, String event, JSONObject payload) {
        List<EventSubscription> consumers = new ArrayList<>();
        List<CapabilityEventListener> systemListeners;
        synchronized (lock) {
            for (List<EventSubscription> subscriptions : eventListeners.values()) {
                for (EventSubscription subscription : subscriptions) {
                    if (subscription.capabilities.contains(capabilityId)
                            && (permissions == null || permissions.isGranted(subscription.pluginId, capabilityId))) {
                        consumers.add(subscription);
                    }
                }
            }
            systemListeners = new ArrayList<>(capabilityEventListeners);
        }
        JSONObject safePayload;
        try {
            safePayload = payload == null ? new JSONObject() : new JSONObject(payload.toString());
        } catch (JSONException error) {
            return;
        }
        for (EventSubscription subscription : consumers) {
            try { subscription.listener.onEvent(event, safePayload); } catch (RuntimeException ignored) { }
        }
        for (CapabilityEventListener listener : systemListeners) {
            try { listener.onEvent(capabilityId, event, safePayload); } catch (RuntimeException ignored) { }
        }
    }

    private ProviderRecord resolve(String capabilityId, String versionRequirement) throws CapabilityFailure {
        synchronized (lock) {
            for (ProviderRecord record : providers.getOrDefault(capabilityId, Collections.emptyList())) {
                if (versionSatisfied(record.version, versionRequirement) && record.isAvailable()) {
                    return record;
                }
            }
        }
        throw CapabilityFailure.unavailable(
                "No healthy provider satisfies " + capabilityId + " " + versionRequirement,
                true
        );
    }

    private void onPermissionChanged(String pluginId, String capabilityId, boolean granted) {
        if (!granted) {
            List<ActiveCall> cancelled = new ArrayList<>();
            synchronized (lock) {
                for (ActiveCall call : activeCalls.getOrDefault(pluginId, Collections.emptyList())) {
                    if (call.capabilityId.equals(capabilityId)) cancelled.add(call);
                }
            }
            for (ActiveCall call : cancelled) call.cancel();
        }
        try {
            emitPluginEvent(pluginId, "app.permissionsChanged", new JSONObject()
                    .put("capability", capabilityId)
                    .put("granted", granted));
        } catch (JSONException ignored) {
        }
    }

    private void removeActiveCall(ActiveCall call) {
        synchronized (lock) {
            List<ActiveCall> values = activeCalls.get(call.pluginId);
            if (values == null) return;
            values.remove(call);
            if (values.isEmpty()) activeCalls.remove(call.pluginId);
        }
    }

    private static RuntimePluginManifest.CapabilityRequirement declaredRequirement(
            RuntimePluginManifest manifest,
            String capabilityId
    ) {
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (requirement.id.equals(capabilityId)) return requirement;
        }
        return null;
    }

    private static JSONObject parseScopes(String raw) throws CapabilityFailure {
        try {
            return new JSONObject(raw == null ? "{}" : raw);
        } catch (JSONException error) {
            throw CapabilityFailure.invalid("Capability scopes are invalid");
        }
    }

    public static boolean versionSatisfied(String actual, String requirement) {
        if (actual == null) return false;
        String value = clean(requirement);
        if (value.isEmpty() || "*".equals(value)) return true;
        String[] clauses = value.split("(?:\\s*,\\s*|\\s+)");
        for (String clause : clauses) {
            if (clause.isEmpty()) continue;
            if (clause.startsWith("^")) {
                String base = clause.substring(1);
                if (PluginDependency.compareVersions(actual, base) < 0
                        || major(actual) != major(base)) return false;
                continue;
            }
            if (clause.startsWith("~")) {
                String base = clause.substring(1);
                if (PluginDependency.compareVersions(actual, base) < 0
                        || major(actual) != major(base) || minor(actual) != minor(base)) return false;
                continue;
            }
            String operator = "=";
            String expected = clause;
            for (String candidate : new String[]{">=", "<=", "==", ">", "<", "="}) {
                if (clause.startsWith(candidate)) {
                    operator = candidate;
                    expected = clause.substring(candidate.length());
                    break;
                }
            }
            if (!PluginDependency.parse("runtime" + operator + expected)
                    .isSatisfied(Collections.singletonMap("runtime", actual))) return false;
        }
        return true;
    }

    private static int major(String version) {
        return versionPart(version, 0);
    }

    private static int minor(String version) {
        return versionPart(version, 1);
    }

    private static int versionPart(String version, int index) {
        String[] parts = version.split("[.-]", -1);
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void unregister(ProviderRecord target) {
        synchronized (lock) {
            List<ProviderRecord> records = providers.get(target.capabilityId);
            if (records == null) return;
            records.remove(target);
            if (records.isEmpty()) providers.remove(target.capabilityId);
        }
    }

    @Override
    public void close() {
        List<ActiveCall> cancelled = new ArrayList<>();
        synchronized (lock) {
            providers.clear();
            eventListeners.clear();
            capabilityEventListeners.clear();
            for (List<ActiveCall> values : activeCalls.values()) cancelled.addAll(values);
            activeCalls.clear();
        }
        for (ActiveCall call : cancelled) call.cancel();
        if (permissionListener != null) {
            try { permissionListener.close(); } catch (Exception ignored) { }
        }
        calls.shutdownNow();
        deadlines.shutdownNow();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private enum ProviderState { READY, DEGRADED, OFFLINE }

    public interface EventListener {
        void onEvent(String event, JSONObject payload);
    }

    public interface CapabilityEventListener {
        void onEvent(String capabilityId, String event, JSONObject payload);
    }

    private static final class EventSubscription {
        final String pluginId;
        final EventListener listener;
        final Set<String> capabilities;

        EventSubscription(String pluginId, EventListener listener, Set<String> capabilities) {
            this.pluginId = pluginId;
            this.listener = listener;
            this.capabilities = capabilities;
        }
    }

    private static final class ActiveCall {
        final String pluginId;
        final String capabilityId;
        final CompletableFuture<JSONObject> result;
        private Future<?> work;
        private boolean cancelled;

        ActiveCall(String pluginId, String capabilityId, CompletableFuture<JSONObject> result) {
            this.pluginId = pluginId;
            this.capabilityId = capabilityId;
            this.result = result;
        }

        synchronized void setWork(Future<?> work) {
            this.work = work;
            if (cancelled) work.cancel(true);
        }

        synchronized void cancel() {
            cancelled = true;
            result.completeExceptionally(new CapabilityFailure(
                    "PERMISSION_DENIED",
                    "插件权限已撤销：" + GeneratedContract.permissionTitle(capabilityId),
                    false
            ));
            if (work != null) work.cancel(true);
        }
    }

    private static final class ProviderRecord {
        final CapabilityProvider provider;
        final String capabilityId;
        final String version;
        final String source;
        final int priority;
        int failures;
        long offlineUntil;

        ProviderRecord(CapabilityProvider provider, String source, int priority) {
            this.provider = provider;
            this.capabilityId = provider.capabilityId();
            this.version = provider.version();
            this.source = source;
            this.priority = priority;
        }

        synchronized boolean isAvailable() {
            if (offlineUntil > 0 && System.currentTimeMillis() >= offlineUntil) {
                offlineUntil = 0;
                failures = FAILURE_THRESHOLD - 1;
            }
            return offlineUntil == 0 && provider.isHealthy();
        }

        synchronized void recordSuccess() {
            failures = 0;
            offlineUntil = 0;
        }

        synchronized void recordFailure(boolean retryable) {
            if (!retryable) return;
            failures++;
            if (failures >= FAILURE_THRESHOLD) offlineUntil = System.currentTimeMillis() + OFFLINE_BACKOFF_MILLIS;
        }

        synchronized ProviderState state() {
            if (offlineUntil > System.currentTimeMillis()) return ProviderState.OFFLINE;
            if (failures > 0 || !provider.isHealthy()) return ProviderState.DEGRADED;
            return ProviderState.READY;
        }
    }
}
