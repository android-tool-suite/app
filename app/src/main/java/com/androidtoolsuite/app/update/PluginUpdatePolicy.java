package com.androidtoolsuite.app.update;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

public final class PluginUpdatePolicy {
    private PluginUpdatePolicy() {
    }

    public static boolean isUpdateAvailable(
            UpdateCatalog.PluginRelease release,
            ImportedPluginDescriptor installed,
            boolean repositoryVerified,
            String installedChannel,
            String installedSha256
    ) {
        if (release == null || installed == null) {
            return false;
        }
        if (!repositoryVerified) {
            return release.versionCode > installed.versionCode;
        }

        String channel = clean(installedChannel);
        if (channel.isEmpty()) {
            channel = UpdateCatalog.CHANNEL_RELEASE;
        }
        if (!release.channel.equals(channel)) {
            return true;
        }
        if (UpdateCatalog.CHANNEL_DEBUG.equals(release.channel)) {
            return !release.sha256.equalsIgnoreCase(clean(installedSha256));
        }
        return release.versionCode > installed.versionCode;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
