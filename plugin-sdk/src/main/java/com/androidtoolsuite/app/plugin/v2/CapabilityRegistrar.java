package com.androidtoolsuite.app.plugin.v2;

import org.json.JSONObject;

/** Registry surface supplied to a trusted Native Provider entry. */
public interface CapabilityRegistrar {
    AutoCloseable register(CapabilityProvider provider) throws CapabilityFailure;

    AutoCloseable registerBackgroundTask(BackgroundTaskProvider provider) throws CapabilityFailure;

    void emitEvent(String capabilityId, String event, JSONObject payload) throws CapabilityFailure;
}
