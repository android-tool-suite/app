package com.example.shizukuaccessibilitygrant.host

import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor
import com.example.shizukuaccessibilitygrant.ui.EmptyState
import com.example.shizukuaccessibilitygrant.ui.Notice
import com.example.shizukuaccessibilitygrant.ui.SectionHeader
import com.example.shizukuaccessibilitygrant.ui.SuiteCard
import com.example.shizukuaccessibilitygrant.ui.SuiteTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

private const val DASHBOARD = 0
private const val PLUGINS = 1
private const val MANAGER = 2

/** State bridge for Java-host callbacks. MutableState is observed by Compose directly. */
class HostUiState {
    private val liveListStates = mutableMapOf<String, LazyListState>()
    var revision: Int by mutableIntStateOf(0)
        private set

    fun bump() {
        Snapshot.withMutableSnapshot { revision++ }
    }

    fun attachListState(page: String, state: LazyListState) {
        liveListStates[page] = state
    }

    fun captureScrollPositions(activity: MainActivity) {
        liveListStates.forEach { (page, state) ->
            activity.saveScrollPositionForUi(
                page,
                state.firstVisibleItemIndex,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
}

@Composable
private fun rememberPageListState(activity: MainActivity, page: String): LazyListState {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = activity.scrollIndexForUi(page),
        initialFirstVisibleItemScrollOffset = activity.scrollOffsetForUi(page),
    )
    activity.uiStateForUi().attachListState(page, state)
    LaunchedEffect(state, page) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> activity.saveScrollPositionForUi(page, index, offset) }
    }
    return state
}

fun createHostAppView(activity: MainActivity): View {
    activity.enableEdgeToEdge()
    return ComposeView(activity).apply {
        setContent { SuiteTheme { HostApp(activity) } }
    }
}

private data class Destination(val section: Int, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination(DASHBOARD, "主页", Icons.Rounded.Home),
    Destination(PLUGINS, "工具", Icons.Rounded.Apps),
    Destination(MANAGER, "管理", Icons.Rounded.Tune),
)

@Composable
private fun HostApp(activity: MainActivity) {
    val refreshVersion = activity.uiStateForUi().revision
    BackHandler(enabled = activity.canHandleBackForUi()) {
        activity.handleBackForUi()
    }

    // Keep the Java host's invalidation state observable to Compose without replacing the
    // root view, which would reset LazyColumn scroll positions after every action.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { stateDescription = "host-refresh-$refreshVersion" },
    ) {
        val expanded = maxWidth >= 840.dp
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(activity)
                AppContent(activity, refreshVersion, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { AppNavigationBar(activity) },
            ) { padding -> AppContent(activity, refreshVersion, Modifier.padding(padding)) }
        }
    }
}

