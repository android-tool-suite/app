package com.androidtoolsuite.app.plugin.migration;

import android.app.Activity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Temporary bridge between the in-process v1 runtime and .atsbackup v2.
 *
 * <p>Export implementations must never mutate or delete legacy data. Import implementations
 * should validate the complete Dataset before replacing data, and should use the Dataset's
 * declared restore mode. Supplied streams are owned by the Host and must not be closed by the
 * plugin.</p>
 */
public interface LegacyDataBridge {
    List<LegacyDatasetDescriptor> datasets(Activity activity) throws IOException;

    void exportDataset(Activity activity, String datasetId, OutputStream output) throws IOException;

    /**
     * Returns whether this bridge can restore the specified Dataset format.
     *
     * <p>The default keeps existing export-only plugins binary compatible.</p>
     */
    default boolean supportsImport(String datasetId, int dataFormatVersion) {
        return false;
    }

    /**
     * Restores one already authenticated and staged Dataset.
     *
     * <p>The Host verifies the complete archive before invoking this method. Implementations must
     * still validate Dataset-specific structure and throw before committing invalid data.</p>
     */
    default void importDataset(
            Activity activity,
            String datasetId,
            int dataFormatVersion,
            InputStream input
    ) throws IOException {
        throw new IOException("Dataset import is not supported: " + datasetId);
    }
}
