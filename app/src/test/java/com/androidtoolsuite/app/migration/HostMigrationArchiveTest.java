package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class HostMigrationArchiveTest {
    @Test
    public void roundTripsHostSettingsAndPlugins() throws Exception {
        HostMigrationArchive.Snapshot source = new HostMigrationArchive.Snapshot(
                "com.androidtoolsuite.app",
                "1.3.1",
                13,
                new JSONObject().put("pluginRepositoryChannel", "debug"),
                Set.of("shizuku_auth"),
                List.of(new HostMigrationArchive.PluginEntry(
                        "sample-plugin", true, "plugin-package".getBytes(StandardCharsets.UTF_8)
                ))
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HostMigrationArchive.write(output, source);

        HostMigrationArchive.Snapshot restored = HostMigrationArchive.read(output.toByteArray());

        assertEquals("com.androidtoolsuite.app", restored.sourcePackage);
        assertEquals("debug", restored.host.getString("pluginRepositoryChannel"));
        assertTrue(restored.builtInEnabledIds.contains("shizuku_auth"));
        assertEquals("sample-plugin", restored.plugins.get(0).id);
        assertTrue(restored.plugins.get(0).enabled);
        assertArrayEquals(source.plugins.get(0).packageBytes, restored.plugins.get(0).packageBytes);
    }

    @Test(expected = IOException.class)
    public void rejectsPathTraversal() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../migration.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        HostMigrationArchive.read(output.toByteArray());
    }

    @Test(expected = IOException.class)
    public void rejectsUndeclaredPluginFile() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("migration.json"));
            zip.write(("{\"schemaVersion\":1,"
                    + "\"type\":\"android-tool-suite-migration\","
                    + "\"sourcePackage\":\"com.androidtoolsuite.app\","
                    + "\"sourceVersionName\":\"1.3.1\","
                    + "\"sourceVersionCode\":13,"
                    + "\"host\":{},\"plugins\":[]}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("plugins/extra.atsplugin"));
            zip.write(new byte[]{1});
            zip.closeEntry();
        }
        HostMigrationArchive.read(output.toByteArray());
    }

    @Test(expected = IOException.class)
    public void rejectsOversizedPluginEntryBeforeAllocationGrowsWithoutBound() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024 * 1024];
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("plugins/oversized.atsplugin"));
            for (int index = 0; index <= 128; index++) {
                zip.write(chunk);
            }
            zip.closeEntry();
        }
        HostMigrationArchive.read(output.toByteArray());
    }
}
