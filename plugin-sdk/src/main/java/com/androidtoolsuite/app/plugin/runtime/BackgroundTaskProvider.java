package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONObject;

/** Trusted API 24+ implementation for a manifest provider-task background entry. */
public interface BackgroundTaskProvider {
    String entryId();

    JSONObject run(BackgroundTaskCall call) throws CapabilityFailure;
}
