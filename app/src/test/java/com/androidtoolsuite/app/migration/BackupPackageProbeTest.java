package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertEquals;

import com.androidtoolsuite.app.migration.DatasetCategory;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.DatasetDescriptor;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;

public final class BackupPackageProbeTest {
    @Test
    public void detectsAllSupportedGenerations() throws Exception {
        ByteArrayOutputStream v3 = new ByteArrayOutputStream();
        DataPackageArchive.write(v3, new DataPackageArchive.WriteRequest(
                "com.androidtoolsuite.app",
                "v3",
                19,
                List.of(new DataPackageArchive.ItemSource(
                        "sample",
                        "Sample",
                        DataPackageArchive.ItemKind.PLUGIN_DATA,
                        descriptor("data"),
                        DataPackageArchive.Protection.NONE,
                        output -> output.write(1)
                )),
                new char[0]
        ));
        assertEquals(
                BackupPackageProbe.Format.DATA_PACKAGE_V3,
                BackupPackageProbe.detect(new ByteArrayInputStream(v3.toByteArray()))
        );

        ByteArrayOutputStream v2 = new ByteArrayOutputStream();
        BackupArchiveV2.write(v2, new BackupArchiveV2.WriteRequest(
                "com.androidtoolsuite.app",
                "v2",
                18,
                List.of(new BackupArchiveV2.DatasetSource(
                        "sample",
                        descriptor("data"),
                        output -> output.write(2)
                )),
                new char[0]
        ));
        assertEquals(
                BackupPackageProbe.Format.BRIDGE_V2,
                BackupPackageProbe.detect(new ByteArrayInputStream(v2.toByteArray()))
        );

        ByteArrayOutputStream v1 = new ByteArrayOutputStream();
        HostMigrationArchive.write(v1, new HostMigrationArchive.Snapshot(
                "com.androidtoolsuite.app",
                "v1",
                17,
                new JSONObject(),
                Set.of(),
                List.of()
        ));
        assertEquals(
                BackupPackageProbe.Format.HOST_MIGRATION_V1,
                BackupPackageProbe.detect(new ByteArrayInputStream(v1.toByteArray()))
        );
    }

    private static DatasetDescriptor descriptor(String id) {
        return new DatasetDescriptor(
                id,
                id,
                DatasetCategory.DATA,
                0L,
                1,
                false,
                DatasetRestoreMode.REPLACE
        );
    }
}
