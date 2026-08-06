package com.androidtoolsuite.app.update;

public final class AppUpdatePolicy {
    private AppUpdatePolicy() {
    }

    public static boolean isUpdateAvailable(
            UpdateCatalog.AppRelease release,
            String installedPackage,
            int installedVersionCode,
            boolean debugBuild,
            String installedCommitSha
    ) {
        if (release == null || !clean(installedPackage).equals(release.packageName)) {
            return false;
        }
        if (debugBuild) {
            String currentCommit = clean(installedCommitSha);
            return UpdateCatalog.CHANNEL_DEBUG.equals(release.channel)
                    && currentCommit.matches("[0-9a-fA-F]{40}")
                    && release.versionCode >= installedVersionCode
                    && !currentCommit.equalsIgnoreCase(release.commitSha);
        }
        return UpdateCatalog.CHANNEL_RELEASE.equals(release.channel)
                && release.versionCode > installedVersionCode;
    }

    public static boolean isDownloadedVersionValid(
            long archiveVersionCode,
            int installedVersionCode,
            boolean debugBuild
    ) {
        return debugBuild
                ? archiveVersionCode >= installedVersionCode
                : archiveVersionCode > installedVersionCode;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
