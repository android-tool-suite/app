package com.androidtoolsuite.app.plugin.runtime;

import android.content.Context;

/** Narrow application-scoped context passed to trusted, same-process providers. */
public interface ProviderContext {
    Context applicationContext();

    void log(String level, String message);

    /** Available only to Provider entries from packages that passed the trusted publisher verifier. */
    default TrustedPlatformBridge trustedPlatform() {
        throw new UnsupportedOperationException("Trusted platform bridge is unavailable");
    }
}
