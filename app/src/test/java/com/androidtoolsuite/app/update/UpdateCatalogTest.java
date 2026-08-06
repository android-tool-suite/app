package com.androidtoolsuite.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
}
