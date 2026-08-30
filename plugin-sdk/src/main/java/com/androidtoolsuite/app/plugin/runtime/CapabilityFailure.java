package com.androidtoolsuite.app.plugin.runtime;

/** Structured provider failure that maps directly to the 插件运行时 RPC error model. */
public final class CapabilityFailure extends Exception {
    public final String code;
    public final boolean retryable;

    public CapabilityFailure(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public static CapabilityFailure invalid(String message) {
        return new CapabilityFailure("INVALID_REQUEST", message, false);
    }

    public static CapabilityFailure unavailable(String message, boolean retryable) {
        return new CapabilityFailure("CAPABILITY_UNAVAILABLE", message, retryable);
    }
}