@Composable
private fun AppNavigationBar(activity: MainActivity) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = activity.currentSectionForUi() == destination.section,
                onClick = { activity.navigateForUi(destination.section) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(activity: MainActivity) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            Box(Modifier.padding(vertical = 20.dp).size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = activity.currentSectionForUi() == destination.section,
                onClick = { activity.navigateForUi(destination.section) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun AppContent(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val selected = activity.selectedPluginForUi()
    when {
        activity.currentSectionForUi() == DASHBOARD -> DashboardScreen(activity, refreshVersion, modifier.fillMaxSize())
        activity.currentSectionForUi() == MANAGER -> ManagerScreen(activity, refreshVersion, modifier.fillMaxSize())
        selected != null -> PluginDetailScreen(activity, selected, refreshVersion, modifier.fillMaxSize())
        else -> PluginListScreen(activity, refreshVersion, modifier.fillMaxSize())
    }
}

@Composable
private fun DashboardScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val plugins = activity.pluginsForUi()
    val widgets = activity.widgetsForUi()
    val allWidgets = activity.allWidgetsForUi()
    val externalCount = plugins.count { it.removable() }
    val editing = activity.isDashboardEditModeForUi()
    val listState = rememberPageListState(activity, "dashboard")

    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "dashboard-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("工具台", style = MaterialTheme.typography.headlineLarge)
            Text(
                "集中查看运行状态，并快速进入常用工具。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SuiteCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Rounded.Dashboard, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Column {
                        Text("${plugins.size} 个工具已就绪", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${plugins.size - externalCount} 个内置 · $externalCount 个外部 · ${widgets.size} 个主页组件",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            SectionHeader(
                "主页小部件",
                if (editing) "长按并拖动卡片可调整顺序" else "长按任一小部件进入编辑模式",
            ) {
                IconButton(onClick = { activity.setDashboardEditModeForUi(!editing) }) {
                    Icon(if (editing) Icons.Rounded.Close else Icons.Rounded.Edit, if (editing) "完成编辑" else "编辑主页")
                }
            }
        }
        if (editing) item { Notice("桌面编辑态：长按卡片后拖动排序；右下角可调整尺寸，开关控制是否显示。") }
        if (!editing && widgets.isEmpty()) {
            item { EmptyState("主页很清爽", "点击右上角编辑，添加工具提供的小部件。") }
        } else if ((if (editing) allWidgets else widgets).isNotEmpty()) {
            item {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 12.dp
                    val unit = (maxWidth - gap * 3) / 4
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        val displayedWidgets = if (editing) allWidgets else widgets
                        widgetRows(displayedWidgets, activity).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                row.forEach { widget ->
                                    val width = activity.widgetWidthUnitsForUi(widget)
                                    val height = activity.widgetHeightUnitsForUi(widget)
                                    key(widget.pluginId() + ":" + widget.id()) {
                                        WidgetTile(
                                            activity = activity,
                                            widget = widget,
                                            modifier = Modifier
                                                .weight(width.toFloat())
                                                .height(unit * height + gap * (height - 1)),
                                            onClick = { activity.openPluginForUi(widget.pluginId()) },
                                            onLongPress = { activity.setDashboardEditModeForUi(true) },
                                            editing = editing,
                                        )
                                    }
                                }
                                val remaining = 4 - row.sumOf { activity.widgetWidthUnitsForUi(it) }
                                if (remaining > 0) Spacer(Modifier.weight(remaining.toFloat()))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun widgetRows(widgets: List<HomeWidget>, activity: MainActivity): List<List<HomeWidget>> {
    val rows = mutableListOf<List<HomeWidget>>()
    val pending = mutableListOf<HomeWidget>()
    widgets.forEach { widget ->
        val width = activity.widgetWidthUnitsForUi(widget)
        val used = pending.sumOf { activity.widgetWidthUnitsForUi(it) }
        if (pending.isNotEmpty() && used + width > 4) {
            rows += pending.toList()
            pending.clear()
        }
        pending += widget
        if (pending.sumOf { activity.widgetWidthUnitsForUi(it) } == 4) {
            rows += pending.toList()
            pending.clear()
        }
    }
    if (pending.isNotEmpty()) rows += pending.toList()
    return rows
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetTile(
    activity: MainActivity,
    widget: HomeWidget,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    editing: Boolean,
) {
    SuiteCard(
        modifier = modifier.then(
            if (editing) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))
            else Modifier,
        ).then(
            if (editing) Modifier.reorderDrag(widget.pluginId() + widget.id(), columns = 2) {
                activity.moveWidgetForUi(widget, it)
            } else Modifier,
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(widget.title(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            PluginAndroidView(Modifier.fillMaxSize()) { widget.createView(activity, activity) }
            if (!editing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .combinedClickable(onClick = onClick, onLongClick = onLongPress),
                )
            }
            if (editing) BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = activity.widgetWidthUnitsForUi(widget)
                val height = activity.widgetHeightUnitsForUi(widget)
                val showSizeText = maxWidth >= 150.dp
                val sizeIndex = activity.widgetSizeIndexForUi(widget)
                val sizeCount = activity.widgetSizeCountForUi(widget)
                Switch(
                    checked = activity.isWidgetVisibleForUi(widget),
                    onCheckedChange = { activity.setWidgetVisibleForUi(widget, it) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { activity.changeWidgetSizeForUi(widget, -1) },
                        enabled = sizeIndex > 0,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "上一个组件规格")
                    }
                    if (showSizeText) {
                        Text("${width}×${height} 格", style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(
                        onClick = { activity.changeWidgetSizeForUi(widget, 1) },
                        enabled = sizeIndex < sizeCount - 1,
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "下一个组件规格")
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginListScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val plugins = activity.pluginsForUi()
    val allTools = activity.allToolsForUi()
    val editing = activity.isToolEditModeForUi()
    val listState = rememberPageListState(activity, "tools")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "tools-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("工具", style = MaterialTheme.typography.headlineLarge)
                    Text(if (editing) "长按并上下拖动卡片可调整顺序" else "长按工具卡片可进入编辑模式。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { activity.setToolEditModeForUi(!editing) }) {
                    Icon(if (editing) Icons.Rounded.Close else Icons.Rounded.Edit, if (editing) "完成编辑" else "编辑工具")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (editing) {
            item { Notice("长按并上下拖动卡片可调整顺序；隐藏的工具仍已安装，可随时重新显示。") }
            items(allTools, key = ToolPlugin::id) { plugin -> ToolEditorCard(activity, plugin, allTools.indexOf(plugin), allTools.size) }
        } else {
            if (plugins.isEmpty()) item { EmptyState("没有显示中的工具", "点击右上角编辑，重新显示已安装工具。") }
            items(plugins, key = ToolPlugin::id) { plugin ->
                PluginListCard(plugin, { activity.openPluginForUi(plugin) }, { activity.setToolEditModeForUi(true) })
            }
        }
    }
}

@Composable
private fun ToolEditorCard(activity: MainActivity, plugin: ToolPlugin, index: Int, size: Int) {
    SuiteCard(modifier = Modifier.reorderDrag(plugin.id()) { activity.moveToolForUi(plugin.id(), it) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plugin.title(), style = MaterialTheme.typography.titleMedium)
                Text(plugin.description(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(activity.isToolVisibleForUi(plugin), { activity.setToolVisibleForUi(plugin, it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { activity.moveToolForUi(plugin.id(), -1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "向前移动") }
            IconButton(onClick = { activity.moveToolForUi(plugin.id(), 1) }, enabled = index < size - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "向后移动") }
            Text("显示顺序 ${index + 1}", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginListCard(plugin: ToolPlugin, onOpen: () -> Unit, onLongPress: () -> Unit) {
    SuiteCard(
        modifier = Modifier
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(pluginIcon(plugin), null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plugin.title(), style = MaterialTheme.typography.titleLarge)
                Text(plugin.description(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onOpen, label = { Text("v${plugin.version()}") })
                    if (plugin.requestedPermissions().isNotEmpty()) AssistChip(onClick = onOpen, label = { Text("${plugin.requestedPermissions().size} 项权限") })
                }
            }
        }
    }
}

@Composable
private fun Modifier.reorderDrag(key: String, columns: Int = 1, onMove: (Int) -> Unit): Modifier {
    var offsetX by remember(key) { mutableFloatStateOf(0f) }
    var offsetY by remember(key) { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { 144.dp.toPx() }
    return this
        .zIndex(if (offsetX != 0f || offsetY != 0f) 10f else 0f)
        .graphicsLayer {
            translationX = offsetX
            translationY = offsetY
            shadowElevation = if (offsetX != 0f || offsetY != 0f) 18f else 0f
            scaleX = if (offsetX != 0f || offsetY != 0f) 1.03f else 1f
            scaleY = scaleX
        }
        .pointerInput(key, columns) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                offsetX = 0f
                offsetY = 0f
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - change.previousPosition
                    offsetX += delta.x
                    offsetY += delta.y
                    pressed = change.pressed
                    change.consume()
                }
                val horizontal = (offsetX / step).roundToInt()
                val vertical = (offsetY / step).roundToInt() * columns
                val movement = if (abs(offsetX) > abs(offsetY)) horizontal else vertical
                offsetX = 0f
                offsetY = 0f
                if (movement != 0) onMove(movement)
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailScreen(activity: MainActivity, plugin: ToolPlugin, refreshVersion: Int, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.semantics { stateDescription = "plugin-$refreshVersion" },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(plugin.title(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("v${plugin.version()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { activity.navigateForUi(PLUGINS) }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回工具列表")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            PluginAndroidView(Modifier.fillMaxSize()) { plugin.createView(activity, activity) }
        }
    }
}

@Composable
private fun ManagerScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    if (activity.isPermissionCenterOpenForUi()) {
        PermissionCenterScreen(activity, refreshVersion, modifier)
        return
    }
    val optionalBuiltIns = activity.optionalBuiltInPlugins()
    val imported = activity.importedDescriptorsForUi()
    val listState = rememberPageListState(activity, "manager")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "manager-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("插件管理", style = MaterialTheme.typography.headlineLarge)
                    Text("启用、停用、导入和导出插件。权限在独立中心统一管理。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = activity::showPermissionCenterForUi) {
                    Icon(Icons.Rounded.Key, "打开权限中心")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = activity::importPlugin) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("导入插件")
                }
                FilledTonalButton(onClick = activity::showPermissionCenterForUi) { Text("权限中心") }
            }
        }
        item { Notice("插件包中的代码只应来自你信任的来源。授权 Shizuku、Shell 或网络能力前，请先核对插件说明。", warning = true) }
        item { SectionHeader("内置插件", "可选能力可按需停用") }
        if (optionalBuiltIns.isEmpty()) item { EmptyState("没有可选内置插件", "核心宿主能力会始终保持启用。") }
        items(optionalBuiltIns, key = ToolPlugin::id) { plugin -> BuiltInManagerCard(activity, plugin) }
        item { SectionHeader("外部插件", "${imported.size} 个已安装") }
        if (imported.isEmpty()) item { EmptyState("尚未导入插件", "支持 .atsplugin 包与 JSON 清单。") }
        items(imported, key = ImportedPluginDescriptor::id) { descriptor -> ImportedManagerCard(activity, descriptor) }
    }
}

@Composable
private fun BuiltInManagerCard(activity: MainActivity, plugin: ToolPlugin) {
    val enabled = activity.isBuiltInPluginEnabled(plugin.id())
    SuiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plugin.title(), style = MaterialTheme.typography.titleLarge)
                Text(plugin.description(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { activity.setBuiltInPluginEnabled(plugin.id(), it) })
        }
        PluginMetadata(plugin)
    }
}

@Composable
private fun ImportedManagerCard(activity: MainActivity, descriptor: ImportedPluginDescriptor) {
    val enabled = activity.isImportedPluginEnabled(descriptor.id)
    SuiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(descriptor.title, style = MaterialTheme.typography.titleLarge)
                Text("v${descriptor.version} · ${descriptor.author}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = enabled, onCheckedChange = { activity.setImportedPluginEnabled(descriptor.id, it) })
        }
        Text(descriptor.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (descriptor.dependencies.isNotEmpty()) Text("依赖：${descriptor.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (descriptor.requestedPermissions.isNotEmpty()) Text("${descriptor.requestedPermissions.size} 项权限 · 在权限中心统一管理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { activity.exportPlugin(descriptor.id) }, modifier = Modifier.weight(1f)) { Text("导出") }
            OutlinedButton(onClick = { activity.deleteImportedPlugin(descriptor.id) }, modifier = Modifier.weight(1f)) { Text("删除") }
        }
    }
}

@Composable
private fun PermissionCenterScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val imported = activity.importedDescriptorsForUi()
    val listState = rememberPageListState(activity, "permissions")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "permissions-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = activity::closePermissionCenterForUi) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回插件管理") }
                Column(Modifier.weight(1f)) {
                    Text("权限中心", style = MaterialTheme.typography.headlineLarge)
                    Text("按能力集中审查外部插件请求。修改后会立即重新加载对应插件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Notice("只授权确有必要的能力。Shell、无障碍设置和应用列表属于高敏感权限。", warning = true) }
        if (imported.isEmpty()) item { EmptyState("没有外部插件", "导入插件后，会在这里集中显示其权限请求。") }
        items(imported, key = ImportedPluginDescriptor::id) { descriptor ->
            PermissionPluginCard(activity, descriptor)
        }
    }
}

@Composable
private fun PermissionPluginCard(activity: MainActivity, descriptor: ImportedPluginDescriptor) {
    SuiteCard {
        Text(descriptor.title, style = MaterialTheme.typography.titleLarge)
        Text(descriptor.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (descriptor.requestedPermissions.isEmpty()) {
            Text("该插件未请求额外权限。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            descriptor.requestedPermissions.forEach { permission ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = activity.hasImportedPluginPermission(descriptor.id, permission),
                        onCheckedChange = { activity.setImportedPluginPermission(descriptor.id, permission, it) },
                        enabled = !activity.isImportedPluginEnabled(descriptor.id),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(PluginPermissionCatalog.label(permission), style = MaterialTheme.typography.bodyLarge)
                        Text(PluginPermissionCatalog.description(permission), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginMetadata(plugin: ToolPlugin) {
    val text = buildList {
        add("v${plugin.version()}")
        if (plugin.dependencies().isNotEmpty()) add("${plugin.dependencies().size} 项依赖")
        if (plugin.requestedPermissions().isNotEmpty()) add("${plugin.requestedPermissions().size} 项权限")
    }.joinToString(" · ")
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

private fun pluginIcon(plugin: ToolPlugin): ImageVector = when {
    plugin.id().contains("shizuku") -> Icons.Rounded.Security
    plugin.id().contains("host") -> Icons.Rounded.Settings
    else -> Icons.Rounded.Extension
}

@Composable
private fun PluginAndroidView(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    factory: () -> View,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = {
            factory().also { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                if (onClick != null) {
                    view.isClickable = true
                    view.setOnClickListener { onClick() }
                }
            }
        },
    )
}
