package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;

/** Application context for fully trusted, same-process providers, which may use platform APIs directly. */
public interface ProviderContext {
    Context applicationContext();

    void log(String level, String message);

    /** Legacy compatibility only. New Providers own their platform transports and need not use this bridge. */
    default TrustedPlatformBridge trustedPlatform() {
        throw new UnsupportedOperationException("Trusted platform bridge is unavailable");
    }
}
