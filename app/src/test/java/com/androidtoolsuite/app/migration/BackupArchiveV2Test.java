package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.androidtoolsuite.app.plugin.migration.DatasetCategory;
import com.androidtoolsuite.app.plugin.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.plugin.migration.LegacyDatasetDescriptor;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BackupArchiveV2Test {
    @Test
    public void plainArchiveRoundTripsMultipleDatasets() throws Exception {
        byte[] settings = "{\"autoGrant\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] records = bytes(257_000);
        byte[] archive = write(
                List.of(source("accessibility_grant", descriptor(
                                "settings", DatasetCategory.SETTINGS, false), settings),
                        source("gacha_analysis", descriptor(
                                "genshin-records", DatasetCategory.DATA, false), records)),
                new char[0]
        );

        Map<String, byte[]> restored = new HashMap<>();
        BackupArchiveV2.ReadResult result = BackupArchiveV2.read(
                new ByteArrayInputStream(archive),
                new char[0],
                (dataset, input) -> restored.put(dataset.key(), readAll(input))
        );

        assertFalse(result.encrypted);
        assertEquals(2, result.datasets.size());
        assertArrayEquals(settings, restored.get("accessibility_grant/settings"));
        assertArrayEquals(records, restored.get("gacha_analysis/genshin-records"));
    }

    @Test
    public void encryptedSecretRoundTripsWithCorrectPassword() throws Exception {
        byte[] secret = "session-token-that-must-not-be-plain".getBytes(StandardCharsets.UTF_8);
        byte[] archive = write(
                List.of(source("phigros_advisor", descriptor(
                        "session-tokens", DatasetCategory.SECRET, true), secret)),
                "migration password".toCharArray()
        );
        assertFalse(new String(archive, StandardCharsets.ISO_8859_1).contains("session-token-that-must-not-be-plain"));

        List<byte[]> restored = new ArrayList<>();
        BackupArchiveV2.ReadResult result = BackupArchiveV2.read(
                new ByteArrayInputStream(archive),
                "migration password".toCharArray(),
                (dataset, input) -> restored.add(readAll(input))
        );

        assertTrue(result.encrypted);
        assertArrayEquals(secret, restored.get(0));
    }

    @Test
    public void secretRequiresPassword() {
        assertThrows(IOException.class, () -> write(
                List.of(source("gacha_analysis", descriptor(
                        "mihoyo-session", DatasetCategory.SECRET, true), new byte[]{1})),
                new char[0]
        ));
    }

    @Test
    public void wrongPasswordCannotRestoreSecret() throws Exception {
        byte[] archive = write(
                List.of(source("gacha_analysis", descriptor(
                        "mihoyo-session", DatasetCategory.SECRET, true), bytes(4096))),
                "correct password".toCharArray()
        );

        assertThrows(IOException.class, () -> BackupArchiveV2.read(
                new ByteArrayInputStream(archive),
                "wrong password".toCharArray(),
                (dataset, input) -> readAll(input)
        ));
    }

    @Test
    public void truncatedArchiveCannotRestore() throws Exception {
        byte[] archive = write(
                List.of(source("gacha_analysis", descriptor(
                        "starrail-records", DatasetCategory.DATA, false), bytes(64_000))),
                new char[0]
        );
        byte[] truncated = java.util.Arrays.copyOf(archive, archive.length / 2);

        assertThrows(IOException.class, () -> BackupArchiveV2.read(
                new ByteArrayInputStream(truncated),
                new char[0],
                (dataset, input) -> readAll(input)
        ));
    }

    private static byte[] write(List<BackupArchiveV2.DatasetSource> datasets, char[] password)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupArchiveV2.write(output, new BackupArchiveV2.WriteRequest(
                "com.androidtoolsuite.app",
                "bridge-test",
                17,
                datasets,
                password
        ));
        return output.toByteArray();
    }

    private static BackupArchiveV2.DatasetSource source(
            String pluginId,
            LegacyDatasetDescriptor descriptor,
            byte[] bytes
    ) {
        return new BackupArchiveV2.DatasetSource(pluginId, descriptor, output -> {
            int offset = 0;
            while (offset < bytes.length) {
                int count = Math.min(7919, bytes.length - offset);
                output.write(bytes, offset, count);
                offset += count;
            }
        });
    }

    private static LegacyDatasetDescriptor descriptor(
            String id,
            DatasetCategory category,
            boolean sensitive
    ) {
        return new LegacyDatasetDescriptor(
                id,
                id,
                category,
                0L,
                1,
                sensitive,
                DatasetRestoreMode.REPLACE
        );
    }

    private static byte[] bytes(int count) {
        byte[] result = new byte[count];
        for (int index = 0; index < result.length; index++) result[index] = (byte) (index * 31 + 7);
        return result;
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4093];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
