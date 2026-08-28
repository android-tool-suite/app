package com.androidtoolsuite.app.plugin.v2;

import java.io.IOException;

/**
 * Lowest-level platform transport exposed only to explicitly trusted Provider entries.
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
