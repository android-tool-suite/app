package com.androidtoolsuite.app.host

import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import com.androidtoolsuite.app.plugin.api.HomeWidget
import com.androidtoolsuite.app.plugin.api.ToolPlugin
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.ui.EmptyState
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SectionHeader
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        val selectedSection = activity.currentSectionForUi()
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                AppNavigationRail(activity, selectedSection)
                AppContent(activity, refreshVersion, Modifier.weight(1f))
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = { AppNavigationBar(activity, selectedSection) },
            ) { padding -> AppContent(activity, refreshVersion, Modifier.padding(padding)) }
        }
    }
}

@Composable
private fun AppNavigationBar(activity: MainActivity, selectedSection: Int) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedSection == destination.section,
                onClick = { activity.navigateForUi(destination.section) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun AppNavigationRail(activity: MainActivity, selectedSection: Int) {
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
                selected = selectedSection == destination.section,
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
        selected != null -> PluginDetailScreen(activity, selected, refreshVersion, modifier.fillMaxSize())
        activity.currentSectionForUi() == DASHBOARD -> DashboardScreen(activity, refreshVersion, modifier.fillMaxSize())
        activity.currentSectionForUi() == MANAGER -> ManagerScreen(activity, refreshVersion, modifier.fillMaxSize())
        else -> PluginListScreen(activity, refreshVersion, modifier.fillMaxSize())
    }
}

@Composable
private fun DashboardScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val plugins = activity.pluginsForUi()
    val widgets = activity.widgetsForUi()
    val externalCount = plugins.count { it.removable() }
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
        item { SectionHeader("主页小部件", "拖动时虚影预览落点；长按后松开可调整或隐藏") }
        if (widgets.isEmpty()) {
            item { EmptyState("主页很清爽", "可在插件管理的界面管理中重新显示小部件。") }
        } else {
            item {
                WidgetGrid(widgets, activity, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun widgetKey(widget: HomeWidget) = widget.pluginId() + ":" + widget.id()

private data class ReorderSlot(
    val key: String,
    val index: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val centerX get() = left + width / 2f
    val centerY get() = top + height / 2f

    fun contains(x: Float, y: Float) = x >= left && x <= left + width && y >= top && y <= top + height
}

private class DropPreviewState {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    var targetIndex by mutableIntStateOf(-1)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var baseSlots: List<ReorderSlot> = emptyList()
    private var frozenSlots: List<ReorderSlot> = emptyList()

    fun updateBaseSlots(slots: List<ReorderSlot>) {
        if (draggedKey == null) baseSlots = slots
    }

    fun start(key: String, fallbackIndex: Int) {
        frozenSlots = baseSlots.toList()
        val source = frozenSlots.firstOrNull { it.key == key }
        targetIndex = source?.index ?: fallbackIndex
        offsetX = 0f
        offsetY = 0f
        draggedKey = key
    }

    fun dragBy(dx: Float, dy: Float) {
        val source = frozenSlots.firstOrNull { it.key == draggedKey } ?: return
        offsetX += dx
        offsetY += dy
        val centerX = source.centerX + offsetX
        val centerY = source.centerY + offsetY
        val target = frozenSlots.firstOrNull { it.contains(centerX, centerY) }
            ?: frozenSlots.minByOrNull {
                val deltaX = centerX - it.centerX
                val deltaY = centerY - it.centerY
                deltaX * deltaX + deltaY * deltaY
            }
        if (target != null) targetIndex = target.index
    }

    fun sourceSlot(): ReorderSlot? = frozenSlots.firstOrNull { it.key == draggedKey }

    fun finish(): Int {
        val result = targetIndex
        reset()
        return result
    }

    fun cancel() = reset()

    private fun reset() {
        draggedKey = null
        targetIndex = -1
        offsetX = 0f
        offsetY = 0f
        frozenSlots = emptyList()
    }
}

@Composable
private fun DropPreview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .zIndex(20f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.82f), shape),
    )
}

