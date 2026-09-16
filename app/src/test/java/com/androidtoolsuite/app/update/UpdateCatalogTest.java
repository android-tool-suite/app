package com.androidtoolsuite.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.Test;

public final class UpdateCatalogTest {
    @Test
    public void parsesSignedIndexPayloadShape() throws Exception {
        UpdateCatalog catalog = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"generatedAt\":\"2026-07-24T00:00:00Z\","
                + "\"app\":{"
                + "\"packageName\":\"com.androidtoolsuite.app\","
                + "\"versionName\":\"1.2.0\","
                + "\"versionCode\":11,"
                + "\"minSdk\":24,"
                + "\"releaseUrl\":\"https://example.test/app/release\","
                + "\"downloadUrl\":\"https://example.test/app.apk\","
                + "\"size\":42,"
                + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
                + "},"
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"description\":\"Sample plugin\","
                + "\"author\":\"ATS\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"2.0.0\","
                + "\"versionCode\":3,"
                + "\"minHostVersionCode\":11,"
                + "\"minAndroidApi\":26,"
                + "\"sdkVersion\":\"1.1.0\","
                + "\"dependencies\":[\"shizuku_auth\"],"
                + "\"releaseUrl\":\"https://example.test/plugin/release\","
                + "\"downloadUrl\":\"https://example.test/plugin.atsplugin\","
                + "\"size\":84,"
                + "\"sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\""
                + "}]"
                + "}");

