package com.androidtoolsuite.app.plugin.v2;

import android.content.Context;
import android.content.SharedPreferences;

import com.androidtoolsuite.runtime.contract.GeneratedContract;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

/** Persistent per-plugin Capability consent with scope-fingerprint invalidation and bounded audit. */
public final class V2PluginPermissionManager {
    private static final String PREFS = "runtime_v2_permissions";
    private static final String KEY_AUDIT = "__audit";
    private static final String GRANTED = "granted|";
    private static final String DENIED = "denied|";
    private static final int MAX_AUDIT = 100;
    private static final long DENIAL_DEDUP_MILLIS = 60_000L;

    private final SharedPreferences preferences;
    private final Map<String, Request> requested = new LinkedHashMap<>();
    private final Set<String> trustedPluginIds = new LinkedHashSet<>();
    private final Map<String, Long> lastDenial = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public V2PluginPermissionManager(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void reconcile(List<V2PackageStore.InstalledPlugin> plugins) {
        requested.clear();
        trustedPluginIds.clear();
        for (V2PackageStore.InstalledPlugin installed : plugins) reconcileLocked(installed.manifest);
        Set<String> valid = new LinkedHashSet<>(requested.keySet());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (!KEY_AUDIT.equals(key) && !valid.contains(key)) editor.remove(key);
        }
        editor.apply();
    }

