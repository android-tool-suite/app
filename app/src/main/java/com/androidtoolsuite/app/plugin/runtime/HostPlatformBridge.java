package com.androidtoolsuite.app.plugin.runtime;

import com.androidtoolsuite.app.plugin.runtime.TrustedPlatformBridge;

import java.io.IOException;

/** Host-owned Android/Shizuku bootstrap kept outside ordinary plugin permissions. */
final class HostPlatformBridge implements TrustedPlatformBridge {
    private final ShizukuService shizuku;

    HostPlatformBridge(ShizukuService shizuku) {
        this.shizuku = shizuku;
    }

    @Override public String shizukuState() { return shizuku.state(); }
    @Override public boolean isShizukuReady() { return shizuku.isReady(); }
    @Override public boolean hasShizukuPermission() { return shizuku.hasPermission(); }
    @Override public boolean isShizukuConnected() { return shizuku.isConnected(); }
    @Override public int shizukuUid() { return shizuku.uid(); }
    @Override public void requestShizukuPermission() { shizuku.requestPermission(); }
    @Override public void ensureShizukuConnected() { shizuku.ensure(); }
    @Override public String readSecureSetting(String name) throws IOException {
        return shizuku.readSecureSetting(name);
    }
    @Override public void writeSecureSetting(String name, String value) throws IOException {
        shizuku.writeSecureSetting(name, value);
    }
}