@Composable
private fun WidgetGrid(widgets: List<HomeWidget>, activity: MainActivity, modifier: Modifier = Modifier) {
    val gap = 12.dp
    val orderKey = widgets.joinToString("\n") { widgetKey(it) }
    val dragState = remember(orderKey) { DropPreviewState() }
    val draggedKey = dragState.draggedKey
    val draggedIndex = widgets.indexOfFirst { widgetKey(it) == draggedKey }
    val targetIndex = dragState.targetIndex.coerceIn(0, (widgets.size - 1).coerceAtLeast(0))
    Layout(
        modifier = modifier,
        content = {
            widgets.forEachIndexed { index, widget ->
                key(widgetKey(widget)) {
                    WidgetTile(
                        activity = activity,
                        widget = widget,
                        modifier = Modifier,
                        onClick = { activity.openPluginForUi(widget.pluginId()) },
                        dragState = dragState,
                        sourceIndex = index,
                        onDrop = { destination ->
                            widgets.getOrNull(destination)?.let { target ->
                                if (target !== widget) activity.moveWidgetToUi(widget, target)
                            }
                        },
                    )
                }
            }
            if (draggedIndex >= 0) {
                DropPreview()
            }
        },
    ) { measurables, constraints ->
        val gridWidth = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val unitWidth = ((gridWidth - gapPx * 3).coerceAtLeast(0)) / 4f
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val positions = Array(measurables.size) { IntArray(2) }
        val logicalIndices = widgets.indices.toMutableList()
        val placeholderIndex = widgets.size
        if (draggedIndex >= 0) {
            logicalIndices.remove(draggedIndex)
            logicalIndices.add(targetIndex.coerceAtMost(logicalIndices.size), -1)
        }
        val rowMeasurableIndices = mutableListOf<Int>()
        var usedUnits = 0
        var rowHeight = 0
        var y = 0

        fun finishRow() {
            rowMeasurableIndices.forEach { positions[it][1] = y }
            if (rowMeasurableIndices.isNotEmpty()) y += rowHeight + gapPx
            rowMeasurableIndices.clear()
            usedUnits = 0
            rowHeight = 0
        }

        logicalIndices.forEach { logicalIndex ->
            val isPlaceholder = logicalIndex < 0
            val widgetIndex = if (isPlaceholder) draggedIndex else logicalIndex
            val measurableIndex = if (isPlaceholder) placeholderIndex else widgetIndex
            val widget = widgets[widgetIndex]
            val widthUnits = activity.widgetWidthUnitsForUi(widget).coerceIn(1, 4)
            if (usedUnits > 0 && usedUnits + widthUnits > 4) finishRow()

            val itemWidth = (unitWidth * widthUnits + gapPx * (widthUnits - 1)).roundToInt()
            val heightUnits = activity.widgetHeightUnitsForUi(widget).coerceAtLeast(1)
            val itemHeight = (72.dp * heightUnits + gap * (heightUnits - 1)).roundToPx()
            val placeable = measurables[measurableIndex].measure(Constraints.fixed(itemWidth, itemHeight))
            placeables[measurableIndex] = placeable
            positions[measurableIndex][0] = ((unitWidth + gapPx) * usedUnits).roundToInt()
            rowMeasurableIndices += measurableIndex
            usedUnits += widthUnits
            rowHeight = maxOf(rowHeight, placeable.height)
            if (usedUnits == 4) finishRow()
        }
        if (rowMeasurableIndices.isNotEmpty()) finishRow()

        if (draggedIndex >= 0) {
            val widget = widgets[draggedIndex]
            val sourceSlot = dragState.sourceSlot()
            val itemWidth = sourceSlot?.width ?: gridWidth
            val itemHeight = sourceSlot?.height ?: (72.dp * activity.widgetHeightUnitsForUi(widget)).roundToPx()
            placeables[draggedIndex] = measurables[draggedIndex].measure(Constraints.fixed(itemWidth, itemHeight))
            positions[draggedIndex][0] = sourceSlot?.left ?: 0
            positions[draggedIndex][1] = sourceSlot?.top ?: 0
        } else {
            dragState.updateBaseSlots(
                widgets.indices.map { index ->
                    val placeable = requireNotNull(placeables[index])
                    ReorderSlot(
                        key = widgetKey(widgets[index]),
                        index = index,
                        left = positions[index][0],
                        top = positions[index][1],
                        width = placeable.width,
                        height = placeable.height,
                    )
                },
            )
        }
        val gridHeight = (y - gapPx).coerceAtLeast(0)

        layout(gridWidth, gridHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable?.placeRelative(
                    positions[index][0],
                    positions[index][1],
                    zIndex = when (index) {
                        placeholderIndex -> 20f
                        draggedIndex -> 10f
                        else -> 0f
                    },
                )
            }
        }
    }
}

