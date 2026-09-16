package com.androidtoolsuite.app.migration;

import android.app.Activity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Host-private adapter used only for current format v3 Dataset data management. */
public interface DatasetBridge {
    List<DatasetDescriptor> datasets(Activity activity) throws IOException;
    void exportDataset(Activity activity, String datasetId, OutputStream output) throws IOException;
    default boolean supportsImport(String datasetId, int dataFormatVersion) { return false; }
    default boolean hasData(Activity activity, String datasetId) throws IOException { return true; }
    default boolean supportsRestoreMode(String datasetId, int dataFormatVersion, DatasetRestoreMode mode) {
        return supportsImport(datasetId, dataFormatVersion);
    }
    default void importDataset(Activity activity, String datasetId, int dataFormatVersion, InputStream input)
            throws IOException {
        throw new IOException("Dataset import is not supported: " + datasetId);
    }
    default void importDataset(Activity activity, String datasetId, int dataFormatVersion,
            DatasetRestoreMode restoreMode, InputStream input) throws IOException {
        if (!supportsRestoreMode(datasetId, dataFormatVersion, restoreMode)) {
            throw new IOException("Dataset restore mode is not supported: " + datasetId);
        }
        importDataset(activity, datasetId, dataFormatVersion, input);
    }
    default boolean supportsDelete(String datasetId) { return false; }
    default void deleteDataset(Activity activity, String datasetId) throws IOException {
        throw new IOException("Dataset deletion is not supported: " + datasetId);
    }
}
