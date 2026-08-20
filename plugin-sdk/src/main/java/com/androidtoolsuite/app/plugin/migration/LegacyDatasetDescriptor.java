package com.androidtoolsuite.app.plugin.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Description of one legacy dataset that can be exported by a Migration Bridge plugin.
 *
 * <p>The id is stable within a plugin and becomes part of the backup archive path.</p>
 */
public final class LegacyDatasetDescriptor {
    public final String id;
    public final String name;
    public final DatasetCategory category;
    public final long estimatedSize;
    public final int dataFormatVersion;
    public final boolean sensitive;
    /** Preferred restore mode, kept for Bridge v2 packages. */
    public final DatasetRestoreMode restoreMode;
    /** Restore modes that the exporting plugin declares for this Dataset format. */
    public final List<DatasetRestoreMode> restoreModes;
    public final List<String> dependencies;

    public LegacyDatasetDescriptor(
            String id,
            String name,
            DatasetCategory category,
            long estimatedSize,
            int dataFormatVersion,
            boolean sensitive,
            DatasetRestoreMode restoreMode,
            List<String> dependencies
    ) {
        this(id, name, category, estimatedSize, dataFormatVersion, sensitive,
                Collections.singletonList(restoreMode), dependencies);
    }

    public LegacyDatasetDescriptor(
            String id,
            String name,
            DatasetCategory category,
            long estimatedSize,
            int dataFormatVersion,
            boolean sensitive,
            List<DatasetRestoreMode> restoreModes,
            List<String> dependencies
    ) {
        this.id = requireId(id, "dataset id");
        this.name = requireText(name, "dataset name");
        this.category = Objects.requireNonNull(category, "category");
        this.estimatedSize = Math.max(0L, estimatedSize);
        if (dataFormatVersion <= 0) {
            throw new IllegalArgumentException("dataFormatVersion must be positive");
        }
        this.dataFormatVersion = dataFormatVersion;
        this.sensitive = sensitive || category == DatasetCategory.SECRET;
        ArrayList<DatasetRestoreMode> copiedModes = new ArrayList<>();
        if (restoreModes != null) {
            for (DatasetRestoreMode mode : restoreModes) {
                DatasetRestoreMode checked = Objects.requireNonNull(mode, "restoreMode");
                if (!copiedModes.contains(checked)) copiedModes.add(checked);
            }
        }
        if (copiedModes.isEmpty()) {
            throw new IllegalArgumentException("restoreModes must not be empty");
        }
        this.restoreModes = Collections.unmodifiableList(copiedModes);
        this.restoreMode = copiedModes.get(0);
        ArrayList<String> copied = new ArrayList<>();
        if (dependencies != null) {
            for (String dependency : dependencies) {
                copied.add(requireId(dependency, "dataset dependency"));
            }
        }
        this.dependencies = Collections.unmodifiableList(copied);
    }

    public LegacyDatasetDescriptor(
            String id,
            String name,
            DatasetCategory category,
            long estimatedSize,
            int dataFormatVersion,
            boolean sensitive,
            List<DatasetRestoreMode> restoreModes
    ) {
        this(id, name, category, estimatedSize, dataFormatVersion, sensitive, restoreModes,
                Collections.emptyList());
    }

    public LegacyDatasetDescriptor(
            String id,
            String name,
            DatasetCategory category,
            long estimatedSize,
            int dataFormatVersion,
            boolean sensitive,
            DatasetRestoreMode restoreMode
    ) {
        this(id, name, category, estimatedSize, dataFormatVersion, sensitive, restoreMode,
                Collections.emptyList());
    }

    public boolean supportsRestoreMode(DatasetRestoreMode mode) {
        return restoreModes.contains(mode);
    }

    private static String requireId(String value, String label) {
        String clean = requireText(value, label);
        if (!clean.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(label + " contains unsupported characters");
        }
        return clean;
    }

    private static String requireText(String value, String label) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return clean;
    }
}
