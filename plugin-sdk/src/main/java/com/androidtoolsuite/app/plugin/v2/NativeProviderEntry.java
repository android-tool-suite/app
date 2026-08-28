package com.androidtoolsuite.app.plugin.v2;

/** Entry point implemented by a trusted android/provider.apk generation. */
public interface NativeProviderEntry {
    AutoCloseable register(ProviderContext context, CapabilityRegistrar registrar) throws Exception;
}
