package com.androidtoolsuite.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppUpdatePolicyTest {
    @Test
    public void releaseRequiresNewerVersionAndReleasePackage() throws Exception {
        UpdateCatalog.AppRelease release = appRelease("release", "com.androidtoolsuite.app", 14);
        assertTrue(AppUpdatePolicy.isUpdateAvailable(
                release, "com.androidtoolsuite.app", 13, false
        ));
        assertFalse(AppUpdatePolicy.isUpdateAvailable(
                release, "com.androidtoolsuite.app", 14, false
        ));
        assertFalse(AppUpdatePolicy.isUpdateAvailable(
                release, "com.androidtoolsuite.app.debug", 13, false
        ));
    }

    @Test
    public void debugBuildNeverOffersRemoteAppUpdates() throws Exception {
        UpdateCatalog.AppRelease release = appRelease("release", "com.androidtoolsuite.app.debug", 14);
        assertFalse(AppUpdatePolicy.isUpdateAvailable(release, "com.androidtoolsuite.app.debug", 13, true));
        assertFalse(AppUpdatePolicy.isUpdateAvailable(release, "com.androidtoolsuite.app.debug", 14, true));
    }

    @Test
    public void downloadedVersionRulesDifferByBuildType() {
        assertFalse(AppUpdatePolicy.isDownloadedVersionValid(13, 13, true));
        assertFalse(AppUpdatePolicy.isDownloadedVersionValid(14, 13, true));
        assertFalse(AppUpdatePolicy.isDownloadedVersionValid(12, 13, true));
        assertTrue(AppUpdatePolicy.isDownloadedVersionValid(14, 13, false));
        assertFalse(AppUpdatePolicy.isDownloadedVersionValid(13, 13, false));
    }

    private UpdateCatalog.AppRelease appRelease(
            String channel,
            String packageName,
            int versionCode
    ) throws Exception {
        UpdateCatalog catalog = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"" + channel + "\","
                + "\"app\":{"
                + "\"packageName\":\"" + packageName + "\","
                + "\"versionName\":\"1.3.1\","
                + "\"versionCode\":" + versionCode + ","
                + "\"minSdk\":24,"
                + "\"releaseUrl\":\"https://example.test/release\","
                + "\"downloadUrl\":\"https://example.test/app.apk\","
                + "\"size\":42,"
                + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
                + "},\"plugins\":[]}");
        return catalog.app;
    }
}
