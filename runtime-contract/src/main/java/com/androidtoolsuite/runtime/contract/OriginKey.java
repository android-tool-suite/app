package com.androidtoolsuite.runtime.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class OriginKey {
    private OriginKey() {
    }

    public static String fromPluginId(String pluginId) throws ContractException {
        String normalized = ContractPatterns.requireId("plugin.id", pluginId, 128);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(40);
            for (int index = 0; index < 20; index++) {
                key.append(Character.forDigit((digest[index] >>> 4) & 0x0f, 16));
                key.append(Character.forDigit(digest[index] & 0x0f, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String virtualOrigin(String pluginId) throws ContractException {
        return "https://" + fromPluginId(pluginId) + ".plugins.android-tool-suite.test";
    }
}
