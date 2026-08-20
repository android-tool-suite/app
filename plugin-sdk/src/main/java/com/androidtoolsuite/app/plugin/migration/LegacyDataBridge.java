package com.androidtoolsuite.app.plugin.migration;

import android.app.Activity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Plugin-owned Dataset adapter for Android Tool Suite data packages.
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

    /** Returns whether current local storage already contains this Dataset. */
    default boolean hasData(Activity activity, String datasetId) throws IOException {
        return true;
    }

    /**
     * Returns whether the current plugin can apply the requested existing-data policy.
     *
     * <p>The archive descriptor also advertises source-side capabilities. The Host exposes only
     * modes supported by both the archive and this target bridge.</p>
     */
    default boolean supportsRestoreMode(
            String datasetId,
            int dataFormatVersion,
            DatasetRestoreMode mode
    ) {
        return supportsImport(datasetId, dataFormatVersion);
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

    /** Restores one Dataset using the user-selected existing-data policy. */
    default void importDataset(
            Activity activity,
            String datasetId,
            int dataFormatVersion,
            DatasetRestoreMode restoreMode,
            InputStream input
    ) throws IOException {
        if (!supportsRestoreMode(datasetId, dataFormatVersion, restoreMode)) {
            throw new IOException("Dataset restore mode is not supported: " + datasetId);
        }
        importDataset(activity, datasetId, dataFormatVersion, input);
    }

    /**
     * Returns whether this bridge can permanently delete the current legacy Dataset.
     *
     * <p>The default keeps existing plugins binary compatible. The Host is responsible for
     * confirmation and dependency-aware selection before invoking deletion.</p>
     */
    default boolean supportsDelete(String datasetId) {
        return false;
    }

    /** Permanently deletes one Dataset after explicit user confirmation. */
    default void deleteDataset(Activity activity, String datasetId) throws IOException {
        throw new IOException("Dataset deletion is not supported: " + datasetId);
    }
}
