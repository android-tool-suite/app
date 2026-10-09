@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.androidtoolsuite.app.host

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.androidtoolsuite.app.migration.DatasetCategory
import com.androidtoolsuite.app.migration.DatasetRestoreMode
import com.androidtoolsuite.app.migration.MigrationBridgeManager
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SuiteShapes
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteSettingsRow
import java.util.Locale

private typealias DataOption = MigrationBridgeManager.DatasetOption

internal open class SelectionDraft(val options: List<DataOption>) {
    var navigation = DataOperationNavigation()
    var query by mutableStateOf("")
    var source by mutableStateOf<String?>(null)
    var onlySelected by mutableStateOf(false)
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var restoredFromTask = false
}

internal enum class ExportProtection { ALL, SENSITIVE, NONE, CUSTOM }

internal class ExportDraft(options: List<DataOption>) : SelectionDraft(options) {
    val initialSelected = closeRestoreSelection(options, options.filter {
        it.isHostItem || it.descriptor.category == DatasetCategory.SETTINGS ||
            (it.descriptor.category == DatasetCategory.DATA && it.descriptor.estimatedSize <= 32L * 1024L * 1024L)
    }.map { it.key() })
    var selected by mutableStateOf(initialSelected)
    var protection by mutableStateOf(ExportProtection.ALL)
    var customProtected by mutableStateOf(initialSelected)
    var password by mutableStateOf("")

    fun protectedKeys(): Set<String> = when (protection) {
        ExportProtection.ALL -> selected
        ExportProtection.SENSITIVE -> options.filter { it.key() in selected && exportDatasetIsSensitive(it) }.mapTo(linkedSetOf()) { it.key() }
        ExportProtection.NONE -> emptySet()
        ExportProtection.CUSTOM -> customProtected.intersect(selected)
    }

    fun updateSelection(updated: Set<String>) {
        customProtected = ((customProtected + (updated - selected)).intersect(updated))
        selected = updated
    }
}

internal class ImportDraft(options: List<DataOption>) : SelectionDraft(options) {
    val initialSelected = closeRestoreSelection(options, options.filter {
        !it.hasExistingData || DatasetRestoreMode.MERGE in it.descriptor.restoreModes
    }.map { it.key() })
    val initialModes = options.associate { it.key() to preferredImportMode(it) }
    var selected by mutableStateOf(initialSelected)
    var modes by mutableStateOf(initialModes)
    var password by mutableStateOf("")
}

internal class DeleteDraft(options: List<DataOption>) : SelectionDraft(options) {
    var selected by mutableStateOf<Set<String>>(emptySet())
    var confirmed by mutableStateOf(false)
    fun updateSelection(updated: Set<String>) { selected = updated; confirmed = false }
    fun restoreRemaining(previous: DeleteDraft) {
        val keys = options.mapTo(linkedSetOf()) { it.key() }
        selected = pruneDeleteSelection(options, previous.selected.intersect(keys))
        query = previous.query
        source = previous.source.takeIf { owner -> options.any { it.pluginId == owner } }
        onlySelected = previous.onlySelected
        expanded = previous.expanded.intersect(options.mapTo(linkedSetOf()) { it.pluginId })
        navigation = previous.navigation
        confirmed = false
    }
}

@Composable
private inline fun <reified T : SelectionDraft> rememberDataDraft(
    activity: MainActivity, type: String, options: List<DataOption>, crossinline create: () -> T,
): T = remember(options) {
    val saved = activity.uiStateForUi().dataDrafts[type] as? T
    if (saved != null && saved.options === options) saved
    else create().also { next ->
        if (next is DeleteDraft && saved is DeleteDraft && options.all { option -> saved.options.any { it === option } }) {
            next.restoreRemaining(saved)
        } else {
            activity.consumeDataRetryForUi(type)?.let { retry ->
                restoreTaskSelection(next, retry.keys, retry.protectedKeys, retry.mergeKeys)
            }
        }
        activity.uiStateForUi().dataDrafts[type] = next
    }
}

