@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.androidtoolsuite.app.host

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.net.Uri
import android.text.format.Formatter
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.update.UpdateCatalog
import androidx.compose.ui.unit.dp
import com.androidtoolsuite.app.ui.*

internal fun androidVersionLabel(api:Int):String = when(api) {
    24->"7.0";25->"7.1";26->"8.0";27->"8.1";28->"9";29->"10";30->"11";31->"12";32->"12L";33->"13";34->"14";35->"15";36->"16";else->"API $api"
}

@Composable
internal fun PluginCenterScreen(activity: MainActivity, discover: Boolean, modifier: Modifier = Modifier) {
    hostRevision(activity)
    var query by remember(discover) { mutableStateOf("") }
    var searching by remember(discover) { mutableStateOf(false) }
    val installed = activity.importedDescriptorsForUi()
    val releases = activity.repositoryPluginsForUi()
    Column(modifier) {
        LazyColumn(
            state = rememberPageListState(activity, if (discover) "discover" else "installed"),
            contentPadding = PaddingValues(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
        ) {
            item {
                SectionHeader(if (discover) "可安装插件" else "已安装插件", if (discover) "安装后在插件详情设置" else "${installed.size} 个插件") {
                    BrowseSearchToggle(searching || query.isNotBlank(), { searching=!searching;if(!searching)query="" })
                    IconButton(onClick = activity::refreshUpdatesForUi, enabled = !activity.isUpdateOperationRunningForUi("__check__")) { Icon(Icons.Rounded.Refresh, if(discover) "刷新插件目录" else "检查插件更新") }
                }
            }
            if(searching || query.isNotBlank()) item { BrowseSearchField(query,{query=it},"搜索名称或用途") {query="";searching=false} }
            if (!discover && activity.availablePluginUpdatesForUi().isNotEmpty()) item {
                SuiteCard {
                    SectionHeader("${activity.availablePluginUpdatesForUi().size} 个插件可更新", "仅包含已安装且允许检查更新的插件") {
                        TextButton(onClick = activity::installAllPluginUpdatesForUi) { Text("全部更新") }
                    }
                }
            }
            if (activity.updateCheckStateForUi() == MainActivity.UpdateCheckState.FAILED) item {
                ErrorState("插件目录刷新失败", activity.updateErrorForUi(), activity::refreshUpdatesForUi)
            }
            val ids = (if (discover) releases.filter { release -> installed.none { it.id == release.id } }.map { it.id } else installed.map { it.id }).distinct().filter { id ->
                val release = releases.find { it.id == id }
                val descriptor = installed.find { it.id == id }
                query.isBlank() || listOf(descriptor?.title, descriptor?.description, release?.title, release?.description).any { it?.contains(query, true) == true }
            }
            if (ids.isEmpty() && (query.isNotBlank() || !discover || activity.updateCheckStateForUi()!=MainActivity.UpdateCheckState.FAILED)) item {
                val allInstalled=discover&&releases.isNotEmpty()&&query.isBlank()
                EmptyState(if(query.isNotBlank()) "没有匹配的插件" else if(allInstalled) "目录中的插件都已安装" else if(discover) "暂无可安装插件" else "还没有安装插件",if(query.isNotBlank()) "清除关键词后查看完整列表。" else if(allInstalled) "更新与版本切换位于插件详情。" else "刷新目录，或导入本地插件包。")
                TextButton(onClick={if(query.isNotBlank()){query="";searching=false}else activity.navigateForUi(if(discover)2 else 6)}) { Text(if(query.isNotBlank()) "清除搜索" else if(discover) "查看管理" else "前往仓库") }
            }
            items(ids, key = { it }) { id ->
                val release = releases.find { it.id == id }
                val descriptor = installed.find { it.id == id }
                Column {
                    SuiteSettingsRow(
                        descriptor?.title ?: release?.title ?: id,
                        supportingText = if (discover) release?.description else "v${descriptor?.version} · ${if(activity.isRuntimeActivationPendingForUi(id)) "等待重启激活" else if (activity.isImportedPluginEnabled(id)) "已启用" else "已停用"}" + if(!activity.isToolVisibleByIdForUi(id)) " · 已放入工具收纳" else "",
                        leading = { IconBox(pluginIconForId(id), null, dense = true) },
                        trailing = { Icon(Icons.Rounded.ChevronRight, null) },
                        onClick = { activity.showPluginManagementForUi(id, "detail") },
                    )
                    if (release != null && descriptor != null && activity.isRepositoryPluginUpdateAvailableForUi(release)) Text("有更新 · v${release.versionName}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
            if (discover) item { OutlinedButton(onClick = activity::importPlugin, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(SuiteSpacing.sm)); Text("导入本地插件包") } }
        }
    }
}

@Composable
internal fun PluginManagementScreen(activity: MainActivity, id: String) {
    hostRevision(activity)
    val descriptor = activity.importedDescriptorsForUi().find { it.id == id }
    val release = activity.repositoryPluginsForUi().find { it.id == id }
    val tool = activity.findToolForUi(id)
    val title = descriptor?.title ?: release?.title ?: tool?.title() ?: id
    val page = activity.managedPluginPageForUi()
    val enabled = descriptor != null && activity.isImportedPluginEnabled(id)
    val trusted = activity.isTrustedProviderForUi(id)
    val description = descriptor?.description?.takeIf { it.isNotBlank() } ?: tool?.description()?.takeIf { it.isNotBlank() } ?: release?.description.orEmpty()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            SuiteTopBar(when (page) { "permissions" -> "功能权限"; "versions" -> "版本与来源"; "info" -> "关于插件"; else -> "插件详情" }, onBack = activity::handleBackForUi)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.lg),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        state = rememberPageListState(activity, "plugin-$id-$page"),
                        contentPadding = PaddingValues(start = SuiteSpacing.xl, top = SuiteSpacing.xl, end = SuiteSpacing.xl, bottom = SuiteSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg),
                    ) {
                        if (page == "detail") item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                                IconBox(pluginIconForId(id), null)
                                Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge); Text("v${descriptor?.version ?: release?.versionName ?: tool?.version().orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        when (page) {
                            "info" -> { item { Text(title, style = MaterialTheme.typography.titleLarge) }; item { PluginSummaryCard(activity, descriptor, release, description) } }
                            "permissions" -> {
                                item { Text("分别决定这个插件可以使用哪些功能。未允许的能力会在调用时被拒绝。", style = MaterialTheme.typography.bodyMedium) }
                                if (trusted) item { Notice("这是完全信任的系统插件，可以直接使用应用拥有的系统权限，无法逐项限制。其他插件调用它时仍须各自获得允许。", warning = true) }
                                val permissions = activity.pluginPermissionsForUi(id)
                                items(permissions) { permission -> PluginPermissionRow(permission, activity.pluginPermissionScopeForUi(permission)) { activity.setPluginPermissionForUi(id, permission.capabilityId, it) } }
                                if (permissions.isEmpty() && !trusted) item { Notice("此插件没有需要你决定的功能权限。") }
                            }
                            "versions" -> {
                                item { Notice(if (descriptor == null) "这里列出正式目录中的发布版本，安装前会检查兼容性。" else if (activity.isRepositoryVerifiedForUi(id)) "当前安装包与已验证的正式发布目录匹配。" else "当前安装包未匹配正式目录，可能来自本地导入。") }
                                val versions = activity.repositoryPluginVersionsForUi(id)
                                if (versions.isEmpty()) item { EmptyState("没有可用的发布版本", "刷新目录后重试；本地安装包仍可导出。") }
                                items(versions, key = { it.sha256 }) { RepositoryPluginCard(activity, it) }
                            }
                            else -> {
                                item {
                                    Text(description.ifBlank { "管理此插件的显示、权限与本地数据。" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(SuiteSpacing.md))
                                    Text(if (descriptor == null) "尚未安装" else if (activity.isRuntimeActivationPendingForUi(id)) "等待重启激活" else if (enabled) "已启用" else "已停用", color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(SuiteSpacing.md))
                                    if (descriptor == null) {
                                        if (release != null) Button(onClick = { activity.installRepositoryPluginVersionForUi(release) }, enabled = activity.isRepositoryPluginVersionSelectableForUi(release) && !activity.isUpdateOperationRunningForUi(id), modifier = Modifier.fillMaxWidth()) { Text("安装插件") }
                                        else OutlinedButton(onClick = activity::importPlugin, Modifier.fillMaxWidth()) { Text("导入本地插件包") }
                                    } else if (activity.isRuntimeActivationPendingForUi(id)) Button(onClick = { activity.requestPluginRestartForUi(id) }, Modifier.fillMaxWidth()) { Text("重启以激活") }
                                    else if (!enabled) Button(onClick = { activity.setImportedPluginEnabled(id, true) }, Modifier.fillMaxWidth()) { Text("启用插件") }
                                    else if (tool != null) Button(onClick = { activity.openPluginForUi(tool) }, Modifier.fillMaxWidth()) { Text("打开工具") }
                                    else ErrorState("插件无法加载", "重新安装后重试。", activity::importPlugin)
                                    if (activity.isUpdateOperationRunningForUi(id)) RepositoryDownloadProgress(activity, id)
                                }
                                if (release != null && descriptor != null && activity.isRepositoryPluginUpdateAvailableForUi(release)) item {
                                    SuiteSettingsRow("新版本 v${release.versionName}", supportingText = "查看版本并选择更新", trailing = { Icon(Icons.Rounded.ChevronRight, null) }, onClick = { activity.showPluginManagementForUi(id, "versions") })
                                }
                                if (descriptor != null) item {
                                    SuiteSettingsGroup("显示与使用") {
                                        SuiteSettingsSwitchRow("启用插件", checked = enabled, onCheckedChange = { activity.setImportedPluginEnabled(id, it) })
                                        SuiteSettingsSwitchRow("在工具主列表显示", supportingText = "关闭后放入工具收纳，插件继续运行", checked = activity.isToolVisibleByIdForUi(id), onCheckedChange = { activity.setToolVisibleByIdForUi(id, it) })
                                    }
                                }
                                item {
                                    SuiteSettingsGroup("权限与版本") {
                                        if (descriptor != null) SuiteSettingsRow("功能权限", supportingText = if (trusted) "完全信任的系统插件" else "管理当前插件的能力范围", onClick = { activity.showPluginManagementForUi(id, "permissions") }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                        SuiteSettingsRow("版本与来源", onClick = { activity.showPluginManagementForUi(id, "versions") }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                        SuiteSettingsRow("关于插件", supportingText = "发布者、兼容性与来源信息", onClick = { activity.showPluginManagementForUi(id, "info") }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                        if (descriptor != null) SuiteSettingsSwitchRow("检查此插件更新", checked = activity.isPluginUpdateCheckEnabledForUi(id), onCheckedChange = { activity.setPluginUpdateCheckEnabledForUi(id, it) })
                                    }
                                }
                                if (descriptor != null) item {
                                    SuiteSettingsGroup("插件数据") {
                                        SuiteSettingsRow("导出数据", supportingText = "选择此插件的数据与备份保护方式", onClick = { activity.prepareMigrationBridgeExportForPluginUi(id) }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                        SuiteSettingsRow("导入数据", supportingText = "从备份中核对此插件的可恢复内容", onClick = { activity.importMigrationBridgeForPluginUi(id) }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                        SuiteSettingsRow("删除数据", supportingText = "选择此插件的数据并确认永久删除", onClick = { activity.prepareMigrationBridgeDeleteForPluginUi(id) }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
                                    }
                                }
                                if (descriptor != null) item {
                                    SuiteSettingsGroup("插件包") {
                                        SuiteSettingsRow("导出插件包", supportingText = "保留当前安装文件", onClick = { activity.exportPlugin(id) })
                                        SuiteSettingsRow("移除插件", supportingText = "保留已有业务数据", onClick = { activity.requestDeletePluginForUi(id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginSummaryCard(
    activity: MainActivity,
    descriptor: ImportedPluginDescriptor?,
    release: UpdateCatalog.PluginRelease?,
    description: String,
) {
    if (descriptor == null && release == null && description.isBlank()) return
    SuiteCard {
        Text("安装与发布信息", style = MaterialTheme.typography.titleMedium)
        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (descriptor != null) {
            PluginSummaryField("已安装版本", descriptor.version)
            descriptor.author?.takeIf { it.isNotBlank() && it != "未知作者" }?.let { PluginSummaryField("安装包发布者", it) }
            if (descriptor.minHostVersionCode > 0) PluginSummaryField("应用要求", "${activity.requiredAppVersionLabelForUi(descriptor.minHostVersionCode)} 或更高版本")
        }
        if (release != null) {
            if (descriptor != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Directory metadata describes this published artifact, not an unmatched local installation.
            Text("正式目录 · v${release.versionName}", style = MaterialTheme.typography.titleSmall)
            release.author?.takeIf { it.isNotBlank() }?.let { PluginSummaryField("发布者", it) }
            if (release.minAndroidApi > 0) PluginSummaryField("最低 Android 版本", androidVersionLabel(release.minAndroidApi))
            if (release.minHostVersionCode > 0 && (descriptor == null || release.minHostVersionCode != descriptor.minHostVersionCode)) {
                PluginSummaryField("目录版本应用要求", "${activity.requiredAppVersionLabelForUi(release.minHostVersionCode)} 或更高版本")
            }
            if (release.size > 0) PluginSummaryField("目录安装包大小", Formatter.formatFileSize(activity, release.size))
            val repository = release.repositoryUrl?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() }
            }
            if (repository != null) {
                PluginSummaryField("发布项目", repository.host.orEmpty() + repository.path.orEmpty())
                TextButton(onClick = { activity.openSourceUrlForUi(repository.toString()) }) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(SuiteSpacing.sm))
                    Text("打开发布项目")
                }
            }
        }
    }
}

@Composable
private fun PluginSummaryField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CompatibilityScreen(activity: MainActivity) {
    LazyColumn(contentPadding = PaddingValues(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg)) {
        item { Notice("旧插件 APK（API 1）不能在当前运行时安装或执行。已有旧文件和业务数据不会自动删除。", warning = true) }
        item { SectionHeader("1 先保留旧数据"); Text("在支持 Migration Bridge 的旧版本中导出需要的数据。") }
        item { SectionHeader("2 安装受支持的新插件"); Text("当前 .atsplugin 包仍须通过签名、版本和数据兼容检查。") }
        item { SectionHeader("3 核对可恢复内容"); Text("兼容的 Dataset 可按范围恢复；旧迁移包仅恢复可识别的应用状态，不恢复旧插件代码。") }
        item { Notice("旧 Shizuku 启用标记不会授权或启用新的可信 Provider，仍需单独确认。") }
        item { Button(onClick = activity::importMigrationBridgeForUi, Modifier.fillMaxWidth()) { Text("选择备份并核对") } }
    }
}

@Composable
internal fun OpenSourceScreen(activity: MainActivity) {
    val licenses = listOf(
        Triple("AndroidX 与 Jetpack Compose", "Apache License 2.0", "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"),
        Triple("Kotlin", "Apache License 2.0", "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"),
        Triple("Material Components for Android", "Apache License 2.0", "https://github.com/material-components/material-components-android/blob/master/LICENSE"),
        Triple("Shizuku API 与 Provider", "MIT License", "https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE"),
    )
    var expanded by remember { mutableStateOf<String?>(null) }
    LazyColumn(state = rememberPageListState(activity, "open-source"), contentPadding = PaddingValues(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
        item { Text("主要开源组件", style = MaterialTheme.typography.titleLarge); Text("展开组件可查看许可名称，并前往项目中的完整许可与版权声明。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(licenses) { (name, license, url) ->
            SuiteCard {
                Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == name) null else name }, verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Icon(if (expanded == name) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded == name) "收起" else "展开")
                }
                if (expanded == name) {
                    Text(license, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { activity.openSourceUrlForUi(url) }) { Text("查看完整许可与版权声明") }
                }
            }
        }
    }
}
