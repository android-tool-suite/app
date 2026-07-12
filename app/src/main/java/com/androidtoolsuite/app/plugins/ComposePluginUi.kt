package com.androidtoolsuite.app.plugins

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidtoolsuite.app.plugin.api.PluginHost
import com.androidtoolsuite.app.plugins.builtin.shizuku.ShizukuPlugin
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SectionHeader
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteColors
import com.androidtoolsuite.app.ui.composePluginView

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
        Notice("Shizuku 授权由宿主统一持有。插件与宿主运行在同一进程，请只安装可信插件。")
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
