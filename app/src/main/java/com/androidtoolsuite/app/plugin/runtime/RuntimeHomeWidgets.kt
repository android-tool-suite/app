package com.androidtoolsuite.app.plugin.runtime

import android.app.Activity
import android.view.View
import com.androidtoolsuite.app.host.MainActivity
import com.androidtoolsuite.app.host.HomeWidgetInstances
import com.androidtoolsuite.app.host.WidgetConfiguration
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteShapes
import com.androidtoolsuite.app.ui.SuiteStatusChip
import com.androidtoolsuite.app.ui.composePluginView
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest
import org.json.JSONObject
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private interface PreparingHomeWidget:HostHomeWidget { fun prepare():WidgetSnapshotStore.Entry? }
internal object RuntimeHomeWidgets {
    @JvmStatic fun migrateInstance(installed:PluginPackageStore.InstalledPlugin?,instance:HomeWidgetInstances.Instance,activity:MainActivity) {
        val preset=installed?.manifest?.homeWidgetContributions?.find { it.id==instance.presetId } ?: return
        activity.lifecycleScope.launch {
            val configuration=try {
                if(installed.enabled)WidgetConfiguration.read(activity,installed,preset,instance.id,instance.configuration,true).getJSONObject("configuration") else JSONObject()
            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(failure:Exception){JSONObject()}
            // Resolve every legacy instance on first discovery, including hidden cards. Failures remain explicitly unconfigured.
            val current=activity.widgetInstancesForUi().find(instance.pluginId,instance.id)
            if(current?.migrating==true && current.revision==instance.revision) {
                activity.widgetInstancesForUi().save(instance.pluginId,instance.id,preset,configuration,instance.revision)
                activity.invalidateComposeUi()
            }
        }
    }
    @JvmStatic fun createInstance(installed: PluginPackageStore.InstalledPlugin?, instance: HomeWidgetInstances.Instance, activity: MainActivity): HostHomeWidget {
        val preset=installed?.manifest?.homeWidgetContributions?.find { it.id==instance.presetId }
        val displaySize = androidx.compose.runtime.mutableStateOf(HomeWidgetSize(2, 2))
        return object:PreparingHomeWidget {
            override fun setDisplaySize(size: HomeWidgetSize) { displaySize.value = size }
            override fun prepare():WidgetSnapshotStore.Entry? {
                if(installed==null||!installed.enabled||preset==null||instance.migrating)return null
                val store=PluginRuntime.get(activity).widgetSnapshots()
                val entry=store.entry(installed,preset,instance.id,instance.configuration,instance.revision)
                if(entry.persistent)return null
                store.hostChanged(entry,activity.hostStateRevision());return entry
            }
            override fun id()=instance.id
            override fun pluginId()=instance.pluginId
            override fun title()=preset?.title ?: instance.title
            override fun supportedSizes()=preset?.sizes?.mapNotNull(::parseSize) ?: listOf(HomeWidgetSize(2,2),HomeWidgetSize(4,2))
            override fun createView(context:Activity,host:HostServices):View=composePluginView(context) {
                val revision=activity.uiStateForUi().revision
                if(installed==null || !installed.enabled || preset==null) {
                    Surface(Modifier.fillMaxSize(),shape=SuiteShapes.Card,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(SuiteSpacing.lg),verticalArrangement=Arrangement.spacedBy(SuiteSpacing.sm)) {
                            Text(instance.title,style=MaterialTheme.typography.labelLarge)
                            Text(if(installed==null) "插件未安装" else if(!installed.enabled) "插件已停用" else "预设暂不可用")
                            Text("已保留此卡片配置",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if(instance.migrating) {
                    var error by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                    RuntimeWidgetCard(preset,JSONObject().put("title",if(error==null) "正在恢复主页配置" else "请长按配置小部件").put("detail",error ?: "固定已有账号选择"),MaterialTheme.colorScheme.surfaceContainerLow,null)
                } else RuntimeWidgetContent(installed,preset,activity,revision,instance,displaySize.value)
            }
        }
    }
    @JvmStatic
    fun prepare(widgets: List<HostHomeWidget>): List<WidgetSnapshotStore.Entry> =
        widgets.filterIsInstance<PreparingHomeWidget>().mapNotNull { it.prepare() }

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
) : PreparingHomeWidget {
    private val displaySize = androidx.compose.runtime.mutableStateOf(HomeWidgetSize(2, 2))
    override fun setDisplaySize(size: HomeWidgetSize) { displaySize.value = size }
    override fun prepare(): WidgetSnapshotStore.Entry? {
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
        RuntimeWidgetContent(installed, contribution, actions, revision.intValue, displaySize = displaySize.value)
    }
}

@Composable
private fun RuntimeWidgetContent(
    installed: PluginPackageStore.InstalledPlugin,
    contribution: RuntimePluginManifest.HomeWidgetContribution,
    actions: HostActions,
    revision: Int,
    instance: HomeWidgetInstances.Instance? = null,
    displaySize: HomeWidgetSize = HomeWidgetSize(2, 2),
) {
    val store = PluginRuntime.get(actions.activity()).widgetSnapshots()
    val entry = remember(installed.generationDirectory, contribution.id, instance?.id, instance?.revision) { store.entry(installed, contribution, instance?.id ?: contribution.id, instance?.configuration ?: JSONObject(), instance?.revision ?: 0) }
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
    RuntimeWidgetCard(contribution, value, color, error, displaySize.widthUnits == 4, displaySize)
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
    wide: Boolean = false,
    displaySize: HomeWidgetSize = HomeWidgetSize(2, 2),
) {
    val rawTitle = value?.optString("title", contribution.title)
        ?: if (refreshError == null) "—" else "暂时不可用"
    val title = rawTitle.removePrefix(contribution.title).trim().trimStart('·', ':', '：').trim().ifBlank { rawTitle }
    val detail = value?.optString("detail", value.optString("label", ""))
        ?: refreshError ?: "正在读取状态"
    val metric = value?.opt("value")?.takeUnless { it == JSONObject.NULL }?.toString()
        ?: value?.opt("uid")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
    val progress = normalizedWidgetProgress(value)
    if (displaySize.widthUnits == 1 || displaySize.heightUnits == 1) {
        // Compact status cards show their state; transport UIDs are secondary diagnostics.
        val compactMetric = when {
            contribution.template == "status" && value?.opt("value") is Number && detail.isNotBlank() -> detail
            contribution.template == "status" && value?.has("value") != true -> title
            else -> metric.ifBlank { title }
        }
        val compactDetail = if (compactMetric == detail) title else detail
        Surface(Modifier.fillMaxSize(), shape = SuiteShapes.Inner, color = containerColor) {
            Column(Modifier.fillMaxSize().padding(SuiteSpacing.sm), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs, Alignment.CenterVertically)) {
                Text(contribution.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(compactMetric, style = if (displaySize.widthUnits == 1) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge, maxLines = if (displaySize.heightUnits > 1) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (displaySize.heightUnits > 1 && compactDetail.isNotBlank()) Text(compactDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    Surface(modifier = Modifier.fillMaxSize(), shape = SuiteShapes.Card, color = containerColor) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(SuiteSpacing.lg)) {
            val compact = maxHeight < 150.dp
            val largeText = compact && LocalDensity.current.fontScale > 1.3f
            val narrowMetric = maxWidth < 150.dp && LocalDensity.current.fontScale > 1f
            val statusDetailLines = if (largeText && maxHeight.value < 68 * LocalDensity.current.fontScale + 8) 1 else 2
            val wideMetricStyle=if(maxWidth < (320 * LocalDensity.current.fontScale).dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(
                    contribution.title,
                    Modifier.fillMaxWidth(),
                    style = if (largeText) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (wide && contribution.template == "trend" && value != null) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                            Text(metric.ifBlank { "—" }, style = wideMetricStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                            val points = value.optJSONArray("points")
                            if (points == null || points.length() == 0) Text("暂无历史", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                WidgetSparkline(value, Modifier.fillMaxWidth().weight(1f))
                                val dates = java.text.SimpleDateFormat("MM/dd", Locale.CHINA)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = if(points.length()==1) Arrangement.Center else Arrangement.SpaceBetween) {
                                    Text(dates.format(java.util.Date(points.getJSONObject(0).getLong("timestamp"))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (points.length() > 1) Text(dates.format(java.util.Date(points.getJSONObject(points.length()-1).getLong("timestamp"))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    if (value != null && contribution.template == "rows") {
                        Text(title,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        val rows=value.optJSONArray("rows")
                        if((rows?.length() ?: 0)>2) {
                            (0 until rows!!.length()).toList().chunked(2).forEach { pair ->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SuiteSpacing.md)) {
                                    pair.forEach { i -> val row=rows.getJSONObject(i)
                                        Column(Modifier.weight(1f)) {
                                            Text(row.optString("label"),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                            Text(row.optString("value"),style=MaterialTheme.typography.labelLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        } else for(i in 0 until (rows?.length() ?: 0)) {
                            val row=rows!!.getJSONObject(i)
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(row.optString("label"),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(row.optString("value"),style=MaterialTheme.typography.labelLarge,maxLines=1)
                            }
                        }
                        if(detail.isNotBlank() && !largeText) Text(detail,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    } else if (value != null && contribution.template in listOf("metric","trend")) {
                        Text(metric.ifBlank { "—" }, style = if (largeText) MaterialTheme.typography.titleMedium else if (narrowMetric) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (largeText && detail.isNotBlank()) "$title · $detail" else title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (progress != null && !largeText) LinearProgressIndicator(progress = { (progress.first / progress.second).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
                        if (detail.isNotBlank() && !largeText) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if(contribution.template=="trend") WidgetSparkline(value,Modifier.fillMaxWidth().weight(1f))
                    } else {
                        Text(title, style = if (largeText) MaterialTheme.typography.bodyMedium else if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = statusDetailLines, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (value != null && refreshError != null) Text("刷新失败 · 保留上次内容", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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

@Composable private fun WidgetSparkline(value:JSONObject,modifier:Modifier) {
    val points=value.optJSONArray("points") ?: org.json.JSONArray()
    val color=MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription="RKS 趋势图，${points.length()} 个历史节点" }) {
        if(points.length()==0)return@Canvas
        val data=(0 until points.length()).map { points.getJSONObject(it).let { p->p.getDouble("timestamp") to p.getDouble("value") } }
        val first=data.first().first;val span=(data.last().first-first).coerceAtLeast(1.0)
        val min=data.minOf { it.second };val max=data.maxOf { it.second };val extent=(max-min).coerceAtLeast(.0001)
        val locations=data.map { Offset((3+(size.width-6)*(it.first-first)/span).toFloat(),(size.height-3-(size.height-6)*(if(max==min).5 else (it.second-min)/extent)).toFloat()) }
        if(locations.size==1)drawCircle(color,3.dp.toPx(),Offset(size.width/2,size.height/2))
        else { val path=Path();locations.forEachIndexed { index,p->if(index==0)path.moveTo(p.x,p.y) else path.lineTo(p.x,p.y) };drawPath(path,color,style=Stroke(2.dp.toPx())) }
    }
}