/** History contains scope and decisions only. Credentials never survive a historical retry. */
internal fun restoreTaskSelection(
    draft: SelectionDraft, keys: Collection<String>, protectedKeys: Collection<String>, mergeKeys: Collection<String>,
) {
    val available = draft.options.mapTo(linkedSetOf()) { it.key() }
    val requested = keys.intersect(available)
    when (draft) {
        is ExportDraft -> {
            val selected = pruneRestoreSelection(draft.options, closeRestoreSelection(draft.options, requested).intersect(available))
            val protected = protectedKeys.intersect(selected) + (selected - requested)
            draft.updateSelection(selected)
            draft.customProtected = protected
            draft.protection = when { protected == selected -> ExportProtection.ALL; protected.isEmpty() -> ExportProtection.NONE; else -> ExportProtection.CUSTOM }
            draft.password = ""
        }
        is ImportDraft -> {
            draft.selected = pruneRestoreSelection(draft.options, closeRestoreSelection(draft.options, requested).intersect(available))
            draft.modes = draft.options.associate { option ->
                option.key() to when {
                    option.key() !in requested -> preferredImportMode(option)
                    option.key() in mergeKeys && DatasetRestoreMode.MERGE in option.descriptor.restoreModes -> DatasetRestoreMode.MERGE
                    DatasetRestoreMode.REPLACE in option.descriptor.restoreModes -> DatasetRestoreMode.REPLACE
                    else -> preferredImportMode(option)
                }
            }
            draft.password = ""
        }
        is DeleteDraft -> draft.updateSelection(closeDeleteSelection(draft.options, requested).intersect(available))
    }
    draft.navigation.reviewing = false
    draft.restoredFromTask = true
}

private fun selectedSources(options: List<DataOption>, selected: Set<String>): Int =
    options.filter { it.key() in selected }.map { it.pluginId }.distinct().size

private fun selectedPreview(options: List<DataOption>, selected: Set<String>): List<Pair<String, String>> =
    options.filter { it.key() in selected }.groupBy { it.pluginId }.values.map { group ->
        group.first().pluginTitle to "${group.size} 项 · ${bridgeDatasetSize(group.sumOf { it.descriptor.estimatedSize })}"
    }

/** Filter first; every batch action receives this same complete set, never a rendered-page subset. */
internal fun visibleOptions(options: List<DataOption>, draft: SelectionDraft, selected: Set<String>): List<DataOption> {
    val query = draft.query.trim()
    return options.filter { option ->
        (draft.source == null || option.pluginId == draft.source) &&
            (!draft.onlySelected || option.key() in selected) &&
            (query.isEmpty() || listOf(option.pluginTitle, option.descriptor.name, bridgeCategoryLabel(option.descriptor.category))
                .any { it.contains(query, ignoreCase = true) })
    }
}

