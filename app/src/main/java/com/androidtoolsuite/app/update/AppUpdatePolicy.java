package com.androidtoolsuite.app.update;

public final class AppUpdatePolicy {
    private AppUpdatePolicy() {
    }

    public static boolean isUpdateAvailable(
            UpdateCatalog.AppRelease release,
            String installedPackage,
            int installedVersionCode,
            boolean debugBuild
    ) {
        if (release == null || !clean(installedPackage).equals(release.packageName)) {
            return false;
        }
        if (debugBuild) {
            return false;
        }
        return UpdateCatalog.CHANNEL_RELEASE.equals(release.channel)
                && release.versionCode > installedVersionCode;
    }

    public static boolean isDownloadedVersionValid(
            long archiveVersionCode,
            int installedVersionCode,
            boolean debugBuild
    ) {
        return !debugBuild && archiveVersionCode > installedVersionCode;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
