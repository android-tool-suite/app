package com.androidtoolsuite.app.plugin.migration;

import android.app.Activity;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Temporary read-only bridge from the in-process v1 runtime to .atsbackup v2.
 *
 * <p>Implementations must never mutate or delete legacy data while exporting. The supplied
 * stream must be written incrementally and must not be closed by the plugin.</p>
 */
public interface LegacyDataBridge {
    List<LegacyDatasetDescriptor> datasets(Activity activity) throws IOException;

    void exportDataset(Activity activity, String datasetId, OutputStream output) throws IOException;
}
