@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.androidtoolsuite.app.host

import android.text.format.Formatter
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
import androidx.compose.ui.platform.LocalConfiguration
import com.androidtoolsuite.app.migration.MigrationBridgeManager
import com.androidtoolsuite.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DataCenterScreen(activity: MainActivity) {
    hostRevision(activity)
    var entries by remember { mutableStateOf<List<MainActivity.DataInventoryEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    val query = activity.dataQueryForUi()
    val owner = activity.dataOwnerForUi()
    val columns = if (LocalConfiguration.current.screenWidthDp >= 840) 2 else 1
    DisposableEffect(refresh) {
        var active = true
        activity.loadDataInventoryForUi({ if (active) { entries = it; error = null } }, { if (active) error = it })
        onDispose { active = false }
    }
    val grouped = entries.orEmpty().groupBy { it.owner }
    val ownerTitles = linkedMapOf(MigrationBridgeManager.HOST_OWNER_ID to "Android Tool Suite").apply {
        activity.importedDescriptorsForUi().forEach { put(it.id, it.title) }
        grouped.forEach { (id, rows) -> put(id, rows.first().ownerTitle) }
    }
    LazyColumn(state = rememberPageListState(activity, "data-${owner.orEmpty()}"), contentPadding = PaddingValues(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                Button(onClick = activity::prepareMigrationBridgeExportForUi, Modifier.weight(1f)) { Text("导出") }
                OutlinedButton(onClick = activity::importMigrationBridgeForUi, Modifier.weight(1f)) { Text("导入") }
                OutlinedButton(onClick = activity::prepareMigrationBridgeDeleteForUi, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
            }
            Spacer(Modifier.height(SuiteSpacing.md))
            Text("按来源查看本地内容。导入、导出与删除均先选择操作范围。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (owner != null) item { TextButton(onClick = { activity.setDataOwnerForUi(null) }) { Text("全部应用与插件数据") } }
        if (error != null) item { ErrorState("读取数据清单失败", error.orEmpty(), { refresh++ }) }
        if (entries != null) {
            if (owner == null) {
                item { SectionHeader("本地内容", "按来源浏览") { BrowseSearchToggle(searching || query.isNotBlank(), {val active=searching||query.isNotBlank();searching=!active;if(active)activity.setDataQueryForUi("")}) } }
                if(searching || query.isNotBlank()) item { BrowseSearchField(query,activity::setDataQueryForUi,"搜索应用或插件") {activity.setDataQueryForUi("");searching=false} }
                val owners = ownerTitles.filter { (_, title) -> title.contains(query, true) }
                items(owners.keys.toList().chunked(columns), key = { it.first() }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                        row.forEach { id ->
                            val rows = grouped[id].orEmpty()
                            DataOwnerCard(owners.getValue(id), rows, Modifier.weight(1f)) { activity.setDataOwnerForUi(id) }
                        }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
                if (owners.isEmpty()) item { EmptyState("没有匹配的数据来源", "换一个名称试试。") }
            } else {
                val rows = grouped[owner].orEmpty()
                item { SectionHeader(ownerTitles[owner] ?: "本地数据", "${rows.size} 类内容") }
                items(rows) { entry ->
                    SuiteCard {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text("${when(entry.category) { "CACHE" -> "可重建缓存"; "SECRET" -> "登录与敏感数据"; "SETTINGS" -> "设置"; else -> "业务数据" }} · ${if (entry.bytes < 1024) "${entry.bytes} B" else "${entry.bytes / 1024} KB"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry.sensitive) Text("默认使用密码保护", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (rows.isEmpty()) item { EmptyState("没有可管理的本地内容", "连接与系统授权状态会重新读取，无需备份。") }
            }
        }

    }
}

@Composable
private fun DataOwnerCard(title: String, rows: List<MainActivity.DataInventoryEntry>, modifier: Modifier, onClick: () -> Unit) {
    val sensitive = rows.count { it.sensitive }
    SuiteCard(modifier = modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
            IconBox(Icons.Rounded.FolderOpen, null, dense = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (rows.isEmpty()) "暂无可备份内容" else "${rows.size} 类内容 · ${Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current,rows.sumOf { it.bytes })}" + if(sensitive>0) " · $sensitive 类登录或隐私内容" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun HostTasksScreen(activity: MainActivity) {
    hostRevision(activity)
    val tasks = activity.tasksForUi()
    val selected = activity.selectedTaskForUi()
    val current = tasks.find { it.id == selected }
    androidx.activity.compose.BackHandler(enabled = selected != null) { activity.setSelectedTaskForUi(null) }
    LazyColumn(state = rememberPageListState(activity, selected?.let { "task-$it" } ?: "tasks"), contentPadding = PaddingValues(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg)) {
        if (current != null) {
            item { TextButton(onClick = { activity.setSelectedTaskForUi(null) }) { Text("全部任务") } }
            item {
                SuiteCard {
                    Text(current.title, style = MaterialTheme.typography.headlineSmall)
                    Text(when (current.status) { "running" -> "正在进行"; "success" -> "已完成"; "partial" -> "部分完成"; "cancelled" -> "已取消"; else -> "需要处理" }, color = when (current.status) { "failed", "partial" -> MaterialTheme.colorScheme.error; "cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant; else -> MaterialTheme.colorScheme.primary })
                    if (current.status == "running") LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(current.detail, style = MaterialTheme.typography.bodyMedium)
                    if (current.status == "failed" || current.status == "partial" || current.status == "cancelled") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                            OutlinedButton(onClick = { activity.returnToTaskSourceForUi(current.id) }) { Text(when (current.kind) { "plugin" -> "返回工具"; "install" -> "查看插件版本"; else -> "返回修改" }) }
                            if (current.kind != "plugin" && current.kind != "install") Button(onClick = { activity.retryUiTaskForUi(current.id) }) { Text("重试未完成项") }
                        }
                        if (current.pluginId == null) Text("重试会先恢复未完成范围，核对并确认后才执行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (current.status == "success") {
                        TextButton(onClick = { activity.returnToTaskSourceForUi(current.id) }) { Text(if (current.kind == "plugin") "返回工具" else if (current.pluginId == null) "查看数据管理" else "查看插件") }
                    }
                }
            }
            items(current.rows) { row -> SuiteSettingsRow(row.title, trailingText = when(row.status) { "success" -> "已完成"; "failed" -> "未完成"; else -> "等待处理" }) }
        } else {
            item { SectionHeader("本次使用", "${tasks.count { it.status == "running" }} 项进行中") }
            if (tasks.isEmpty()) item { EmptyState("还没有任务", "同步、导入、导出、删除与安装开始后，会在这里显示真实结果。") }
            items(tasks, key = { it.id }) { task ->
                SuiteSettingsRow(task.title, supportingText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(task.createdAt)) + " · " + when(task.status) { "running" -> "进行中"; "success" -> "已完成"; "partial" -> "部分完成"; "cancelled" -> "已取消"; else -> "需要处理" }, leadingIcon = if (task.status == "success") Icons.Rounded.CheckCircle else Icons.Rounded.History, trailing = { Icon(Icons.Rounded.ChevronRight, null) }, onClick = { activity.setSelectedTaskForUi(task.id) })
            }
        }
    }
}
