package com.androidtoolsuite.app.host

import android.app.Activity
import com.androidtoolsuite.app.migration.DataPackageArchive
import com.androidtoolsuite.app.migration.DatasetBridge
import com.androidtoolsuite.app.migration.DatasetCategory
import com.androidtoolsuite.app.migration.DatasetDescriptor
import com.androidtoolsuite.app.migration.DatasetRestoreMode
import com.androidtoolsuite.app.migration.MigrationBridgeManager
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTransferSelectionTest {
    private val bridge = object : DatasetBridge {
        override fun datasets(activity: Activity?): List<DatasetDescriptor> = emptyList()
        override fun exportDataset(activity: Activity?, datasetId: String?, output: OutputStream?) = Unit
    }

    private fun option(
        owner: String, id: String, category: DatasetCategory = DatasetCategory.DATA,
        dependencies: List<String> = emptyList(), modes: List<DatasetRestoreMode> = listOf(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE),
    ): MigrationBridgeManager.DatasetOption {
        val descriptor = DatasetDescriptor(id, "内容 $id", category, 2048, 1, category == DatasetCategory.SECRET, modes, dependencies)
        val constructor = MigrationBridgeManager.DatasetOption::class.java.getDeclaredConstructor(
            String::class.java, String::class.java, DatasetDescriptor::class.java, DatasetBridge::class.java,
            DataPackageArchive.ItemKind::class.java, DataPackageArchive.Protection::class.java, Boolean::class.javaPrimitiveType,
        )
        constructor.isAccessible = true
        return constructor.newInstance(owner, "来源 $owner", descriptor, bridge, DataPackageArchive.ItemKind.PLUGIN_DATA, DataPackageArchive.Protection.NONE, true)
    }

    @Test fun batchSelectionUsesEveryMatchingRowAndPreservesHiddenChoices() {
        val options = (0 until 125).map { option("first", "record-$it") } + option("second", "retained")
        val draft = SelectionDraft(options).apply { source = "first"; query = "record" }
        val originallySelected = setOf("second/retained")
        val visible = visibleOptions(options, draft, originallySelected)
        assertEquals(125, visible.size)
        val allCurrent = closeRestoreSelection(options, originallySelected + visible.map { it.key() })
        assertEquals(126, allCurrent.size)
        assertTrue("second/retained" in allCurrent)
        val clearedCurrent = pruneRestoreSelection(options, allCurrent - visible.map { it.key() }.toSet())
        assertEquals(originallySelected, clearedCurrent)
        draft.onlySelected = true
        assertTrue(visibleOptions(options, draft, clearedCurrent).isEmpty())
    }

    @Test fun restoreDependenciesAndDeleteDependentsAreClosedAcrossFilters() {
        val options = listOf(option("first", "account"), option("first", "records", dependencies = listOf("account")), option("other", "records"))
        val selection = closeRestoreSelection(options, setOf("first/records", "other/records"))
        assertEquals(setOf("first/account", "first/records", "other/records"), selection)
        assertEquals(setOf("other/records"), pruneRestoreSelection(options, selection - "first/account"))
        val deletion = closeDeleteSelection(options, setOf("first/account", "other/records"))
        assertEquals(selection, deletion)
        assertEquals(setOf("other/records"), pruneDeleteSelection(options, deletion - "first/records"))
    }

    @Test fun exportDefaultsToEncryptingTheWholeSelectedScopeAndKeepsPluginPackages() {
        val packageItem = MigrationBridgeManager.hostPluginPackageExportOption("local-only", "本地插件", 4000)
        val options = listOf(packageItem, option("first", "settings", DatasetCategory.SETTINGS), option("first", "records"), option("first", "login", DatasetCategory.SECRET))
        val draft = ExportDraft(options)
        assertTrue(packageItem.key() in draft.selected)
        assertEquals(draft.selected, draft.protectedKeys())
        draft.updateSelection(draft.selected + "first/login")
        assertEquals(draft.selected, draft.protectedKeys())
        draft.protection = ExportProtection.SENSITIVE
        assertEquals(setOf("first/login"), draft.protectedKeys())
        draft.customProtected = draft.protectedKeys()
        draft.protection = ExportProtection.CUSTOM
        draft.customProtected = draft.customProtected + "first/records"
        assertEquals(setOf("first/login", "first/records"), draft.protectedKeys())
        draft.updateSelection(draft.selected - "first/login")
        assertEquals(setOf("first/records"), draft.protectedKeys())
        draft.protection = ExportProtection.NONE
        assertTrue(draft.protectedKeys().isEmpty())
    }

    @Test fun importDoesNotDefaultToReplacingExistingUnmergeableData() {
        val options = listOf(option("first", "mergeable"), option("first", "credentials", DatasetCategory.SECRET, modes = listOf(DatasetRestoreMode.REPLACE)))
        val draft = ImportDraft(options)
        assertEquals(setOf("first/mergeable"), draft.selected)
        assertEquals(DatasetRestoreMode.MERGE, draft.modes["first/mergeable"])
        draft.selected = closeRestoreSelection(options, draft.selected + "first/credentials")
        assertEquals(DatasetRestoreMode.REPLACE, draft.modes["first/credentials"])
        assertEquals(2, draft.selected.size)
    }

    @Test fun deleteStartsEmptyAndChangingTheScopeInvalidatesConfirmation() {
        val options = listOf(option("first", "account"), option("first", "cache", DatasetCategory.CACHE, dependencies = listOf("account")))
        val draft = DeleteDraft(options)
        assertTrue(draft.selected.isEmpty())
        draft.updateSelection(closeDeleteSelection(options, setOf("first/account")))
        assertEquals(setOf("first/account", "first/cache"), draft.selected)
        draft.confirmed = true
        draft.updateSelection(pruneDeleteSelection(options, draft.selected - "first/cache"))
        assertTrue(draft.selected.isEmpty())
        assertFalse(draft.confirmed)
    }

    @Test fun partialDeleteRetriesOnlyRemainingItemsAndRequiresFreshAcknowledgement() {
        val options = listOf(option("first", "account"), option("first", "cache", DatasetCategory.CACHE, dependencies = listOf("account")), option("other", "records"))
        val previous = DeleteDraft(options).apply {
            updateSelection(closeDeleteSelection(options, setOf("first/account")))
            confirmed = true
            query = "account"
            navigation.reviewing = true
        }
        val remaining = DeleteDraft(options.filter { it.key() != "first/cache" })
        remaining.restoreRemaining(previous)
        assertEquals(setOf("first/account"), remaining.selected)
        assertFalse(remaining.confirmed)
        assertTrue(remaining.navigation.reviewing)
        assertEquals("account", remaining.query)
        assertFalse("other/records" in remaining.selected)
    }

    @Test fun historicalExportRetryIntersectsAvailableKeysAndProtectsNewDependencies() {
        val options = listOf(option("first", "account", DatasetCategory.SECRET), option("first", "records", dependencies = listOf("account")), option("other", "records"))
        val draft = ExportDraft(options).apply { password = "previous-private-password" }
        restoreTaskSelection(draft, setOf("first/records", "removed/old"), emptySet(), emptySet())
        assertEquals(setOf("first/account", "first/records"), draft.selected)
        assertEquals(setOf("first/account"), draft.protectedKeys())
        assertEquals("", draft.password)
        assertFalse(draft.navigation.reviewing)
    }

    @Test fun historicalImportRetryUsesOnlySupportedModesAndDeleteRetryRequiresConfirmation() {
        val options = listOf(option("first", "account", DatasetCategory.SECRET, modes = listOf(DatasetRestoreMode.REPLACE)), option("first", "records", dependencies = listOf("account")))
        val restore = ImportDraft(options).apply { password = "old-password" }
        restoreTaskSelection(restore, setOf("first/records", "first/account", "removed/old"), emptySet(), setOf("first/records", "first/account"))
        assertEquals(setOf("first/account", "first/records"), restore.selected)
        assertEquals(DatasetRestoreMode.REPLACE, restore.modes["first/account"])
        assertEquals(DatasetRestoreMode.MERGE, restore.modes["first/records"])
        assertEquals("", restore.password)
        val delete = DeleteDraft(options).apply { confirmed = true; navigation.reviewing = true }
        restoreTaskSelection(delete, setOf("first/account", "removed/old"), emptySet(), emptySet())
        assertEquals(setOf("first/account", "first/records"), delete.selected)
        assertFalse(delete.confirmed)
        assertFalse(delete.navigation.reviewing)
    }
}