@Composable
private fun WidgetTile(
    activity: MainActivity,
    widget: HomeWidget,
    modifier: Modifier,
    onClick: () -> Unit,
    dragState: DropPreviewState,
    sourceIndex: Int,
    onDrop: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    var menuExpanded by remember(widget.pluginId(), widget.id()) { mutableStateOf(false) }
    val width = activity.widgetWidthUnitsForUi(widget)
    val height = activity.widgetHeightUnitsForUi(widget)
    Box(
        modifier = modifier
            .dropPreviewReorder(
                key = widget.pluginId() + ":" + widget.id(),
                onClick = onClick,
                onLongPress = { menuExpanded = true },
                dragState = dragState,
                sourceIndex = sourceIndex,
                onDrop = onDrop,
                allowHorizontalDrag = true,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        PluginAndroidView(Modifier.fillMaxSize()) { widget.createView(activity, activity) }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(widget.pluginId(), widget.id()) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            widget.supportedSizes().forEach { size ->
                DropdownMenuItem(
                    text = { Text("尺寸 ${size.widthUnits}×${size.heightUnits}") },
                    leadingIcon = {
                        if (width == size.widthUnits && height == size.heightUnits) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    },
                    onClick = {
                        activity.setWidgetSizeForUi(widget, size.widthUnits, size.heightUnits)
                        menuExpanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("不在主页显示") },
                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                onClick = {
                    activity.setWidgetVisibleForUi(widget, false)
                    menuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun Modifier.dropPreviewReorder(
    key: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    dragState: DropPreviewState,
    sourceIndex: Int,
    onDrop: (Int) -> Unit,
    allowHorizontalDrag: Boolean,
): Modifier {
    val dragging = dragState.draggedKey == key
    val viewConfiguration = LocalViewConfiguration.current
    return this
        .zIndex(if (dragging) 10f else 0f)
        .graphicsLayer {
            translationX = if (dragging && allowHorizontalDrag) dragState.offsetX else 0f
            translationY = if (dragging) dragState.offsetY else 0f
            alpha = if (dragging) 0.94f else 1f
        }
        .pointerInput(key) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var pointer = down
                var maxDistance = 0f
                while (pointer.pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    maxDistance = maxOf(maxDistance, (pointer.position - down.position).getDistance())
                }
                if (maxDistance <= viewConfiguration.touchSlop) {
                    if (pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) onLongPress()
                    else onClick()
                }
            }
        }
        .pointerInput(key, sourceIndex, allowHorizontalDrag) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragState.start(key, sourceIndex) },
                onDragCancel = dragState::cancel,
                onDragEnd = { onDrop(dragState.finish()) },
            ) { change, dragAmount ->
                change.consume()
                dragState.dragBy(if (allowHorizontalDrag) dragAmount.x else 0f, dragAmount.y)
            }
        }
}

@Composable
private fun PluginListScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val plugins = activity.pluginsForUi()
    val listState = rememberPageListState(activity, "tools")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "tools-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("工具", style = MaterialTheme.typography.headlineLarge)
            Text("点击打开；拖动时虚影预览落点，松手后保存排序", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        if (plugins.isEmpty()) item { EmptyState("没有可用工具", "请先在插件管理中启用插件。") }
        if (plugins.isNotEmpty()) item { ToolReorderList(activity, plugins, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ToolReorderList(
    activity: MainActivity,
    plugins: List<ToolPlugin>,
    modifier: Modifier = Modifier,
) {
    val gap = 12.dp
    val orderKey = plugins.joinToString("\n", transform = ToolPlugin::id)
    val dragState = remember(orderKey) { DropPreviewState() }
    val draggedIndex = plugins.indexOfFirst { it.id() == dragState.draggedKey }
    val targetIndex = dragState.targetIndex.coerceIn(0, (plugins.size - 1).coerceAtLeast(0))
    Layout(
        modifier = modifier,
        content = {
            plugins.forEachIndexed { index, plugin ->
                key(plugin.id()) {
                    PluginListCard(
                        plugin = plugin,
                        onOpen = { activity.openPluginForUi(plugin) },
                        modifier = Modifier.dropPreviewReorder(
                            key = plugin.id(),
                            onClick = { activity.openPluginForUi(plugin) },
                            onLongPress = {},
                            dragState = dragState,
                            sourceIndex = index,
                            onDrop = { destination ->
                                plugins.getOrNull(destination)?.let { target ->
                                    if (target !== plugin) activity.moveToolToUi(plugin.id(), target.id())
                                }
                            },
                            allowHorizontalDrag = false,
                        ),
                    )
                }
            }
            if (draggedIndex >= 0) DropPreview()
        },
    ) { measurables, constraints ->
        val listWidth = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val placeholderIndex = plugins.size
        val logicalIndices = plugins.indices.toMutableList()
        if (draggedIndex >= 0) {
            logicalIndices.remove(draggedIndex)
            logicalIndices.add(targetIndex.coerceAtMost(logicalIndices.size), -1)
        }
        val placeables = arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
        val positions = Array(measurables.size) { IntArray(2) }
        var y = 0

        logicalIndices.forEach { logicalIndex ->
            val isPlaceholder = logicalIndex < 0
            val measurableIndex = if (isPlaceholder) placeholderIndex else logicalIndex
            val placeable = if (isPlaceholder) {
                val height = dragState.sourceSlot()?.height ?: 132.dp.roundToPx()
                measurables[measurableIndex].measure(Constraints.fixed(listWidth, height))
            } else {
                measurables[measurableIndex].measure(Constraints(minWidth = listWidth, maxWidth = listWidth))
            }
            placeables[measurableIndex] = placeable
            positions[measurableIndex][1] = y
            y += placeable.height + gapPx
        }

        if (draggedIndex >= 0) {
            val sourceSlot = dragState.sourceSlot()
            placeables[draggedIndex] = measurables[draggedIndex].measure(Constraints(minWidth = listWidth, maxWidth = listWidth))
            positions[draggedIndex][1] = sourceSlot?.top ?: 0
        } else {
            dragState.updateBaseSlots(
                plugins.indices.map { index ->
                    val placeable = requireNotNull(placeables[index])
                    ReorderSlot(
                        key = plugins[index].id(),
                        index = index,
                        left = 0,
                        top = positions[index][1],
                        width = placeable.width,
                        height = placeable.height,
                    )
                },
            )
        }

        val listHeight = (y - gapPx).coerceAtLeast(0)
        layout(listWidth, listHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable?.placeRelative(
                    0,
                    positions[index][1],
                    zIndex = when (index) {
                        placeholderIndex -> 20f
                        draggedIndex -> 10f
                        else -> 0f
                    },
                )
            }
        }
    }
}

@Composable
private fun PluginListCard(plugin: ToolPlugin, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    SuiteCard(modifier = modifier) {
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
                }
            }
        }
    }
}

