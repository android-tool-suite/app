package com.example.shizukuaccessibilitygrant.plugins

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedWidgetDescriptor
import com.example.shizukuaccessibilitygrant.plugins.builtin.shizuku.ShizukuPlugin
import com.example.shizukuaccessibilitygrant.ui.Notice
import com.example.shizukuaccessibilitygrant.ui.SectionHeader
import com.example.shizukuaccessibilitygrant.ui.SuiteCard
import com.example.shizukuaccessibilitygrant.ui.SuiteColors
import com.example.shizukuaccessibilitygrant.ui.composePluginView

fun createShizukuPluginView(activity: Activity, plugin: ShizukuPlugin, host: PluginHost): View = composePluginView(activity) {
    var revision by remember { mutableIntStateOf(0) }
    plugin.setComposeInvalidator { activity.runOnUiThread { revision++ } }
    revision
    ShizukuContent(host)
}

fun createShizukuWidgetView(activity: Activity, host: PluginHost): View = composePluginView(activity) {
    ShizukuStatusCard(host)
}

@Composable
private fun ShizukuContent(host: PluginHost) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Shizuku 授权", "为需要系统能力的插件建立受控连接")
        Notice("宿主统一持有 Shizuku 授权。插件仍需在插件管理中单独获得对应能力，避免权限被隐式共享。")
        ShizukuStatusCard(host)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = host::requestShizukuPermission,
                enabled = host.isShizukuReady && !host.hasShizukuPermission(),
                modifier = Modifier.weight(1f),
            ) { Text("请求授权") }
            OutlinedButton(
                onClick = host::ensureShellService,
                enabled = host.hasShizukuPermission() && !host.isShellServiceConnected,
                modifier = Modifier.weight(1f),
            ) { Text("连接服务") }
        }
    }
}

@Composable
private fun ShizukuStatusCard(host: PluginHost) {
    val ready = host.isShizukuReady
    val granted = host.hasShizukuPermission()
    val connected = host.isShellServiceConnected
    val title = when {
        !ready -> "Shizuku 未连接"
        !granted -> "等待用户授权"
        !connected -> "正在准备服务"
        else -> "运行正常"
    }
    val detail = when {
        !ready -> "请先在设备上启动 Shizuku。"
        !granted -> "连接已建立，仍需批准此应用的授权请求。"
        !connected -> "授权成功，下一步连接 UserService。UID ${host.shizukuUid()}"
        else -> "UserService 已连接 · UID ${host.shizukuUid()}"
    }
    SuiteCard(containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (connected) SuiteColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun createImportedPluginView(activity: Activity, descriptor: ImportedPluginDescriptor, host: PluginHost): View = composePluginView(activity) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(descriptor.title, "v${descriptor.version} · ${descriptor.author}")
        SuiteCard {
            Text(descriptor.description, style = MaterialTheme.typography.bodyLarge)
            Notice("这是导入的插件清单。只有包含可执行 APK 且满足宿主接口的插件才会运行代码。", warning = true)
            if (descriptor.dependencies.isNotEmpty()) {
                Text("依赖", style = MaterialTheme.typography.titleMedium)
                Text(descriptor.dependencies.joinToString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("权限", style = MaterialTheme.typography.titleMedium)
            if (descriptor.requestedPermissions.isEmpty()) {
                Text("未声明额外权限", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("${descriptor.requestedPermissions.size} 项权限需在“插件管理 → 权限中心”统一授权。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { host.deleteImportedPlugin(descriptor.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DeleteOutline, null)
                Spacer(Modifier.padding(4.dp))
                Text("删除插件")
            }
        }
        Text("插件 ID · ${descriptor.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun createImportedWidgetView(
    activity: Activity,
    plugin: ImportedPluginDescriptor,
    widget: ImportedWidgetDescriptor,
): View = composePluginView(activity) {
    SuiteCard {
        Text(widget.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(widget.value.ifBlank { plugin.title }, style = MaterialTheme.typography.headlineSmall)
        Text(widget.subtitle.ifBlank { plugin.description }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
