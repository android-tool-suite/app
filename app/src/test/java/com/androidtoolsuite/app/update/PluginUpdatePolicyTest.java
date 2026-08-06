package com.androidtoolsuite.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

import org.junit.Test;

public final class PluginUpdatePolicyTest {
    @Test
    public void debugBuildUsesDigestEvenWhenVersionCodeIsUnchanged() throws Exception {
        UpdateCatalog.PluginRelease release = debugRelease("b");
        ImportedPluginDescriptor installed = descriptor(7);

        assertTrue(PluginUpdatePolicy.isUpdateAvailable(
                release,
                installed,
                true,
                UpdateCatalog.CHANNEL_DEBUG,
                "a".repeat(64)
        ));
        assertFalse(PluginUpdatePolicy.isUpdateAvailable(
                release,
                installed,
                true,
                UpdateCatalog.CHANNEL_DEBUG,
                "b".repeat(64)
        ));
    }

    @Test
    public void verifiedPluginCanSwitchBetweenChannels() throws Exception {
        UpdateCatalog.PluginRelease release = debugRelease("c");

        assertTrue(PluginUpdatePolicy.isUpdateAvailable(
                release,
                descriptor(9),
                true,
                UpdateCatalog.CHANNEL_RELEASE,
                "c".repeat(64)
        ));
    }

    @Test
    public void localImportStillRequiresHigherVersionCode() throws Exception {
        UpdateCatalog.PluginRelease release = debugRelease("d");

        assertFalse(PluginUpdatePolicy.isUpdateAvailable(
                release,
                descriptor(7),
                false,
                "",
                ""
        ));
        assertTrue(PluginUpdatePolicy.isUpdateAvailable(
                release,
                descriptor(6),
                false,
                "",
                ""
        ));
    }

    private static UpdateCatalog.PluginRelease debugRelease(String digestCharacter) throws Exception {
        return UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"debug\","
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"1.0.0\","
                + "\"versionCode\":7,"
                + "\"commitSha\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"releaseUrl\":\"https://example.test/debug\","
                + "\"downloadUrl\":\"https://example.test/plugin\","
                + "\"size\":1,"
                + "\"sha256\":\"" + digestCharacter.repeat(64) + "\""
                + "}]}").plugins.get(0);
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
}