@Composable
private fun PluginDetailScreen(activity: MainActivity, plugin: ToolPlugin, refreshVersion: Int, modifier: Modifier = Modifier) {
    Box(modifier.semantics { stateDescription = "plugin-$refreshVersion" }) {
        PluginAndroidView(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) { plugin.createView(activity, activity) }
        IconButton(
            onClick = activity::closePluginForUi,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回上一页")
        }
    }
}

@Composable
private fun ManagerScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    if (activity.isInterfaceManagementOpenForUi()) {
        InterfaceManagementScreen(activity, refreshVersion, modifier)
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
                    Text("启用、停用、导入和导出插件。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = activity::importPlugin) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.size(8.dp))
                    Text("导入插件")
                }
                OutlinedButton(onClick = activity::showInterfaceManagementForUi) {
                    Icon(Icons.Rounded.Tune, null)
                    Spacer(Modifier.size(8.dp))
                    Text("界面管理")
                }
            }
        }
        item { Notice("插件代码与宿主运行在同一进程，能够使用宿主进程已有的能力。请只安装来自可信来源的插件。", warning = true) }
        item { SectionHeader("内置插件", "可选能力可按需停用") }
        if (optionalBuiltIns.isEmpty()) item { EmptyState("没有可选内置插件", "核心宿主能力会始终保持启用。") }
        items(optionalBuiltIns, key = ToolPlugin::id) { plugin -> BuiltInManagerCard(activity, plugin) }
        item { SectionHeader("外部插件", "${imported.size} 个已安装") }
        if (imported.isEmpty()) item { EmptyState("尚未导入插件", "只支持包含可执行代码的完整 .atsplugin 插件包。") }
        items(imported, key = ImportedPluginDescriptor::id) { descriptor -> ImportedManagerCard(activity, descriptor) }
    }
}

