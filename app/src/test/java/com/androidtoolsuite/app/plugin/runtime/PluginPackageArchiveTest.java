package com.androidtoolsuite.app.plugin.runtime;

import com.androidtoolsuite.runtime.contract.ContractException;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginPackageArchiveTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void extractsVerifiedDeclarativeWebViewPackage() throws Exception {
        byte[] packageBytes = packageBytes(Map.of(
                "manifest.json", manifest().getBytes(StandardCharsets.UTF_8),
                "ui/main.json", "{\"formatVersion\":1,\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                "web/index.html", "<!doctype html><title>Test</title>".getBytes(StandardCharsets.UTF_8)
        ));
        File target = new File(temporary.getRoot(), "verified");

        PluginPackageArchive.VerifiedPackage verified = PluginPackageArchive.extract(packageBytes, target);

        assertEquals("sample.test_tool", verified.manifest.plugin.id);
        assertEquals(64, verified.packageSha256.length());
        assertTrue(new File(target, "web/index.html").isFile());
        assertFalse(verified.hasSignature);
    }

    @Test
    public void rejectsNativePayloadHiddenInsideOrdinaryTool() throws Exception {
        byte[] packageBytes = packageBytes(Map.of(
                "manifest.json", manifest().getBytes(StandardCharsets.UTF_8),
                "ui/main.json", "{\"formatVersion\":1,\"body\":{\"type\":\"webview\",\"entry\":\"web/index.html\"}}"
                        .getBytes(StandardCharsets.UTF_8),
                "web/index.html", "<!doctype html><title>Test</title>".getBytes(StandardCharsets.UTF_8),
                "android/provider.apk", new byte[]{1, 2, 3}
        ));

        ContractException error = assertThrows(
                ContractException.class,
                () -> PluginPackageArchive.extract(
                        packageBytes,
                        new File(temporary.getRoot(), "hidden-provider")
                )
        );

        assertTrue(error.getMessage().contains("不得夹带"));
    }

    @Test
    public void probesNumericFormatV3WithoutTreatingLegacyStringAsV3() throws Exception {
        byte[] v3 = rawZip(Map.of("manifest.json", manifest().getBytes(StandardCharsets.UTF_8)));
        byte[] legacy = rawZip(Map.of(
                "manifest.json",
                "{\"format\":\"ats-plugin\",\"formatVersion\":\"3\"}".getBytes(StandardCharsets.UTF_8)
        ));

        assertTrue(PluginPackageArchive.hasFormatV3Manifest(v3));
        assertFalse(PluginPackageArchive.hasFormatV3Manifest(legacy));
    }

    @Test
    public void rejectsTamperedPayloadAndRemovesStaging() throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest().getBytes(StandardCharsets.UTF_8));
        files.put("web/index.html", "original".getBytes(StandardCharsets.UTF_8));
        byte[] valid = packageBytes(files);
        byte[] tampered = rewriteEntry(valid, "web/index.html", "changed".getBytes(StandardCharsets.UTF_8));
        File target = new File(temporary.getRoot(), "tampered");

        assertThrows(ContractException.class, () -> PluginPackageArchive.extract(tampered, target));
        assertFalse(target.exists());
    }

    @Test
    public void rejectsCaseFoldedDuplicatePaths() throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest().getBytes(StandardCharsets.UTF_8));
        files.put("web/index.html", "valid".getBytes(StandardCharsets.UTF_8));
        files.put("web/App.js", "one".getBytes(StandardCharsets.UTF_8));
        files.put("web/app.js", "two".getBytes(StandardCharsets.UTF_8));
        byte[] packageBytes = packageBytes(files);

        assertThrows(
                ContractException.class,
                () -> PluginPackageArchive.extract(packageBytes, new File(temporary.getRoot(), "duplicate"))
        );
    }

    @Test
    public void rejectsTraversalBeforeWritingOutsideTarget() throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest().getBytes(StandardCharsets.UTF_8));
        files.put("web/index.html", "valid".getBytes(StandardCharsets.UTF_8));
        files.put("web/../escape.txt", "bad".getBytes(StandardCharsets.UTF_8));
        byte[] packageBytes = rawZip(files);
        File target = new File(temporary.getRoot(), "traversal");

        assertThrows(ContractException.class, () -> PluginPackageArchive.extract(packageBytes, target));
        assertFalse(new File(temporary.getRoot(), "escape.txt").exists());
    }

    private static String manifest() {
        return """
                {
                  "format":"ats-plugin",
                  "formatVersion":3,
                  "plugin":{
                    "id":"sample.test_tool",
                    "title":"测试工具",
                    "description":"用于归档验证测试。",
                    "version":"1.0.0",
                    "versionCode":1,
                    "minHostVersionCode":23,
                    "publisher":"android_tool_suite.tests",
                    "kind":"tool"
                  },
                  "platforms":["android"],
                  "runtime":{
                    "ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}],
                    "background":[],
                    "providers":[]
                  },
                  "requires":{"plugins":[],"capabilities":[]},
                  "provides":{"capabilities":[]},
                  "contributes":{"tools":[{"id":"main","uiEntry":"main"}],"homeWidgets":[]},
                  "datasets":[],
                  "tasks":[]
                }
                """;
    }

    private static byte[] packageBytes(Map<String, byte[]> payload) throws Exception {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>(payload.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        JSONArray integrityFiles = new JSONArray();
        for (Map.Entry<String, byte[]> entry : entries) {
            integrityFiles.put(new JSONObject()
                    .put("path", entry.getKey())
                    .put("size", entry.getValue().length)
                    .put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        }
        LinkedHashMap<String, byte[]> all = new LinkedHashMap<>(payload);
        all.put("META-INF/ats-integrity.json", new JSONObject()
                .put("algorithm", "sha256")
                .put("files", integrityFiles)
                .put("formatVersion", 1)
                .toString()
                .getBytes(StandardCharsets.UTF_8));
        return rawZip(all);
    }

    private static byte[] rewriteEntry(byte[] original, String path, byte[] replacement) throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        try (java.util.zip.ZipInputStream input = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(original)
        )) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries.put(entry.getName(), entry.getName().equals(path) ? replacement : input.readAllBytes());
            }
        }
        return rawZip(entries);
    }

    private static byte[] rawZip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item >>> 4) & 0xf, 16));
            value.append(Character.forDigit(item & 0xf, 16));
        }
        return value.toString();
    }
}