    public synchronized void reconcile(RuntimePluginManifest manifest) {
        String prefix = manifest.plugin.id + "|";
        if (isFullyTrusted(manifest)) trustedPluginIds.add(manifest.plugin.id);
        else trustedPluginIds.remove(manifest.plugin.id);
        Set<String> valid = new LinkedHashSet<>();
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!isManaged(manifest, requirement.id)) continue;
            Request request = request(manifest.plugin.id, requirement);
            requested.put(request.key, request);
            valid.add(request.key);
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix) && !valid.contains(key)) editor.remove(key);
        }
        editor.apply();
    }

    private void reconcileLocked(RuntimePluginManifest manifest) {
        if (isFullyTrusted(manifest)) trustedPluginIds.add(manifest.plugin.id);
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!isManaged(manifest, requirement.id)) continue;
            Request request = request(manifest.plugin.id, requirement);
            requested.put(request.key, request);
        }
    }

    public synchronized List<Permission> permissions(RuntimePluginManifest manifest) {
        List<Permission> values = new ArrayList<>();
        if (isFullyTrusted(manifest)) return Collections.emptyList();
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!isManaged(manifest, requirement.id)) continue;
            Request request = request(manifest.plugin.id, requirement);
            requested.put(request.key, request);
            values.add(new Permission(
                    requirement.id,
                    GeneratedContract.permissionTitle(requirement.id),
                    GeneratedContract.permissionDescription(requirement.id),
                    GeneratedContract.permissionRisk(requirement.id),
                    requirement.optional,
                    request.scopesJson,
                    state(request)
            ));
        }
        return Collections.unmodifiableList(values);
    }

    public synchronized boolean isGranted(RuntimePluginManifest manifest, String capabilityId) {
        if (isFullyTrusted(manifest)) return true;
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (!requirement.id.equals(capabilityId)) continue;
            Request request = request(manifest.plugin.id, requirement);
            requested.put(request.key, request);
            return state(request) == State.GRANTED;
        }
        return false;
    }

    public synchronized boolean isGranted(String pluginId, String capabilityId) {
        if (trustedPluginIds.contains(pluginId)) return true;
        if ("implicit".equals(GeneratedContract.permissionMode(capabilityId))) return true;
        Request request = requested.get(key(pluginId, capabilityId));
        return request != null && state(request) == State.GRANTED;
    }

    public void setGranted(RuntimePluginManifest manifest, String capabilityId, boolean granted) {
        Request request;
        synchronized (this) {
            request = declaredRequest(manifest, capabilityId);
            if (!isManaged(manifest, capabilityId)) return;
            requested.put(request.key, request);
            String value = (granted ? GRANTED : DENIED) + request.scopeFingerprint;
            if (!preferences.edit().putString(request.key, value).commit()) {
                throw new IllegalStateException("无法保存插件权限");
            }
            appendAuditLocked(manifest.plugin.id, capabilityId, granted ? "granted" : "revoked", "user");
        }
        for (Listener listener : listeners) {
            listener.onChanged(manifest.plugin.id, capabilityId, granted);
        }
    }

    public synchronized void recordDenied(String pluginId, String capabilityId, String reason) {
        long now = System.currentTimeMillis();
        String key = key(pluginId, capabilityId);
        Long previous = lastDenial.get(key);
        if (previous != null && now - previous < DENIAL_DEDUP_MILLIS) return;
        lastDenial.put(key, now);
        appendAuditLocked(pluginId, capabilityId, "denied", reason);
    }

    public synchronized void removePlugin(String pluginId) {
        String prefix = pluginId + "|";
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        editor.apply();
        requested.keySet().removeIf(key -> key.startsWith(prefix));
        lastDenial.keySet().removeIf(key -> key.startsWith(prefix));
        trustedPluginIds.remove(pluginId);
    }

    public synchronized JSONArray audit(String pluginId) {
        JSONArray all = readAuditLocked();
        JSONArray result = new JSONArray();
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.optJSONObject(index);
            if (item != null && (pluginId == null || pluginId.equals(item.optString("pluginId")))) {
                result.put(item);
            }
        }
        return result;
    }

    public AutoCloseable addListener(Listener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private State state(Request request) {
        if ("implicit".equals(GeneratedContract.permissionMode(request.capabilityId))) return State.GRANTED;
        String stored = preferences.getString(request.key, "");
        if ((GRANTED + request.scopeFingerprint).equals(stored)) return State.GRANTED;
        if ((DENIED + request.scopeFingerprint).equals(stored)) return State.DENIED;
        return State.PENDING;
    }

    private Request declaredRequest(RuntimePluginManifest manifest, String capabilityId) {
        for (RuntimePluginManifest.CapabilityRequirement requirement : manifest.capabilityRequirements) {
            if (requirement.id.equals(capabilityId)) return request(manifest.plugin.id, requirement);
        }
        throw new IllegalArgumentException("插件未声明 Capability：" + capabilityId);
    }

    private static Request request(String pluginId, RuntimePluginManifest.CapabilityRequirement requirement) {
        return new Request(
                key(pluginId, requirement.id),
                requirement.id,
                canonicalScopes(requirement.scopesJson),
                scopeFingerprint(requirement.scopesJson)
        );
    }

    private static String canonicalScopes(String raw) {
        try {
            return canonicalJson(new JSONObject(raw == null ? "{}" : raw));
        } catch (JSONException error) {
            return "{}";
        }
    }

    private static String canonicalJson(Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            TreeSet<String> names = new TreeSet<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) names.add(iterator.next());
            for (String name : names) {
                if (!first) result.append(',');
                first = false;
                result.append(JSONObject.quote(name)).append(':').append(canonicalJson(object.get(name)));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int index = 0; index < array.length(); index++) {
                if (index > 0) result.append(',');
                result.append(canonicalJson(array.get(index)));
            }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        throw new JSONException("Unsupported scope value");
    }

    private static String scopeFingerprint(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalScopes(raw).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private void appendAuditLocked(String pluginId, String capabilityId, String action, String reason) {
        JSONArray existing = readAuditLocked();
        JSONArray next = new JSONArray();
        try {
            next.put(new JSONObject()
                    .put("timestamp", System.currentTimeMillis())
                    .put("pluginId", pluginId)
                    .put("capability", capabilityId)
                    .put("action", action)
                    .put("reason", reason == null ? "" : reason));
            for (int index = 0; index < existing.length() && next.length() < MAX_AUDIT; index++) {
                JSONObject item = existing.optJSONObject(index);
                if (item != null) next.put(item);
            }
        } catch (JSONException ignored) {
        }
        preferences.edit().putString(KEY_AUDIT, next.toString()).apply();
    }

    private JSONArray readAuditLocked() {
        try {
            return new JSONArray(preferences.getString(KEY_AUDIT, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    private static String key(String pluginId, String capabilityId) {
        return pluginId + "|" + capabilityId;
    }

    private static boolean isFullyTrusted(RuntimePluginManifest manifest) {
        return "trusted-provider".equals(manifest.plugin.kind);
    }

    private static boolean isManaged(RuntimePluginManifest manifest, String capabilityId) {
        return !isFullyTrusted(manifest)
                && !"implicit".equals(GeneratedContract.permissionMode(capabilityId));
    }

    public enum State { GRANTED, PENDING, DENIED }

    public static final class Permission {
        public final String capabilityId;
        public final String title;
        public final String description;
        public final String risk;
        public final boolean optional;
        public final String scopesJson;
        public final State state;

        Permission(String capabilityId, String title, String description, String risk, boolean optional,
                   String scopesJson, State state) {
            this.capabilityId = capabilityId;
            this.title = title;
            this.description = description;
            this.risk = risk;
            this.optional = optional;
            this.scopesJson = scopesJson;
            this.state = state;
        }

        public boolean isManaged() {
            return !"implicit".equals(GeneratedContract.permissionMode(capabilityId));
        }
    }

    private static final class Request {
        final String key;
        final String capabilityId;
        final String scopesJson;
        final String scopeFingerprint;

        Request(String key, String capabilityId, String scopesJson, String scopeFingerprint) {
            this.key = key;
            this.capabilityId = capabilityId;
            this.scopesJson = scopesJson;
            this.scopeFingerprint = scopeFingerprint;
        }
    }

    public interface Listener {
        void onChanged(String pluginId, String capabilityId, boolean granted);
    }
}
