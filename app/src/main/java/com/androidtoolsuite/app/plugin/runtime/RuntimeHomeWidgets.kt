package com.androidtoolsuite.app.plugin.runtime

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteStatusChip
import com.androidtoolsuite.app.ui.composePluginView
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest
import org.json.JSONObject

internal object RuntimeHomeWidgets {
    @JvmStatic
    fun prepare(widgets: List<HostHomeWidget>): List<WidgetSnapshotStore.Entry> =
        widgets.filterIsInstance<RuntimeHomeWidget>().mapNotNull { it.prepare() }

    fun create(
        installed: PluginPackageStore.InstalledPlugin,
        actions: HostActions,
        revision: MutableIntState,
    ): List<HostHomeWidget> = installed.manifest.homeWidgetContributions.map { contribution ->
        RuntimeHomeWidget(installed, contribution, actions, revision)
    }
}

private class RuntimeHomeWidget(
    private val installed: PluginPackageStore.InstalledPlugin,
    private val contribution: RuntimePluginManifest.HomeWidgetContribution,
    private val actions: HostActions,
    private val revision: MutableIntState,
) : HostHomeWidget {
    fun prepare(): WidgetSnapshotStore.Entry? {
        val store = PluginRuntime.get(actions.activity()).widgetSnapshots()
        val entry = store.entry(installed, contribution)
        // Only live system summaries: worker/data summaries retain their lazy/cache policy.
        if (entry.persistent) return null
        store.hostChanged(entry, revision.intValue)
        return entry
    }

    override fun id(): String = contribution.id
    override fun title(): String = contribution.title
    override fun pluginId(): String = installed.manifest.plugin.id
    override fun supportedSizes(): List<HomeWidgetSize> = contribution.sizes.mapNotNull(::parseSize)

    override fun createView(activity: Activity, host: HostServices): View = composePluginView(activity) {
        RuntimeWidgetContent(installed, contribution, actions, revision.intValue)
    }
}

@Composable
private fun RuntimeWidgetContent(
    installed: PluginPackageStore.InstalledPlugin,
    contribution: RuntimePluginManifest.HomeWidgetContribution,
    actions: HostActions,
    revision: Int,
) {
    val store = PluginRuntime.get(actions.activity()).widgetSnapshots()
    val entry = remember(installed.generationDirectory, contribution.id) { store.entry(installed, contribution) }
    DisposableEffect(entry) {
        val observation = store.observe(entry)
        onDispose { observation.close() }
    }
    LaunchedEffect(entry, revision) { store.hostChanged(entry, revision) }
    val color = if (installed.manifest.plugin.kind == "trusted-provider") {
        MaterialTheme.colorScheme.primaryContainer
    } else MaterialTheme.colorScheme.surfaceContainerLow
    val value = entry.value
    val error = entry.error
    RuntimeWidgetCard(contribution, value, color, error)
    SideEffect {
        if (value != null || error != null) entry.presented = true
    }
}

@Composable
private fun RuntimeWidgetCard(
    contribution: RuntimePluginManifest.HomeWidgetContribution,
    value: JSONObject?,
    containerColor: Color,
    refreshError: String?,
) {
    val title = value?.optString("title", contribution.title)
        ?: if (refreshError == null) "—" else "暂时不可用"
    val detail = value?.optString("detail", value.optString("label", ""))
        ?: refreshError ?: "正在读取状态"
    val metric = value?.opt("value")?.takeUnless { it == JSONObject.NULL }?.toString()
        ?: value?.opt("uid")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
    val positive = value?.optBoolean("connected", false) == true || value?.optString("state") == "ready"
    SuiteCard(modifier = Modifier.fillMaxSize(), containerColor = containerColor) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
        ) {
            Text(
                contribution.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null && !positive) {
                SuiteStatusChip("需处理", positive = false)
            }
        }
        when (if (value == null) "status" else contribution.template) {
            "metric" -> Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(metric.ifBlank { "—" }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (value != null && refreshError != null) {
            Text("刷新失败 · 已保留上次内容", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun parseSize(raw: String): HomeWidgetSize? {
    val parts = raw.split('x')
    if (parts.size != 2) return null
    val width = parts[0].toIntOrNull() ?: return null
    val height = parts[1].toIntOrNull() ?: return null
    return runCatching { HomeWidgetSize(width, height) }.getOrNull()
}
