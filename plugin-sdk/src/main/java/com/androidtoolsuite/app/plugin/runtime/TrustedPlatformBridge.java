package com.androidtoolsuite.app.plugin.runtime;

import java.io.IOException;

/**
 * Legacy compatibility transport. New trusted Providers may use Android/Binder/Shizuku directly.
 * Ordinary Tool packages cannot load native code and never receive this object.
 */
public interface TrustedPlatformBridge {
    String shizukuState();

    boolean isShizukuReady();

    boolean hasShizukuPermission();

    boolean isShizukuConnected();

    int shizukuUid();

    void requestShizukuPermission();

    void ensureShizukuConnected();

    String readSecureSetting(String name) throws IOException;

    void writeSecureSetting(String name, String value) throws IOException;
}
