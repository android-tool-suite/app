package com.androidtoolsuite.app.plugin.v2;

import com.androidtoolsuite.app.plugin.v2.BackgroundTaskCall;
import com.androidtoolsuite.app.plugin.v2.BackgroundTaskProvider;
import com.androidtoolsuite.app.plugin.v2.CapabilityFailure;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reversible registry for API 24+ provider-task entries. */
public final class V2BackgroundTaskRegistry {
    private final Map<String, Record> providers = new LinkedHashMap<>();

    public synchronized AutoCloseable register(BackgroundTaskProvider provider, String source)
            throws CapabilityFailure {
        return register("", provider, source);
    }

    public synchronized AutoCloseable register(String pluginId, BackgroundTaskProvider provider, String source)
            throws CapabilityFailure {
        String entryId = provider == null ? "" : provider.entryId().trim();
        if (entryId.isEmpty()) throw CapabilityFailure.invalid("Background task entry ID is missing");
        String key = key(pluginId, entryId);
        if (providers.containsKey(key)) throw CapabilityFailure.invalid("Background task entry is already registered");
        Record record = new Record(provider, source == null ? "" : source);
        providers.put(key, record);
        return () -> unregister(key, record);
    }

    public synchronized boolean contains(String pluginId, String entryId) {
        return providers.containsKey(key(pluginId, entryId));
    }

    public JSONObject invoke(String pluginId, String entryId, BackgroundTaskCall call) throws CapabilityFailure {
        final Record record;
        synchronized (this) {
            record = providers.get(key(pluginId, entryId));
        }
        if (record == null) throw CapabilityFailure.unavailable("Background task provider is unavailable", true);
        return record.provider.run(call);
    }

    private synchronized void unregister(String key, Record expected) {
        if (providers.get(key) == expected) providers.remove(key);
    }

    private static String key(String pluginId, String entryId) {
        return (pluginId == null ? "" : pluginId.trim()) + "\u0000" + (entryId == null ? "" : entryId.trim());
    }

    private static final class Record {
        final BackgroundTaskProvider provider;
        final String source;

        Record(BackgroundTaskProvider provider, String source) {
            this.provider = provider;
            this.source = source;
        }
    }
}
