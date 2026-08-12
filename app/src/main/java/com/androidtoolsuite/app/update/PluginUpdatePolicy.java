package com.androidtoolsuite.app.update;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

public final class PluginUpdatePolicy {
    public enum Transition {
        INSTALL,
        REINSTALL_COMPATIBLE,
        CURRENT,
        UPGRADE,
        REPLACE,
        DOWNGRADE_COMPATIBLE,
        DATA_INCOMPATIBLE
    }

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

    public static Transition assessTransition(
            UpdateCatalog.PluginRelease target,
            ImportedPluginDescriptor installed,
            boolean repositoryVerified,
            String installedSha256,
            boolean mayHaveData,
            int installedDataFormatVersion,
            boolean targetIsOlderBuild
    ) {
        if (target == null) {
            return Transition.CURRENT;
        }
        if (installed == null) {
            if (!mayHaveData) {
                return Transition.INSTALL;
            }
            return target.canReadDataFormat(installedDataFormatVersion)
                    ? Transition.REINSTALL_COMPATIBLE
                    : Transition.DATA_INCOMPATIBLE;
        }
        if (repositoryVerified
                && target.sha256.equalsIgnoreCase(clean(installedSha256))) {
            return Transition.CURRENT;
        }

        boolean downgrade = target.versionCode < installed.versionCode
                || (target.versionCode == installed.versionCode && targetIsOlderBuild);
        if (downgrade) {
            return target.canReadDataFormat(installedDataFormatVersion)
                    ? Transition.DOWNGRADE_COMPATIBLE
                    : Transition.DATA_INCOMPATIBLE;
        }

        if (!target.canReadDataFormat(installedDataFormatVersion)) {
            return Transition.DATA_INCOMPATIBLE;
        }
        return target.versionCode > installed.versionCode
                ? Transition.UPGRADE
                : Transition.REPLACE;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