        assertNotNull(catalog.app);
        assertEquals(UpdateCatalog.CHANNEL_RELEASE, catalog.channel);
        assertEquals(11, catalog.app.versionCode);
        assertEquals(1, catalog.plugins.size());
        assertEquals("sample", catalog.plugins.get(0).id);
        assertEquals(11, catalog.plugins.get(0).minHostVersionCode);
        assertEquals(26, catalog.plugins.get(0).minAndroidApi);
        assertEquals("shizuku_auth", catalog.plugins.get(0).dependencies.iterator().next());
    }

    @Test(expected = JSONException.class)
    public void rejectsUnknownSchema() throws Exception {
        UpdateCatalog.parse("{\"schemaVersion\":2,\"plugins\":[]}");
    }

    @Test(expected = JSONException.class)
    public void rejectsMalformedDigest() throws Exception {
        UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"1.0.0\","
                + "\"versionCode\":1,"
                + "\"releaseUrl\":\"https://example.test/release\","
                + "\"downloadUrl\":\"https://example.test/plugin\","
                + "\"size\":1,"
                + "\"sha256\":\"bad\""
                + "}]"
                + "}");
    }

    @Test
    public void parsesDebugChannelAndCombinesCatalogs() throws Exception {
        UpdateCatalog release = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"release\","
                + "\"app\":{"
                + "\"packageName\":\"com.androidtoolsuite.app\","
                + "\"versionName\":\"1.3.0\","
                + "\"versionCode\":12,"
                + "\"minSdk\":24,"
                + "\"releaseUrl\":\"https://example.test/app/release\","
                + "\"downloadUrl\":\"https://example.test/app.apk\","
                + "\"size\":42,"
                + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
                + "},\"plugins\":[]}");
        UpdateCatalog debug = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"debug\","
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"2.1.0\","
                + "\"versionCode\":4,"
                + "\"commitSha\":\"0123456789abcdef0123456789abcdef01234567\","
                + "\"releaseUrl\":\"https://example.test/debug\","
                + "\"downloadUrl\":\"https://example.test/plugin.atsplugin\","
                + "\"size\":84,"
                + "\"sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\""
                + "}]}");

        UpdateCatalog combined = UpdateCatalog.combine(release, debug);

        assertEquals(UpdateCatalog.CHANNEL_DEBUG, combined.channel);
        assertNotNull(combined.app);
        assertEquals(12, combined.app.versionCode);
        assertEquals("0123456", combined.plugins.get(0).commitSha.substring(0, 7));
    }

    @Test(expected = JSONException.class)
    public void rejectsDebugEntryWithoutCommitSha() throws Exception {
        UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"debug\","
                + "\"plugins\":[{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"1.0.0\","
                + "\"versionCode\":1,"
                + "\"releaseUrl\":\"https://example.test/release\","
                + "\"downloadUrl\":\"https://example.test/plugin\","
                + "\"size\":1,"
                + "\"sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\""
                + "}]}");
    }

    @Test
    public void parsesHistoricalPluginVersionsAndDataCompatibility() throws Exception {
        UpdateCatalog catalog = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"channel\":\"release\","
                + "\"app\":{\"title\":\"ATS\",\"versions\":[]},"
                + "\"plugins\":[{"
                + "\"id\":\"sample\",\"title\":\"Sample\",\"versions\":["
                + pluginVersionJson("2.0.0", 2, 2, 1, 2) + ","
                + pluginVersionJson("1.0.0", 1, 1, 1, 1)
                + "]}]}"
        );

        assertEquals(1, catalog.plugins.size());
        assertEquals(2, catalog.versionsForPlugin("sample").size());
        UpdateCatalog.PluginRelease latest = catalog.findPlugin("sample");
        assertNotNull(latest);
        assertEquals(2, latest.versionCode);
        assertTrue(latest.canReadDataFormat(1));
        assertTrue(latest.canReadDataFormat(2));
        assertFalse(catalog.versionsForPlugin("sample").get(1).canReadDataFormat(2));
    }

    @Test
    public void missingCompatibilityIsLegacyV0() throws Exception {
        UpdateCatalog.PluginRelease release = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,"
                + "\"plugins\":[{"
                + "\"id\":\"legacy\","
                + "\"title\":\"Legacy\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"1.0\","
                + "\"versionCode\":1,"
                + "\"releaseUrl\":\"https://example.test/release\","
                + "\"downloadUrl\":\"https://example.test/plugin\","
                + "\"size\":1,"
                + "\"sha256\":\"" + "a".repeat(64) + "\""
                + "}]}"
        ).plugins.get(0);

        assertFalse(release.hasDataCompatibilityDeclaration());
        assertTrue(release.canReadDataFormat(0));
        assertFalse(release.canReadDataFormat(1));
    }

    @Test
    public void latestIndexControlsDefaultWhileCatalogProvidesHistory() throws Exception {
        UpdateCatalog latest = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,\"plugins\":["
                + pluginVersionJson("1.0.0", 1, 1, 1, 1)
                + "]}"
        );
        UpdateCatalog history = UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,\"plugins\":[{"
                + "\"id\":\"sample\",\"title\":\"Sample\",\"versions\":["
                + pluginVersionJson("2.0.0", 2, 2, 1, 2) + ","
                + pluginVersionJson("1.0.0", 1, 1, 1, 1)
                + "]}]}"
        );

        UpdateCatalog combined = UpdateCatalog.combine(latest, latest, history);

        assertEquals(1, combined.findPlugin("sample").versionCode);
        assertEquals(1, combined.versionsForPlugin("sample").get(0).versionCode);
        assertEquals(2, combined.versionsForPlugin("sample").get(1).versionCode);
    }

    @Test(expected = JSONException.class)
    public void rejectsInvalidDataCompatibilityRange() throws Exception {
        UpdateCatalog.parse("{"
                + "\"schemaVersion\":1,\"plugins\":["
                + pluginVersionJson("1.0.0", 1, 2, 3, 2)
                + "]}"
        );
    }

    private static String pluginVersionJson(
            String versionName,
            int versionCode,
            int dataFormatVersion,
            int minReadable,
            int maxReadable
    ) {
        return "{"
                + "\"id\":\"sample\","
                + "\"title\":\"Sample\","
                + "\"repositoryUrl\":\"https://example.test/repo\","
                + "\"versionName\":\"" + versionName + "\","
                + "\"versionCode\":" + versionCode + ","
                + "\"releaseUrl\":\"https://example.test/release/" + versionName + "\","
                + "\"downloadUrl\":\"https://example.test/plugin/" + versionName + "\","
                + "\"size\":1,"
                + "\"sha256\":\"" + Integer.toHexString(versionCode).repeat(64) + "\","
                + "\"dataCompatibility\":{"
                + "\"schemaVersion\":1,"
                + "\"dataFormatVersion\":" + dataFormatVersion + ","
                + "\"minReadableDataFormatVersion\":" + minReadable + ","
                + "\"maxReadableDataFormatVersion\":" + maxReadable
                + "}}";
    }
}
