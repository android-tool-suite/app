package com.androidtoolsuite.runtime.contract;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RuntimePluginManifestTest {
    @Test
    public void parsesValidWebManifest() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(fixture("manifest-valid-web.json"));

        assertEquals("sample.hello_web", manifest.plugin.id);
        assertEquals("1.0.0", manifest.plugin.version);
        assertEquals("ui/main.json", manifest.defaultUiEntry().entry);
        assertEquals("tool", manifest.plugin.kind);
        assertTrue(manifest.platforms.contains("android"));
    }

    @Test
    public void rejectsUnknownFields() throws Exception {
        String raw = fixture("manifest-valid-web.json").replace(
                "\"formatVersion\": 3,",
                "\"formatVersion\": 3, \"surprise\": true,"
        );

        ContractException error = assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(raw));

        assertTrue(error.getMessage().contains("未知字段"));
    }

    @Test
    public void rejectsMissingUiReference() throws Exception {
        String raw = fixture("manifest-valid-web.json").replace(
                "\"uiEntry\": \"main\"",
                "\"uiEntry\": \"missing\""
        );

        ContractException error = assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(raw));

        assertTrue(error.getMessage().contains("不存在的 UI entry"));
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        String raw = fixture("manifest-valid-web.json").replace("ui/main.json", "ui/../main.json");

        assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(raw));
    }

    @Test
    public void rejectsNumericStringsAndFractionalIntegers() throws Exception {
        String numericString = fixture("manifest-valid-web.json").replace(
                "\"versionCode\": 1",
                "\"versionCode\": \"1\""
        );
        String fractional = fixture("manifest-valid-web.json").replace(
                "\"versionCode\": 1",
                "\"versionCode\": 1.5"
        );

        assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(numericString));
        assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(fractional));
    }

    @Test
    public void parsesDatasetAndBackgroundTaskPolicies() throws Exception {
        String raw = fixture("manifest-valid-web.json")
                .replace("\"requires\": { \"plugins\": [], \"capabilities\": [] }", "\"requires\": { \"plugins\": [], \"capabilities\": [{"
                        + "\"id\":\"scheduler\",\"version\":\"^1.0.0\","
                        + "\"optional\":false,\"scopes\":{}}] }")
                .replace("\"background\": []", "\"background\": [{"
                        + "\"id\":\"refresh-worker\",\"type\":\"javascript-worker\","
                        + "\"entry\":\"workers/refresh.js\",\"required\":false,"
                        + "\"timeoutMs\":15000,\"maxHeapBytes\":8388608}]")
                .replace("\"datasets\": [],", "\"datasets\": [{"
                        + "\"id\":\"settings\",\"title\":\"Settings\",\"category\":\"settings\","
                        + "\"formatVersion\":1,\"sensitive\":false,\"mediaType\":\"application/json\","
                        + "\"validator\":\"json\",\"maxBytes\":131072,"
                        + "\"restoreModes\":[\"replace\"],\"dependsOn\":[]}],")
                .replace("\"tasks\": []", "\"tasks\": [{"
                        + "\"id\":\"refresh\",\"backgroundEntry\":\"refresh-worker\","
                        + "\"triggers\":[{\"type\":\"periodic\",\"intervalMinutes\":30}],"
                        + "\"constraints\":{\"network\":\"connected\",\"batteryNotLow\":true},"
                        + "\"concurrency\":{\"policy\":\"forbid\"},"
                        + "\"retry\":{\"maxAttempts\":3,\"initialBackoffMs\":10000}}]");

        RuntimePluginManifest manifest = RuntimePluginManifest.parse(raw);

        assertEquals("json", manifest.datasets.get(0).validator);
        assertEquals(131072, manifest.datasets.get(0).maxBytes);
        assertEquals(30, manifest.tasks.get(0).triggers.get(0).intervalMinutes);
        assertEquals("connected", manifest.tasks.get(0).constraints.network);
    }

    @Test
    public void rejectsDatasetDependencyCycle() throws Exception {
        String raw = fixture("manifest-valid-web.json").replace(
                "\"datasets\": [],",
                "\"datasets\":["
                        + "{\"id\":\"a\",\"title\":\"A\",\"category\":\"data\",\"formatVersion\":1,"
                        + "\"sensitive\":false,\"restoreModes\":[\"replace\"],\"dependsOn\":[\"b\"]},"
                        + "{\"id\":\"b\",\"title\":\"B\",\"category\":\"data\",\"formatVersion\":1,"
                        + "\"sensitive\":false,\"restoreModes\":[\"replace\"],\"dependsOn\":[\"a\"]}],"
        );

        ContractException error = assertThrows(ContractException.class, () -> RuntimePluginManifest.parse(raw));

        assertTrue(error.getMessage().contains("形成循环"));
    }

    @Test
    public void acceptsProviderOnlyTrustedPackage() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(providerManifest(false));

        assertEquals("trusted-provider", manifest.plugin.kind);
        assertEquals(1, manifest.providerEntries.size());
        assertTrue(manifest.uiEntries.isEmpty());
    }

    @Test
    public void acceptsTrustedProviderWithToolUi() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(providerManifest(true));

        assertEquals("trusted-provider", manifest.plugin.kind);
        assertEquals(1, manifest.providerEntries.size());
        assertEquals(1, manifest.toolContributions.size());
    }

    @Test
    public void acceptsOrdinaryPluginCapabilityFromRequiredWorker() throws Exception {
        String raw = fixture("manifest-valid-web.json")
                .replace("\"background\": []", "\"background\":[{"
                        + "\"id\":\"echo-provider\",\"type\":\"javascript-worker\","
                        + "\"entry\":\"workers/echo.js\",\"required\":true}]")
                .replace("\"provides\": { \"capabilities\": [] }", "\"provides\":{\"capabilities\":[{"
                        + "\"id\":\"sample.echo\",\"version\":\"1.0.0\","
                        + "\"workerEntry\":\"echo-provider\",\"methods\":[\"sample.echo.call\"]}]}"
                )
                .replace("\"requires\": { \"plugins\": [], \"capabilities\": [] }", "\"requires\":{"
                        + "\"plugins\":[],\"capabilities\":[{\"id\":\"sample.echo\","
                        + "\"version\":\"^1.0.0\",\"optional\":false,\"scopes\":{}}]}")
                .replace("\"homeWidgets\": []", "\"homeWidgets\":[{\"id\":\"echo\","
                        + "\"title\":\"Echo\",\"template\":\"status\","
                        + "\"dataSource\":\"sample.echo.call\",\"sizes\":[\"2x1\"]}]");

        RuntimePluginManifest manifest = RuntimePluginManifest.parse(raw);

        assertEquals("echo-provider", manifest.capabilityContributions.get(0).workerEntry);
        assertEquals("sample.echo.call", manifest.capabilityContributions.get(0).methods.get(0));
        assertEquals("sample.echo.call", manifest.homeWidgetContributions.get(0).dataSource);
        assertEquals(24, manifest.plugin.minAndroidApi);
    }

    @Test
    public void parsesExplicitMinimumAndroidApi() throws Exception {
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(
                fixture("manifest-valid-web.json").replace(
                        "\"minHostVersionCode\": 23,",
                        "\"minHostVersionCode\": 23,\"minAndroidApi\":26,"
                )
        );
        assertEquals(26, manifest.plugin.minAndroidApi);
    }

    private static String providerManifest(boolean mixedUi) {
        String ui = mixedUi
                ? "[{\"id\":\"main\",\"type\":\"declarative\",\"entry\":\"ui/main.json\"}]"
                : "[]";
        String tools = mixedUi ? "[{\"id\":\"main\",\"uiEntry\":\"main\"}]" : "[]";
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"sample.provider\",\"title\":\"Provider\","
                + "\"description\":\"Trusted provider fixture\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"sample\","
                + "\"kind\":\"trusted-provider\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":" + ui + ",\"background\":[],\"providers\":[{"
                + "\"id\":\"main\",\"type\":\"android-dex\",\"code\":\"android/provider.apk\","
                + "\"entryClass\":\"sample.Provider\",\"activation\":\"cold-start\"}]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[]},"
                + "\"provides\":{\"capabilities\":[{\"id\":\"sample.capability\","
                + "\"version\":\"1.0.0\",\"providerEntry\":\"main\","
                + "\"methods\":[\"sample.capability.call\"]}]},"
                + "\"contributes\":{\"tools\":" + tools + ",\"homeWidgets\":[]},"
                + "\"datasets\":[],\"tasks\":[]}";
    }

    private static String fixture(String name) throws IOException {
        try (InputStream input = RuntimePluginManifestTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IOException("Missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
