package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONObject;

import java.util.Set;

/** Android-side implementation of one versioned 插件运行时 capability definition. */
public interface CapabilityProvider {
    String capabilityId();

    String version();

    Set<String> methods();

    JSONObject call(CapabilityCall call) throws CapabilityFailure;

    default boolean isHealthy() {
        return true;
    }
}
