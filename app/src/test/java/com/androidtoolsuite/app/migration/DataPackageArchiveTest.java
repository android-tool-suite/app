package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.androidtoolsuite.app.migration.DatasetCategory;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.DatasetDescriptor;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DataPackageArchiveTest {
    @Test
    public void mixedPlainAndPasswordSectionsRoundTrip() throws Exception {
        byte[] settings = "plain settings".getBytes(StandardCharsets.UTF_8);
        byte[] secret = "protected secret".getBytes(StandardCharsets.UTF_8);
        byte[] archive = write(List.of(
                source("sample", "Sample", "settings", DatasetCategory.SETTINGS,
                        false, DataPackageArchive.Protection.NONE, settings),
                source("sample", "Sample", "secret", DatasetCategory.SECRET,
                        true, DataPackageArchive.Protection.PASSWORD, secret)
        ), "12345678".toCharArray());

        String raw = new String(archive, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("protected secret"));
        DataPackageArchive.ReadResult inspection = DataPackageArchive.inspect(
                new ByteArrayInputStream(archive)
        );
        assertEquals(2, inspection.items.size());
        assertTrue(inspection.hasProtection(DataPackageArchive.Protection.PASSWORD));

        Map<String, byte[]> restored = new HashMap<>();
        DataPackageArchive.read(
                new ByteArrayInputStream(archive),
                "12345678".toCharArray(),
                Set.of("sample/settings", "sample/secret"),
                (item, input) -> restored.put(item.key(), readAll(input))
        );
        assertArrayEquals(settings, restored.get("sample/settings"));
        assertArrayEquals(secret, restored.get("sample/secret"));
    }

    @Test
    public void plainSubsetCanRestoreWithoutPassword() throws Exception {
        byte[] archive = write(List.of(
                source("sample", "Sample", "settings", DatasetCategory.SETTINGS,
                        false, DataPackageArchive.Protection.NONE, new byte[]{1, 2, 3}),
                source("sample", "Sample", "secret", DatasetCategory.SECRET,
                        true, DataPackageArchive.Protection.PASSWORD, new byte[]{4, 5, 6})
        ), "12345678".toCharArray());

        Map<String, byte[]> restored = new HashMap<>();
        DataPackageArchive.read(
                new ByteArrayInputStream(archive),
                new char[0],
                Set.of("sample/settings"),
                (item, input) -> restored.put(item.key(), readAll(input))
        );
        assertEquals(Set.of("sample/settings"), restored.keySet());
    }

    @Test
    public void sensitiveItemMayBeExplicitlyPlain() throws Exception {
        byte[] secret = "user accepted plaintext".getBytes(StandardCharsets.UTF_8);
        byte[] archive = write(List.of(source(
                "sample",
                "Sample",
                "secret",
                DatasetCategory.SECRET,
                true,
                DataPackageArchive.Protection.NONE,
                secret
        )), new char[0]);

        Map<String, byte[]> restored = new HashMap<>();
        DataPackageArchive.read(
                new ByteArrayInputStream(archive),
                new char[0],
                Set.of("sample/secret"),
                (item, input) -> restored.put(item.key(), readAll(input))
        );
        assertArrayEquals(secret, restored.get("sample/secret"));
    }

    @Test
    public void wrongPasswordCannotRestoreProtectedSection() throws Exception {
        byte[] archive = write(List.of(source(
                "sample",
                "Sample",
                "secret",
                DatasetCategory.SECRET,
                true,
                DataPackageArchive.Protection.PASSWORD,
                bytes(8192)
        )), "12345678".toCharArray());

        assertThrows(IOException.class, () -> DataPackageArchive.read(
                new ByteArrayInputStream(archive),
                "87654321".toCharArray(),
                Set.of("sample/secret"),
                (item, input) -> readAll(input)
        ));
    }

    @Test
    public void onePackageCanCarryIndependentHostItemAndPluginData() throws Exception {
        byte[] host = "host settings".getBytes(StandardCharsets.UTF_8);
        byte[] data = "plugin data".getBytes(StandardCharsets.UTF_8);
        DataPackageArchive.ItemSource hostSource = new DataPackageArchive.ItemSource(
                "android_tool_suite",
                "Android Tool Suite",
                DataPackageArchive.ItemKind.HOST_SETTINGS,
                new DatasetDescriptor(
                        "app-settings",
                        "应用设置",
                        DatasetCategory.SETTINGS,
                        host.length,
                        1,
                        false,
                        List.of(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE)
                ),
                DataPackageArchive.Protection.NONE,
                output -> output.write(host)
        );
        byte[] archive = write(List.of(
                hostSource,
                source("sample", "Sample", "records", DatasetCategory.DATA,
                        false, DataPackageArchive.Protection.NONE, data)
        ), new char[0]);

        Map<String, byte[]> restored = new HashMap<>();
        DataPackageArchive.ReadResult result = DataPackageArchive.read(
                new ByteArrayInputStream(archive),
                new char[0],
                null,
                (item, input) -> restored.put(item.key(), readAll(input))
        );
        assertArrayEquals(host, restored.get("android_tool_suite/app-settings"));
        assertArrayEquals(data, restored.get("sample/records"));
        assertEquals(
                List.of(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE),
                result.items.get(0).descriptor.restoreModes
        );
    }

    @Test
    public void accountProtectionIsReservedUntilProviderExists() {
        assertThrows(IOException.class, () -> write(List.of(source(
                "sample",
                "Sample",
                "secret",
                DatasetCategory.SECRET,
                true,
                DataPackageArchive.Protection.ACCOUNT,
                new byte[]{1}
        )), new char[0]));
    }

    private static byte[] write(List<DataPackageArchive.ItemSource> items, char[] password)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataPackageArchive.write(output, new DataPackageArchive.WriteRequest(
                "com.androidtoolsuite.app",
                "data-package-test",
                19,
                items,
                password
        ));
        return output.toByteArray();
    }

    private static DataPackageArchive.ItemSource source(
            String ownerId,
            String ownerName,
            String id,
            DatasetCategory category,
            boolean sensitive,
            DataPackageArchive.Protection protection,
            byte[] bytes
    ) {
        return new DataPackageArchive.ItemSource(
                ownerId,
                ownerName,
                DataPackageArchive.ItemKind.PLUGIN_DATA,
                descriptor(id, category, sensitive),
                protection,
                output -> output.write(bytes)
        );
    }

    private static DatasetDescriptor descriptor(
            String id,
            DatasetCategory category,
            boolean sensitive
    ) {
        return new DatasetDescriptor(
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
        for (int index = 0; index < result.length; index++) result[index] = (byte) (index * 29 + 3);
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