@Composable
private fun InterfaceManagementScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    val tools = activity.allToolsForUi()
    val listState = rememberPageListState(activity, "interface-management")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "interface-management-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = activity::closeInterfaceManagementForUi) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回插件管理")
                }
                Column(Modifier.weight(1f)) {
                    Text("界面管理", style = MaterialTheme.typography.headlineLarge)
                    Text("按插件管理工具页和主页的显示状态。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionHeader("插件显示", "隐藏界面入口不会停用插件") }
        if (tools.isEmpty()) item { EmptyState("没有可管理的插件", "请先在插件管理中启用插件。") }
        items(tools, key = ToolPlugin::id) { plugin -> PluginVisibilityCard(activity, plugin) }
    }
}

@Composable
private fun PluginVisibilityCard(activity: MainActivity, plugin: ToolPlugin) {
    val hasHomeWidgets = activity.hasHomeWidgetsForUi(plugin)
    SuiteCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(plugin.title(), style = MaterialTheme.typography.titleMedium)
            Text(plugin.description(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VisibilitySwitch(
                label = "工具页",
                checked = activity.isToolVisibleForUi(plugin),
                onCheckedChange = { activity.setToolVisibleForUi(plugin, it) },
                modifier = Modifier.weight(1f),
            )
            VisibilitySwitch(
                label = "主页",
                checked = hasHomeWidgets && activity.isPluginHomeVisibleForUi(plugin),
                onCheckedChange = { activity.setPluginHomeVisibleForUi(plugin, it) },
                modifier = Modifier.weight(1f),
                enabled = hasHomeWidgets,
            )
        }
    }
}

@Composable
private fun VisibilitySwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
    val loaded = activity.isPluginLoadedForUi(descriptor.id)
    SuiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(descriptor.title, style = MaterialTheme.typography.titleLarge)
                Text("v${descriptor.version} · ${descriptor.author}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Switch(checked = enabled, onCheckedChange = { activity.setImportedPluginEnabled(descriptor.id, it) })
        }
        Text(descriptor.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (enabled && !loaded) {
            Notice("插件代码未能加载，请重新导入与当前宿主版本匹配的完整插件包。", warning = true)
        }
        if (descriptor.dependencies.isNotEmpty()) Text("依赖：${descriptor.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { activity.exportPlugin(descriptor.id) }, modifier = Modifier.weight(1f)) { Text("导出") }
            OutlinedButton(onClick = { activity.deleteImportedPlugin(descriptor.id) }, modifier = Modifier.weight(1f)) { Text("删除") }
        }
    }
}

@Composable
private fun PluginMetadata(plugin: ToolPlugin) {
    val text = buildList {
        add("v${plugin.version()}")
        if (plugin.dependencies().isNotEmpty()) add("${plugin.dependencies().size} 项依赖")
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
