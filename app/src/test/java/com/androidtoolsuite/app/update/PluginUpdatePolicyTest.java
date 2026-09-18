package com.androidtoolsuite.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

import org.junit.Test;

public final class PluginUpdatePolicyTest {
    @Test
    public void automaticUpdateRequiresHigherVersionCode() throws Exception {
        UpdateCatalog.PluginRelease release = releaseWithDataCompatibility(7, 1, 1, 1);
        assertTrue(PluginUpdatePolicy.isUpdateAvailable(release, descriptor(6)));
        assertFalse(PluginUpdatePolicy.isUpdateAvailable(release, descriptor(7)));
        assertFalse(PluginUpdatePolicy.isUpdateAvailable(release, descriptor(8)));
        assertFalse(PluginUpdatePolicy.isUpdateAvailable(release, null));
        assertFalse(PluginUpdatePolicy.isUpdateAvailable(null, descriptor(6)));
    }

    @Test
    public void downgradeRequiresReadableDataFormat() throws Exception {
        UpdateCatalog.PluginRelease compatible = releaseWithDataCompatibility(5, 2, 1, 3);
        UpdateCatalog.PluginRelease incompatible = releaseWithDataCompatibility(5, 1, 1, 1);
        ImportedPluginDescriptor installed = descriptor(7);

        assertTrue(PluginUpdatePolicy.assessTransition(
                compatible, installed, true, "a".repeat(64), true, 3, true
        ) == PluginUpdatePolicy.Transition.DOWNGRADE_COMPATIBLE);
        assertTrue(PluginUpdatePolicy.assessTransition(
                incompatible, installed, true, "a".repeat(64), true, 3, true
        ) == PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE);
        assertTrue(PluginUpdatePolicy.assessTransition(
                compatible, installed, true, "a".repeat(64), true, 0, true
        ) == PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE);
    }

    @Test
    public void sameDigestIsCurrentBuild() throws Exception {
        UpdateCatalog.PluginRelease release = releaseWithDataCompatibility(7, 1, 1, 1);

        assertTrue(PluginUpdatePolicy.assessTransition(
                release, descriptor(7), true, release.sha256, true, 1, false
        ) == PluginUpdatePolicy.Transition.CURRENT);
    }

    @Test
    public void retainedDataIsNotTreatedAsFirstInstall() throws Exception {
        UpdateCatalog.PluginRelease release = releaseWithDataCompatibility(7, 1, 1, 1);

        assertTrue(PluginUpdatePolicy.assessTransition(
                release, null, false, "", true, 1, false
        ) == PluginUpdatePolicy.Transition.REINSTALL_COMPATIBLE);
        assertTrue(PluginUpdatePolicy.assessTransition(
                release, null, false, "", true, 0, false
        ) == PluginUpdatePolicy.Transition.DATA_INCOMPATIBLE);
        assertTrue(PluginUpdatePolicy.assessTransition(
                release, null, false, "", false, 0, false
        ) == PluginUpdatePolicy.Transition.INSTALL);
    }

    @Test
    public void declaredLegacyCompatibilityAcceptsUndeclaredV0Data() throws Exception {
        UpdateCatalog.PluginRelease release = releaseWithDataCompatibility(7, 1, 0, 1);

        assertTrue(release.canReadDataFormat(0));
        assertTrue(PluginUpdatePolicy.assessTransition(
                release, null, false, "", true, 0, false
        ) == PluginUpdatePolicy.Transition.REINSTALL_COMPATIBLE);
        assertTrue(PluginUpdatePolicy.assessTransition(
                release, descriptor(8), true, "a".repeat(64), true, 0, true
        ) == PluginUpdatePolicy.Transition.DOWNGRADE_COMPATIBLE);
    }

    private static ImportedPluginDescriptor descriptor(int versionCode) throws Exception {
        return ImportedPluginDescriptor.fromJson("{"
                + "\"formatVersion\":\"2\","
                + "\"plugin\":{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"version\":\"1.0.0\","
                + "\"versionCode\":" + versionCode
                + "}}"
        );
    }

    private static UpdateCatalog.PluginRelease releaseWithDataCompatibility(
            int versionCode,
            int dataFormatVersion,
            int minReadable,
            int maxReadable
    ) throws Exception {
        return UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"1.0.0\","
                + "\"versionCode\":" + versionCode + ","
                + "\"releaseUrl\":\"https://example.test/release\","
                + "\"downloadUrl\":\"https://example.test/plugin\","
                + "\"size\":1,"
                + "\"sha256\":\"" + "e".repeat(64) + "\","
                + "\"dataCompatibility\":{"
                + "\"schemaVersion\":1,"
                + "\"dataFormatVersion\":" + dataFormatVersion + ","
                + "\"minReadableDataFormatVersion\":" + minReadable + ","
                + "\"maxReadableDataFormatVersion\":" + maxReadable
                + "}}]}"
        ).plugins.get(0);
    }
}