@Composable
private fun SelectionToolbar(
    options: List<DataOption>, draft: SelectionDraft, selected: Set<String>, visible: List<DataOption>,
    onSelectVisible: () -> Unit, onClearVisible: () -> Unit, onSelectCaches: (() -> Unit)? = null,
) {
    var searching by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
        if (draft.restoredFromTask) Text("已恢复上次未完成的选择，请核对后继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick = { sourceMenu = true }) {
                    Text(options.firstOrNull { it.pluginId == draft.source }?.pluginTitle ?: "全部来源")
                    Icon(Icons.Rounded.ExpandMore, null)
                }
                DropdownMenu(sourceMenu, { sourceMenu = false }) {
                    DropdownMenuItem(text = { Text("全部来源") }, onClick = { draft.source = null; sourceMenu = false })
                    options.groupBy { it.pluginId }.forEach { (owner, items) ->
                        DropdownMenuItem(text = { Text(items.first().pluginTitle) }, onClick = { draft.source = owner; sourceMenu = false })
                    }
                }
            }
            BrowseSearchToggle(searching || draft.query.isNotBlank(), { searching = !searching; if (!searching) draft.query = "" })
            Box {
                TextButton(onClick = { actionsMenu = true }) { Text("批量选择") }
                DropdownMenu(actionsMenu, { actionsMenu = false }) {
                    DropdownMenuItem(text = { Text("全选当前结果") }, enabled = visible.any { it.key() !in selected }, onClick = { actionsMenu = false; onSelectVisible() })
                    DropdownMenuItem(text = { Text("取消当前选择") }, enabled = visible.any { it.key() in selected }, onClick = { actionsMenu = false; onClearVisible() })
                    if (onSelectCaches != null) DropdownMenuItem(text = { Text("只选当前缓存") }, enabled = visible.any { it.descriptor.category == DatasetCategory.CACHE }, onClick = { actionsMenu = false; onSelectCaches() })
                }
            }
        }
        if (searching || draft.query.isNotBlank()) BrowseSearchField(draft.query, { draft.query = it }, "搜索来源或内容") { draft.query = ""; searching = false }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(draft.onlySelected, { draft.onlySelected = it })
            Text("只看已选内容", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(selected.size.toString() + " 项已选", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun LazyListScope.selectionGroups(
    prefix: String, draft: SelectionDraft, selected: Set<String>, visible: List<DataOption>,
    toggle: (Set<String>, Boolean) -> Unit,
) {
    if (visible.isEmpty()) item("$prefix-empty") {
        Text("没有匹配的内容。可以清除关键词或调整来源。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    visible.groupBy { it.pluginId }.forEach { (owner, items) ->
        item("$prefix-$owner") {
            val keys = items.mapTo(linkedSetOf()) { it.key() }
            val count = keys.count { it in selected }
            DataOperationGroup(
                title = items.first().pluginTitle, summary = "已选 $count / ${items.size} 项",
                expanded = owner in draft.expanded,
                onExpand = { draft.expanded = if (owner in draft.expanded) draft.expanded - owner else draft.expanded + owner },
                selectionState = when (count) { 0 -> ToggleableState.Off; keys.size -> ToggleableState.On; else -> ToggleableState.Indeterminate },
                onSelectGroup = { toggle(keys, count == keys.size) },
            ) {
                items.forEach { option ->
                    val checked = option.key() in selected
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle(setOf(option.key()), !it) })
                            .padding(horizontal = SuiteSpacing.md, vertical = SuiteSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                            Text(option.descriptor.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${if (option.isPluginPackage()) "安装包" else bridgeCategoryLabel(option.descriptor.category)} · ${bridgeDatasetSize(option.descriptor.estimatedSize)}" +
                                    (if (exportDatasetIsSensitive(option)) " · 敏感内容" else "") +
                                    (if (option.descriptor.dependencies.isNotEmpty()) " · 包含关联项目" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MigrationBridgeExportDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeExportOptionsForUi()
    if (options.isEmpty()) return
    val draft = rememberDataDraft(activity, "export", options) { ExportDraft(options) }
    val selected = draft.selected
    val protected = draft.protectedKeys()
    val encryptedCount = selected.count { it in protected }
    val plainCount = selected.size - encryptedCount
    val plainSensitive = options.count { it.key() in selected && it.key() !in protected && exportDatasetIsSensitive(it) }
    val visible = visibleOptions(options, draft, selected)
    fun toggle(keys: Set<String>, remove: Boolean) = draft.updateSelection(
        if (remove) pruneRestoreSelection(options, draft.selected - keys) else closeRestoreSelection(options, draft.selected + keys),
    )
    DataOperationDialog(
        sessionKey = options, navigation = draft.navigation,
        errorMessage = activity.dataOperationErrorForUi("export"),
        title = "导出数据包", description = "先选择要保留的内容，再设置文件保护。默认保留已安装的插件包，之后可以恢复这些本地版本。",
        selectedCount = selected.size,
        summary = "已选择 ${selected.size} 项 · ${selectedSources(options, selected)} 个来源" + if (draft.navigation.reviewing) " · $encryptedCount 项加密" else "",
        hasChanges = selected != draft.initialSelected || draft.protection != ExportProtection.ALL || draft.password.isNotEmpty(),
        confirmLabel = "选择保存位置", confirmEnabled = encryptedCount == 0 || draft.password.length >= 8,
        selectedPreview = selectedPreview(options, selected),
        onDismiss = activity::dismissMigrationBridgeExportForUi,
        onConfirm = { activity.confirmMigrationBridgeExportForUi(selected.toList(), protected.toList(), if (encryptedCount > 0) draft.password else "") },
        selection = {
            item("export-search") { SelectionToolbar(options, draft, selected, visible, { toggle(visible.mapTo(linkedSetOf()) { it.key() }, false) }, { toggle(visible.mapTo(linkedSetOf()) { it.key() }, true) }) }
            selectionGroups("export", draft, selected, visible, ::toggle)
        },
        confirmation = {
            item("export-protection") {
                val choices=listOf(
                    Triple(ExportProtection.ALL,"全部加密","所选内容使用同一个密码保护。"),
                    Triple(ExportProtection.SENSITIVE,"仅敏感内容加密","登录与隐私内容使用密码保护，其他内容可以直接读取。"),
                    Triple(ExportProtection.NONE,"不加密","能够读取文件的人可以查看全部内容。"),
                    Triple(ExportProtection.CUSTOM,"逐项选择","分别决定每一项是否使用密码保护。"),
                )
                var expanded by remember { mutableStateOf(false) }
                val current=choices.first { it.first==draft.protection }
                Box {
                    SuiteSettingsRow("文件保护",supportingText=current.third,trailingText=current.second,onClick={expanded=true},trailing={Icon(Icons.Rounded.ExpandMore,null)})
                    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                        choices.forEach { (policy,title,description) -> DropdownMenuItem(text={Column {Text(title);Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},onClick={
                            if(policy==ExportProtection.CUSTOM)draft.customProtected=draft.protectedKeys()
                            draft.protection=policy;expanded=false
                        }) }
                    }
                }
            }
            if (draft.protection == ExportProtection.CUSTOM) {
                options.filter { it.key() in selected }.groupBy { it.pluginId }.forEach { (owner, chosen) ->
                    item("export-custom-$owner") {
                        DataOperationSection(chosen.first().pluginTitle, "${chosen.size} 项") {
                            chosen.forEach { option ->
                                Row(
                                    Modifier.fillMaxWidth().toggleable(value = option.key() in protected, role = Role.Checkbox, onValueChange = { checked ->
                                        draft.customProtected = if (checked) draft.customProtected + option.key() else draft.customProtected - option.key()
                                    }).padding(SuiteSpacing.md), verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = option.key() in protected, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) {
                                        Text(option.descriptor.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (option.key() in protected) "密码加密" else "不加密", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (encryptedCount > 0) item("export-password") {
                var visiblePassword by remember { mutableStateOf(false) }
                OutlinedTextField(value = draft.password, onValueChange = { draft.password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("数据包密码") }, supportingText = { Text("至少 8 位；仅用于此次导出，请妥善保管") },
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={visiblePassword=!visiblePassword}){Icon(if(visiblePassword)Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,if(visiblePassword)"隐藏密码" else "显示密码")}},
                    visualTransformation = if(visiblePassword)VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, isError = draft.password.length in 1..7)
            }
            item("export-review") { Text("核对导出范围 · $plainCount 项不加密 · $encryptedCount 项加密", style = MaterialTheme.typography.titleMedium) }
            options.filter { it.key() in selected }.groupBy { it.pluginId }.forEach { (owner, chosen) ->
                item("export-review-$owner") { DataOperationReviewGroup(chosen.first().pluginTitle, chosen.map { it.descriptor.name to "${if (it.key() in protected) "密码加密" else "不加密"} · ${bridgeDatasetSize(it.descriptor.estimatedSize)}" }) }
            }
            if (plainSensitive > 0) item("export-warning") { Notice("$plainSensitive 项敏感内容未加密。能够读取文件的人可能看到凭据或隐私数据，可在上方调整保护方式。", warning = true) }
            item("export-destination") { Notice("下一步选择保存位置。取消或失败时保留本次选择和保护设置，不会更改本机数据。") }
        },
    )
}

@Composable
private fun ProtectionChoice(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = SuiteShapes.Card,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().toggleable(value = selected, role = Role.RadioButton, onValueChange = { onSelect() }).padding(SuiteSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = SuiteSpacing.sm), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun exportDatasetIsSensitive(option: DataOption): Boolean = option.descriptor.sensitive || option.descriptor.category == DatasetCategory.SECRET

@Composable
internal fun MigrationBridgeImportDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeImportOptionsForUi()
    if (options.isEmpty()) return
    val draft = rememberDataDraft(activity, "import", options) { ImportDraft(options) }
    val selected = draft.selected
    val visible = visibleOptions(options, draft, selected)
    val needsPassword = options.any { it.key() in selected && activity.migrationBridgeImportProtectionForUi(it.key()) == "PASSWORD" }
    val replacing = options.count { it.key() in selected && it.hasExistingData && draft.modes[it.key()] == DatasetRestoreMode.REPLACE }
    fun toggle(keys: Set<String>, remove: Boolean) {
        draft.selected = if (remove) pruneRestoreSelection(options, draft.selected - keys) else closeRestoreSelection(options, draft.selected + keys)
    }
    DataOperationDialog(
        sessionKey = options, navigation = draft.navigation,
        errorMessage = activity.dataOperationErrorForUi("import"),
        title = "导入数据包", description = "先选择要恢复的内容。下一步决定合并或替换，确认前不会更改本机数据。",
        selectedCount = selected.size, summary = "已选择 ${selected.size} 项 · ${selectedSources(options, selected)} 个来源" + if (draft.navigation.reviewing) " · $replacing 项替换" else "",
        hasChanges = selected != draft.initialSelected || draft.modes != draft.initialModes || draft.password.isNotEmpty(),
        confirmLabel = "开始导入", confirmEnabled = !activity.migrationBridgeImportBusyForUi() && (!needsPassword || draft.password.length >= 8),
        selectedPreview = selectedPreview(options, selected), onDismiss = activity::dismissMigrationBridgeImportForUi,
        onConfirm = { activity.confirmMigrationBridgeImportForUi(
            selected.filter { draft.modes[it] == DatasetRestoreMode.REPLACE }, selected.filter { draft.modes[it] == DatasetRestoreMode.MERGE }, if (needsPassword) draft.password else "",
        ) },
        selection = {
            item("import-source") { ImportSourceCard(activity) }
            item("import-search") { SelectionToolbar(options, draft, selected, visible, { toggle(visible.mapTo(linkedSetOf()) { it.key() }, false) }, { toggle(visible.mapTo(linkedSetOf()) { it.key() }, true) }) }
            selectionGroups("import", draft, selected, visible, ::toggle)
        },
        confirmation = {
            item("import-review-source") { ImportSourceCard(activity) }
            item("import-treatment") { Text("如何处理已有内容", style = MaterialTheme.typography.titleLarge) }
            options.filter { it.key() in selected }.groupBy { it.pluginId }.forEach { (owner, chosen) ->
                item("import-treatment-$owner") {
                    DataOperationSection(chosen.first().pluginTitle, "${chosen.size} 项") {
                        chosen.forEach { option ->
                            DataOperationEntry(option.descriptor.name, if (option.hasExistingData) "本机已有内容" else "将新增到本机") {
                                if (option.hasExistingData) {
                                    var treatmentMenu by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { treatmentMenu = true }) {
                                            Text(if (draft.modes[option.key()] == DatasetRestoreMode.MERGE) "合并到本机" else "替换本机内容")
                                            Icon(Icons.Rounded.ExpandMore, null)
                                        }
                                        DropdownMenu(treatmentMenu, { treatmentMenu = false }) {
                                            option.descriptor.restoreModes.forEach { mode ->
                                                DropdownMenuItem(text = { Text(if (mode == DatasetRestoreMode.MERGE) "合并到本机" else "替换本机内容") },
                                                    onClick = { draft.modes = draft.modes + (option.key() to mode); treatmentMenu = false })
                                            }
                                        }
                                    }
                                    if (DatasetRestoreMode.MERGE !in option.descriptor.restoreModes) Text(if (option.descriptor.category == DatasetCategory.CACHE) "此缓存需保持整体版本一致。" else "此项未提供合并规则，选择导入会替换现有内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else Text("导入为新内容", style = MaterialTheme.typography.bodyMedium)
                                if (option.isPluginPackage()) Text("恢复时仍检查兼容性与来源，新安装插件保持停用；完全信任需要单独确认。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (replacing > 0) item("import-replacing") { Notice("$replacing 项现有内容将被替换。请核对上方处理方式，也可以返回选择调整范围。", warning = true) }
            if (needsPassword) item("import-password") {
                var visiblePassword by remember { mutableStateOf(false) }
                OutlinedTextField(value = draft.password, onValueChange = { draft.password = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("数据包密码") }, supportingText = { Text("输入导出时设置的密码，至少 8 位") },
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),trailingIcon={IconButton(onClick={visiblePassword=!visiblePassword}){Icon(if(visiblePassword)Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,if(visiblePassword)"隐藏密码" else "显示密码")}},
                    visualTransformation = if(visiblePassword)VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, isError = draft.password.length in 1..7)
            }
            item("import-validation") { Notice("所选内容先完成校验，再按确认的方式处理。导入失败会保留本次选择与处理方式，可修正后重试。") }
        },
    )
}

@Composable
private fun ImportSourceCard(activity: MainActivity) {
    hostRevision(activity)
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = SuiteShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(SuiteSpacing.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
            Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(activity.migrationBridgeImportFileNameForUi().ifBlank { "备份文件" }, style = MaterialTheme.typography.bodyLarge)
                Text(activity.migrationBridgeImportSourceForUi(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = activity::replaceMigrationBridgeFileForUi, enabled = !activity.migrationBridgeImportBusyForUi()) { Text("更换") }
        }
    }
}

private fun preferredImportMode(option: DataOption): DatasetRestoreMode = when {
    option.hasExistingData && DatasetRestoreMode.MERGE in option.descriptor.restoreModes -> DatasetRestoreMode.MERGE
    DatasetRestoreMode.REPLACE in option.descriptor.restoreModes -> DatasetRestoreMode.REPLACE
    else -> option.descriptor.restoreModes.first()
}

@Composable
internal fun MigrationBridgeDeleteDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeDeleteOptionsForUi()
    if (options.isEmpty()) return
    val draft = rememberDataDraft(activity, "delete", options) { DeleteDraft(options) }
    val selected = draft.selected
    val visible = visibleOptions(options, draft, selected)
    fun toggle(keys: Set<String>, remove: Boolean) = draft.updateSelection(
        if (remove) pruneDeleteSelection(options, draft.selected - keys) else closeDeleteSelection(options, draft.selected + keys),
    )
    DataOperationDialog(
        sessionKey = options, navigation = draft.navigation,
        errorMessage = activity.dataOperationErrorForUi("delete"),
        title = "删除插件数据", description = "默认不选择任何内容。先确定删除范围，再核对关联影响；插件包不会因此被卸载。",
        selectedCount = selected.size, summary = "已选择 ${selected.size} 项 · ${selectedSources(options, selected)} 个来源 · 尚未删除",
        hasChanges = selected.isNotEmpty(), confirmLabel = "永久删除", confirmEnabled = draft.confirmed,
        selectedPreview = selectedPreview(options, selected), onDismiss = activity::dismissMigrationBridgeDeleteForUi,
        onConfirm = { activity.confirmMigrationBridgeDeleteForUi(selected.toList()) },
        onBackToSelection = { draft.confirmed = false }, destructive = true,
        selection = {
            item("delete-search") { SelectionToolbar(options, draft, selected, visible,
                { toggle(visible.mapTo(linkedSetOf()) { it.key() }, false) }, { toggle(visible.mapTo(linkedSetOf()) { it.key() }, true) },
                onSelectCaches = {
                    val base = pruneDeleteSelection(options, selected - visible.mapTo(linkedSetOf()) { it.key() })
                    draft.updateSelection(closeDeleteSelection(options, base + visible.filter { it.descriptor.category == DatasetCategory.CACHE }.map { it.key() }))
                },
            ) }
            selectionGroups("delete", draft, selected, visible, ::toggle)
        },
        confirmation = {
            item("delete-warning") { Notice("将永久删除下面 ${selected.size} 项内容，无法撤销。请先导出需要保留的内容。", warning = true) }
            options.filter { it.key() in selected }.groupBy { it.pluginId }.forEach { (owner, chosen) ->
                item("delete-review-$owner") { DataOperationReviewGroup(chosen.first().pluginTitle, chosen.map { it.descriptor.name to "永久删除 · ${bridgeCategoryLabel(it.descriptor.category)}" }) }
            }
            if (options.any { it.key() in selected && it.descriptor.dependencies.isNotEmpty() }) item("delete-dependencies") {
                Notice("删除范围包含依赖所选内容的数据。下列确认针对当前完整范围，返回选择后需要重新确认。")
            }
            item("delete-acknowledgement") {
                Row(Modifier.fillMaxWidth().toggleable(value = draft.confirmed, role = Role.Checkbox, onValueChange = { draft.confirmed = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = draft.confirmed, onCheckedChange = null)
                    Text("我确认永久删除所选内容", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        },
    )
}

internal fun closeRestoreSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { option ->
            if (option.requiresBridgeResolution()) {
                val packageKey = "${MigrationBridgeManager.HOST_OWNER_ID}/" +
                    "${MigrationBridgeManager.HOST_PLUGIN_PACKAGE_PREFIX}${option.pluginId}"
                if (result.add(packageKey)) changed = true
            }
            option.descriptor.dependencies.forEach { dependency ->
                if (result.add("${option.pluginId}/$dependency")) changed = true
            }
        }
    } while (changed)
    return result
}

internal fun pruneRestoreSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { option ->
            val requiredPackageKey = "${MigrationBridgeManager.HOST_OWNER_ID}/" +
                "${MigrationBridgeManager.HOST_PLUGIN_PACKAGE_PREFIX}${option.pluginId}"
            val packageMissing = option.requiresBridgeResolution() && requiredPackageKey !in result
            if (packageMissing || option.descriptor.dependencies.any { "${option.pluginId}/$it" !in result }) {
                result.remove(option.key())
                changed = true
            }
        }
    } while (changed)
    return result
}

internal fun closeDeleteSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.forEach { option ->
            if (option.descriptor.dependencies.any { "${option.pluginId}/$it" in result }) {
                if (result.add(option.key())) changed = true
            }
        }
    } while (changed)
    return result
}

internal fun pruneDeleteSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { parent ->
            val missingDependent = options.any { dependent ->
                dependent.pluginId == parent.pluginId && dependent.key() !in result &&
                    parent.descriptor.id in dependent.descriptor.dependencies
            }
            if (missingDependent) {
                result.remove(parent.key())
                changed = true
            }
        }
    } while (changed)
    return result
}

private fun bridgeCategoryLabel(category: DatasetCategory): String = when (category) {
    DatasetCategory.SETTINGS -> "设置"
    DatasetCategory.DATA -> "业务数据"
    DatasetCategory.SECRET -> "凭据"
    DatasetCategory.CACHE -> "缓存"
}

private fun bridgeDatasetSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
}
