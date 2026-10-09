@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.androidtoolsuite.app.host

import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.viewinterop.AndroidView
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.runtime.HostTool
import com.androidtoolsuite.app.migration.MigrationBridgeManager
import com.androidtoolsuite.app.migration.DatasetCategory
import com.androidtoolsuite.app.migration.DatasetRestoreMode
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.plugin.runtime.PluginPermissionManager
import com.androidtoolsuite.app.ui.EmptyState
import com.androidtoolsuite.app.ui.ErrorState
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SectionHeader
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteSettingsGroup
import com.androidtoolsuite.app.ui.SuiteSettingsRow
import com.androidtoolsuite.app.ui.SuiteSettingsSwitchRow
import com.androidtoolsuite.app.ui.SuiteShapes
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteStatusChip
import com.androidtoolsuite.app.ui.SuiteTheme
import com.androidtoolsuite.app.ui.SuiteThemePreferences
import com.androidtoolsuite.app.ui.SuiteTheming
import com.androidtoolsuite.app.ui.SuiteTopBar
import com.androidtoolsuite.app.update.UpdateCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
internal fun DashboardScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    val plugins = activity.pluginsForUi()
    val widgets = homeCollections(activity, activity.widgetsForUi())
    val hidden = activity.allWidgetsForUi().filterNot(activity::isWidgetVisibleForUi)
    var arranging by remember { mutableStateOf(false) }
    val dragState = remember { DropPreviewState() }
    val listState = rememberPageListState(activity, "dashboard")
    ReorderAutoScroll(dragState, listState)
    BackHandler(enabled = arranging && !dragState.isDragging) { arranging = false }
    LazyColumn(
        state = listState,
        modifier = modifier.onGloballyPositioned { dragState.viewport = it.boundsInWindow() }
            .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && dragState.isDragging) { dragState.cancel(); true } else false }
            .semantics { stateDescription = "dashboard-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("常用，一眼可见", style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = { activity.editFolderForUi(HostFolders.HOME, null) }) { Icon(Icons.Rounded.CreateNewFolder, "新建文件夹") }
                TextButton(onClick = { arranging = !arranging }, enabled = !dragState.isDragging) { Text(if (arranging) "完成" else "排列") }
                TextButton(onClick = { activity.openWidgetSettingsForUi(null, null) }, enabled = !dragState.isDragging) { Text("小部件") }
            }
        }
        if (arranging) item { ReorderHint() }
        if (activity.availablePluginUpdatesForUi().isNotEmpty()) item {
            SuiteCard(modifier = Modifier.clickable(onClick = activity::showAvailableUpdatesForUi)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                    Icon(Icons.Rounded.FileUpload, null, tint = MaterialTheme.colorScheme.primary)
                    Text("${activity.availablePluginUpdatesForUi().size} 个插件可以更新", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        activity.folderErrorForUi()?.let { error -> item { Notice(error, warning = true) } }
        if (widgets.isEmpty()) item {
            EmptyState("放一些你关心的信息", "从已安装插件中选择小部件，随时可以调整顺序。")
            OutlinedButton(onClick = { activity.openWidgetSettingsForUi(null, null) }) { Text("添加小部件") }
        } else item { WidgetGrid(widgets, activity, dragState, arranging, Modifier.fillMaxWidth()) }

    }
}

@Composable
private fun ReorderHint() {
    Text("拖动卡片或手柄排列 · 点击打开移动菜单 · 返回结束排列；拖出内容区或按返回取消本次拖动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun widgetKey(widget: HostHomeWidget) = widget.pluginId() + ":" + widget.id()

internal data class ReorderSlot(
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

internal class DropPreviewState {
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
    private var generation = 0
    val positions = mutableStateMapOf<String, IntOffset>()
    var settling by mutableStateOf(false)
        private set
    var destination = IntOffset.Zero
    var bounds = Rect.Zero
    var viewport = Rect.Zero
    var pointerInsetX = 0f
    var pointerInsetY = 0f
    val isDragging get() = draggedKey != null
    val pointerX get() = bounds.left + (sourceSlot()?.left ?: 0) + offsetX + pointerInsetX
    val pointerY get() = bounds.top + (sourceSlot()?.top ?: 0) + offsetY + pointerInsetY

    fun updateBaseSlots(slots: List<ReorderSlot>) {
        if (draggedKey == null) baseSlots = slots
    }

    fun start(key: String, fallbackIndex: Int, x: Float, y: Float) {
        if (settling) return
        generation++
        frozenSlots = baseSlots.toList()
        val source = frozenSlots.firstOrNull { it.key == key }
        targetIndex = source?.index ?: fallbackIndex
        offsetX = 0f
        offsetY = 0f
        draggedKey = key
        pointerInsetX = x
        pointerInsetY = y
    }

    fun dragBy(dx: Float, dy: Float) {
        if (settling) return
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
    fun pointerSlot(): ReorderSlot? = frozenSlots.firstOrNull { it.key != draggedKey && it.contains(pointerX-bounds.left,pointerY-bounds.top) }

    fun rememberPosition(key: String, left: Int, top: Int) {
        val value = IntOffset(left, top)
        if (positions[key] != value) positions[key] = value
    }

    suspend fun finish(onDrop: (Int) -> Unit) {
        val source = sourceSlot() ?: return cancel()
        if (settling) return
        if (pointerX !in bounds.left..bounds.right || pointerY !in maxOf(bounds.top, viewport.top)..minOf(bounds.bottom, viewport.bottom)) return cancel()
        val session = generation
        val target = targetIndex
        settling = true
        val x = offsetX; val y = offsetY
        try {
            animate(0f, 1f, animationSpec = tween(180, easing = FastOutSlowInEasing)) { fraction, _ ->
                if (session != generation) throw CancellationException("Reorder gesture cancelled")
                offsetX = x + (destination.x - source.left - x) * fraction
                offsetY = y + (destination.y - source.top - y) * fraction
            }
            if (session == generation && draggedKey != null) onDrop(target)
        } finally { if (session == generation) reset() }
    }

    fun cancel() { generation++; reset() }

    private fun reset() {
        draggedKey = null
        targetIndex = -1
        offsetX = 0f
        offsetY = 0f
        frozenSlots = emptyList()
        settling = false
    }
}

@Composable
private fun DropPreview(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .zIndex(0f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.82f), shape),
    )
}

@Composable
internal fun WidgetGrid(incoming: List<HostHomeWidget>, activity: MainActivity, dragState: DropPreviewState, arranging: Boolean, modifier: Modifier = Modifier, gridScope: String = "home") {
    hostRevision(activity)
    val gap=SuiteSpacing.md
    val gridLine=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f)
    val held=remember { object {var items=incoming} }
    val widgets=if(dragState.isDragging)held.items.map {old->incoming.find {widgetKey(it)==widgetKey(old)} ?: old} else incoming.also {held.items=it}
    val real=widgets.filterNot {it is FolderExitWidget}
    val items=real.map {HomeGridLayout.Item(widgetKey(it),activity.widgetWidthUnitsForUi(it),activity.widgetHeightUnitsForUi(it))}
    val saved=HomeGridLayout.decodeCells(activity.gridPositionsForUi(gridScope))
    val base=HomeGridLayout.place(items,saved)
    val encoded=HomeGridLayout.encodeCells(base)
    LaunchedEffect(gridScope,encoded.toString()) {if(!dragState.isDragging && activity.gridErrorForUi()==null)activity.saveGridPositionsForUi(gridScope,encoded,false)}
    val draggedKey=dragState.draggedKey
    val draggedIndex=widgets.indexOfFirst {widgetKey(it)==draggedKey}
    val dragged=widgets.getOrNull(draggedIndex)
    val pointerTarget=dragState.pointerSlot()?.let {slot->widgets.find {widgetKey(it)==slot.key}}
    val transferTarget=pointerTarget?.takeIf {dragged!=null && dragged !is FolderHomeWidget && (it is FolderHomeWidget || it is FolderExitWidget)}
    val requested=remember(gridScope) {object {var cell: HomeGridLayout.Cell?=null}}
    fun move(widget: HostHomeWidget,target: HostHomeWidget?) {
        if(target==null||target is FolderExitWidget||widgetKey(widget)==widgetKey(target))return
        val cell=base[widgetKey(target)] ?:return
        activity.saveGridPositionsForUi(gridScope,HomeGridLayout.encodeCells(HomeGridLayout.place(items,base,widgetKey(widget),cell)),true)
    }
    Column(modifier) {
        activity.gridErrorForUi()?.let {Notice(it,warning=true)}
        Layout(modifier=Modifier.fillMaxWidth().drawBehind {
            if(arranging || dragState.isDragging) {
                val gapPx=gap.toPx();val unit=(size.width-gapPx*3)/4;val height=72.dp.toPx();val step=height+gapPx
                for(row in 0 until kotlin.math.ceil(size.height/step).toInt())for(column in 0..3)drawRoundRect(gridLine,androidx.compose.ui.geometry.Offset(column*(unit+gapPx),row*step),androidx.compose.ui.geometry.Size(unit,height),androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }.onGloballyPositioned {dragState.bounds=it.boundsInWindow()},content={
            widgets.forEachIndexed {index,widget->key(widgetKey(widget)) {
                if(widget is FolderExitWidget)MoveOutTarget("拖到这里移回主页")
                else WidgetTile(activity,widget,Modifier,onClick={if(widget is FolderHomeWidget)activity.openFolderForUi(widget.folder.id) else {activity.openFolderForUi(null);activity.openWidgetForUi(widget)}},dragState=dragState,arranging=arranging,sourceIndex=index,
                    onDrop={
                        when(val target=transferTarget) {
                            is FolderHomeWidget->activity.moveFolderMemberForUi(HostFolders.HOME,widgetKey(widget),target.folder.id)
                            is FolderExitWidget->activity.moveFolderMemberForUi(HostFolders.HOME,widgetKey(widget),null)
                            else->requested.cell?.let {cell->activity.saveGridPositionsForUi(gridScope,HomeGridLayout.encodeCells(HomeGridLayout.place(items,base,widgetKey(widget),cell)),true)}
                        }
                    },onMoveEarlier={move(widget,widgets.getOrNull(index-1))},onMoveLater={move(widget,widgets.getOrNull(index+1))},onMoveFirst={move(widget,real.firstOrNull())},onMoveLast={move(widget,real.lastOrNull())})
            }}
            if(draggedIndex>=0)DropPreview()
        }) {measurables,constraints->
            val width=constraints.maxWidth;val gapPx=gap.roundToPx();val unitWidth=(width-gapPx*3).coerceAtLeast(4)/4f
            val unitHeight=72.dp.roundToPx();val stepX=unitWidth+gapPx;val stepY=unitHeight+gapPx
            val source=dragState.sourceSlot()
            val draggedItem=items.find {it.key==draggedKey}
            val candidate=if(draggedItem!=null && source!=null)HomeGridLayout.Cell(((source.left+dragState.offsetX)/stepX).roundToInt().coerceIn(0,4-draggedItem.width),((source.top+dragState.offsetY)/stepY).roundToInt().coerceIn(0,HomeGridLayout.MAX_ROWS-draggedItem.height)) else null
            requested.cell=candidate
            val cells=if(transferTarget==null && candidate!=null)HomeGridLayout.place(items,base,draggedKey,candidate) else base
            val baseRows=items.maxOfOrNull {base.getValue(it.key).row+it.height} ?:0
            val exitRow=items.maxOfOrNull {cells.getValue(it.key).row+it.height} ?:0
            val rows=maxOf(baseRows,exitRow)+(if(widgets.any {it is FolderExitWidget})1 else if(arranging || draggedIndex>=0)2 else 0)
            val placeables=arrayOfNulls<androidx.compose.ui.layout.Placeable>(measurables.size)
            val positions=Array(measurables.size){IntArray(2)}
            widgets.forEachIndexed {index,widget->
                val w=activity.widgetWidthUnitsForUi(widget);val h=activity.widgetHeightUnitsForUi(widget)
                val cell=if(widget is FolderExitWidget)HomeGridLayout.Cell(0,exitRow.coerceAtMost(HomeGridLayout.MAX_ROWS-1)) else cells.getValue(widgetKey(widget))
                val x=(stepX*cell.column).roundToInt();val y=stepY*cell.row
                positions[index][0]=if(index==draggedIndex)source?.left ?:x else x
                positions[index][1]=if(index==draggedIndex)source?.top ?:y else y
                placeables[index]=measurables[index].measure(Constraints.fixed((unitWidth*w+gapPx*(w-1)).roundToInt(),unitHeight*h+gapPx*(h-1)))
            }
            if(draggedIndex>=0) {
                val index=widgets.size
                if(transferTarget!=null) {
                    val targetIndex=widgets.indexOf(transferTarget);val target=requireNotNull(placeables[targetIndex])
                    placeables[index]=measurables[index].measure(Constraints.fixed(target.width,target.height));positions[index]=positions[targetIndex].clone()
                }else {
                    val target=requireNotNull(placeables[draggedIndex]);val cell=cells.getValue(draggedKey!!)
                    placeables[index]=measurables[index].measure(Constraints.fixed(target.width,target.height));positions[index][0]=(stepX*cell.column).roundToInt();positions[index][1]=stepY*cell.row
                }
                dragState.destination=IntOffset(positions[index][0],positions[index][1])
            }else dragState.updateBaseSlots(widgets.indices.map {index->val cell=requireNotNull(placeables[index]);ReorderSlot(widgetKey(widgets[index]),index,positions[index][0],positions[index][1],cell.width,cell.height)})
            widgets.indices.filter {it!=draggedIndex}.forEach {dragState.rememberPosition(widgetKey(widgets[it]),positions[it][0],positions[it][1])}
            layout(width,(rows*stepY-gapPx).coerceAtLeast(0).coerceIn(constraints.minHeight,constraints.maxHeight)) {
                placeables.forEachIndexed {index,placeable->placeable?.placeRelative(positions[index][0],positions[index][1],zIndex=if(index==draggedIndex)10f else 0f)}
            }
        }
    }
}

@Composable
private fun WidgetTile(
    activity: MainActivity,
    widget: HostHomeWidget,
    modifier: Modifier,
    onClick: () -> Unit,
    dragState: DropPreviewState,
    arranging: Boolean,
    sourceIndex: Int,
    onDrop: (Int) -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onMoveFirst: () -> Unit,
    onMoveLast: () -> Unit,
) {
    val shape = SuiteShapes.Card
    var menuExpanded by remember(widget.pluginId(), widget.id()) { mutableStateOf(false) }
    val width = activity.widgetWidthUnitsForUi(widget)
    val height = activity.widgetHeightUnitsForUi(widget)
    SideEffect { widget.setDisplaySize(com.androidtoolsuite.app.plugin.api.HomeWidgetSize(width, height)) }
    Box(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!arranging || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp -> { onMoveEarlier(); true }
                    Key.DirectionRight, Key.DirectionDown -> { onMoveLater(); true }
                    Key.MoveHome -> { onMoveFirst(); true }
                    Key.MoveEnd -> { onMoveLast(); true }
                    else -> false
                }
            }
            .dropPreviewReorder(
                key = widgetKey(widget),
                onClick = onClick,
                onLongPress = { menuExpanded = true },
                dragState = dragState,
                sourceIndex = sourceIndex,
                onDrop = onDrop,
                allowHorizontalDrag = true,
                arranging = arranging,
            )
            .semantics {
                contentDescription = if (widget is FolderHomeWidget) "文件夹 ${widget.title()}" else "小部件 ${widget.title()}"
                customActions = listOf(
                    CustomAccessibilityAction("上移") { onMoveEarlier(); true },
                    CustomAccessibilityAction("下移") { onMoveLater(); true },
                    CustomAccessibilityAction("移到开头") { onMoveFirst(); true },
                    CustomAccessibilityAction("移到末尾") { onMoveLast(); true },
                    CustomAccessibilityAction(if (widget is FolderHomeWidget) "解散文件夹" else "从主页移除") {
                        if (widget is FolderHomeWidget) activity.dissolveFolderForUi(widget.folder.id)
                        else activity.setWidgetVisibleForUi(widget, false)
                        true
                    },
                )
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        key(widget) {
            PluginAndroidView(Modifier.fillMaxSize()) {
                widget.setDisplaySize(com.androidtoolsuite.app.plugin.api.HomeWidgetSize(width, height))
                widget.createView(activity, activity)
            }
        }
        // 小部件里的 View 会把触摸事件吃掉，外层的点按、长按和拖动就都收不到了。
        // 这层透明覆盖只拦事件不消费，手势判定仍然由上面的 dropPreviewReorder 做。
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(widget.pluginId(), widget.id()) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        )
        if (arranging) Surface(Modifier.align(Alignment.TopEnd).padding(SuiteSpacing.sm), shape = SuiteShapes.Chip, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Rounded.DragHandle, "拖动排列 ${widget.title()}", Modifier.padding(SuiteSpacing.sm).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            if (widget is FolderHomeWidget) {
                DropdownMenuItem(text = { Text("编辑文件夹") }, onClick = { menuExpanded=false;activity.editFolderForUi(HostFolders.HOME,widget.folder.id) })
                DropdownMenuItem(text = { Text("解散文件夹") }, onClick = { menuExpanded=false;activity.dissolveFolderForUi(widget.folder.id) })
            } else {
                DropdownMenuItem(text = { Text("配置小部件") }, onClick = { menuExpanded=false;activity.openWidgetSettingsForUi(widget.pluginId(),widget.id()) })
                DropdownMenuItem(text = { Text("移入文件夹") }, onClick = { menuExpanded=false;activity.moveToFolderForUi(HostFolders.HOME,widgetKey(widget)) })
            }
            listOf("上移" to onMoveEarlier, "下移" to onMoveLater, "移到开头" to onMoveFirst, "移到末尾" to onMoveLast).forEach { (label, move) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menuExpanded = false; move() })
            }
            HorizontalDivider()
            widget.supportedSizes().forEach { size ->
                val selected = width == size.widthUnits && height == size.heightUnits
                DropdownMenuItem(
                    text = { Text("尺寸 ${size.widthUnits}×${size.heightUnits}") },
                    leadingIcon = { if (selected) Icon(Icons.Rounded.Check, "当前尺寸") },
                    onClick = {
                        activity.setWidgetSizeForUi(widget, size.widthUnits, size.heightUnits)
                        menuExpanded = false
                    },
                )
            }
            HorizontalDivider()
            if (widget !is FolderHomeWidget) {
            DropdownMenuItem(
                text = { Text("暂时隐藏") },
                leadingIcon = { Icon(Icons.Rounded.RemoveCircle, null) },
                onClick = {
                    activity.setWidgetVisibleForUi(widget, false)
                    menuExpanded = false
                },
            )
            DropdownMenuItem(text = { Text("移除小部件") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                menuExpanded=false;activity.requestRemoveWidgetForUi(widget)
            })
            }
        }
    }
}

@Composable
private fun AddWidgetSheet(activity: MainActivity, hidden: List<HostHomeWidget>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("添加小部件", modifier = Modifier.padding(horizontal = SuiteSpacing.xl), style = MaterialTheme.typography.titleLarge)
        if (hidden.isEmpty()) {
            // 「全都显示了」和「根本没有小部件可用」是两种情况，出口也不一样。
            val nothingAvailable = activity.allWidgetsForUi().isEmpty()
            Text(
                if (nothingAvailable) "已启用的插件都没有提供主页小部件，可在管理中启用更多插件。"
                else "所有小部件都已显示在主页。",
                modifier = Modifier.padding(SuiteSpacing.xl),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            hidden.forEach { widget ->
                SuiteSettingsRow(
                    widget.title(),
                    supportingText = "${widget.supportedSizes().size} 种尺寸",
                    leadingIcon = Icons.Rounded.Add,
                    onClick = {
                        activity.setWidgetVisibleForUi(widget, true)
                        onDismiss()
                    },
                )
            }
        }
        Spacer(Modifier.height(SuiteSpacing.xxl))
    }
}

/**
 * 长按拿起、拖动排序、点击打开——三件事共用一次手势判定。
 *
 * 不用 `combinedClickable` 叠 `detectDragGesturesAfterLongPress`：那样长按会先触发点击语义，
 * 再由拖动接手，松手时容易同时算成「点开插件」和「排序」。这里自己判距离与时长，
 * 位移没超过 touchSlop 才算点按或长按，超过就交给下面的拖动识别。
 */
@Composable
private fun Modifier.dropPreviewReorder(
    key: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    dragState: DropPreviewState,
    sourceIndex: Int,
    onDrop: (Int) -> Unit,
    allowHorizontalDrag: Boolean,
    arranging: Boolean,
    respectChildConsumption: Boolean = false,
): Modifier {
    val dragging = dragState.draggedKey == key
    val viewConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val latestDrop by rememberUpdatedState(onDrop)
    val latestClick by rememberUpdatedState(onClick)
    val latestLongPress by rememberUpdatedState(onLongPress)
    val target = dragState.positions[key] ?: IntOffset.Zero
    val animated by animateIntOffsetAsState(target, tween(if (dragState.isDragging) 180 else 0), label = "reorder-placement")
    return this
        .zIndex(if (dragging) 10f else 0f)
        .graphicsLayer {
            translationX = if (dragging) dragState.offsetX else if (dragState.isDragging) (animated.x - target.x).toFloat() else 0f
            translationY = if (dragging) dragState.offsetY else if (dragState.isDragging) (animated.y - target.y).toFloat() else 0f
            alpha = if (dragging) 0.94f else 1f
            scaleX = if (dragging && !dragState.settling) 1.025f else 1f
            scaleY = scaleX
            shadowElevation = if (dragging) 14.dp.toPx() else 0f
            shape = SuiteShapes.Card
        }
        .semantics {
            onClick(if (arranging) "排列选项" else "打开") { if (arranging) latestLongPress() else latestClick(); true }
            onLongClick("排列选项") { latestLongPress(); true }
        }
        .focusable()
        .pointerInput(key, arranging) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                var pointer = down
                val childConsumed = down.isConsumed
                var maxDistance = 0f
                while (pointer.pressed) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    maxDistance = maxOf(maxDistance, (pointer.position - down.position).getDistance())
                }
                if (maxDistance <= viewConfiguration.touchSlop && (!respectChildConsumption || !childConsumed)) {
                    if (arranging || pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) latestLongPress()
                    else latestClick()
                }
            }
        }
        .pointerInput(key, sourceIndex, arranging) {
            val start: (androidx.compose.ui.geometry.Offset) -> Unit = { dragState.start(key, sourceIndex, it.x, it.y) }
            val end: () -> Unit = { scope.launch { dragState.finish(latestDrop) } }
            val move: (androidx.compose.ui.input.pointer.PointerInputChange, androidx.compose.ui.geometry.Offset) -> Unit = { change, dragAmount ->
                change.consume()
                dragState.dragBy(dragAmount.x, dragAmount.y)
            }
            if (arranging) detectDragGestures(onDragStart = start, onDragCancel = dragState::cancel, onDragEnd = end, onDrag = move)
            else detectDragGesturesAfterLongPress(onDragStart = start, onDragCancel = dragState::cancel, onDragEnd = end, onDrag = move)
        }
}

@Composable
private fun ReorderAutoScroll(state: DropPreviewState, listState: LazyListState) {
    val edge = with(LocalDensity.current) { 60.dp.toPx() }
    val maximum = with(LocalDensity.current) { 14.dp.toPx() }
    BackHandler(enabled = state.isDragging) { state.cancel() }
    LaunchedEffect(state.draggedKey) {
        while (isActive && state.isDragging) {
            withFrameNanos { }
            if (state.settling || state.viewport == Rect.Zero) continue
            val distance = when {
                state.pointerY < state.viewport.top + edge -> -((state.viewport.top + edge - state.pointerY) / edge).coerceIn(0f, 1f)
                state.pointerY > state.viewport.bottom - edge -> ((state.pointerY - state.viewport.bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (distance != 0f) state.dragBy(0f, listState.scrollBy(distance * maximum))
        }
    }
}

@Composable
internal fun PluginListScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    val dragState = remember { DropPreviewState() }
    val plugins = activity.pluginsForUi().filter { query.isBlank() || (it.title() + " " + it.description()).contains(query.trim(), ignoreCase = true) }
    val hidden = activity.allToolsForUi().filterNot(activity::isToolVisibleForUi)
    val listState = rememberPageListState(activity, "tools")
    ReorderAutoScroll(dragState, listState)
    BackHandler(enabled = arranging && !dragState.isDragging) { arranging = false }
    LazyColumn(
        state = listState,
        modifier = modifier.onGloballyPositioned { dragState.viewport = it.boundsInWindow() }
            .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp && dragState.isDragging) { dragState.cancel(); true } else false }
            .semantics { stateDescription = "tools-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
    ) {
        item { SectionHeader("你的工具", "${plugins.size} 个可用") {
            BrowseSearchToggle(searching || query.isNotBlank(), { searching=!searching;if(!searching)query="" }, !dragState.isDragging)
            TextButton(onClick = { arranging = !arranging }, enabled = !dragState.isDragging) { Text(if (arranging) "完成" else "排列") }
        } }
        if(searching || query.isNotBlank()) item { BrowseSearchField(query,{query=it},"搜索名称或用途") {query="";searching=false} }
        if (arranging) item { ReorderHint() }
        if (plugins.isEmpty()) item {
            EmptyState(if (query.isBlank()) "还没有可用工具" else "没有找到这个工具", if (query.isBlank()) "前往插件，安装并启用需要的能力。" else "试试名称中的其他关键词。")
            OutlinedButton(onClick = { if (query.isNotBlank()) query = "" else activity.showPluginRepositoryForUi() }) { Text(if (query.isNotBlank()) "清除搜索" else "前往仓库") }
        } else item { ToolReorderList(activity, plugins, dragState, arranging, Modifier.fillMaxWidth()) }
        if (hidden.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${hidden.size} 个工具已隐藏。隐藏不会停用插件。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { hidden.forEach { activity.setToolVisibleForUi(it, true) } }) { Text("恢复显示") }
            }
        }
    }
}

@Composable
private fun ToolReorderList(
    activity: MainActivity,
    incoming: List<HostTool>,
    dragState: DropPreviewState,
    arranging: Boolean,
    modifier: Modifier = Modifier,
) {
    val gap = 12.dp
    val held = remember { object { var items = incoming } }
    val plugins = if (dragState.isDragging) held.items.map { old -> incoming.find { it.id() == old.id() } ?: old } else incoming.also { held.items = it }
    val draggedIndex = plugins.indexOfFirst { it.id() == dragState.draggedKey }
    val targetIndex = dragState.targetIndex.coerceIn(0, (plugins.size - 1).coerceAtLeast(0))
    Layout(
        modifier = modifier.onGloballyPositioned { dragState.bounds = it.boundsInWindow() },
        content = {
            plugins.forEachIndexed { index, plugin ->
                key(plugin.id()) {
                    fun moveTo(target: HostTool?) {
                        target?.takeIf { it.id() != plugin.id() }?.let {
                            val previous = activity.allToolsForUi().map(HostTool::id)
                            activity.moveToolToUi(plugin.id(), it.id())
                            activity.offerReorderUndoForUi(false, previous)
                        }
                    }
                    PluginListCard(
                        activity = activity,
                        plugin = plugin,
                        arranging = arranging,
                        onMoveEarlier = { moveTo(plugins.getOrNull(index - 1)) },
                        onMoveLater = { moveTo(plugins.getOrNull(index + 1)) },
                        onMoveFirst = { moveTo(plugins.firstOrNull()) },
                        onMoveLast = { moveTo(plugins.lastOrNull()) },
                        interaction = { onLongPress ->
                            Modifier.dropPreviewReorder(
                                key = plugin.id(),
                                onClick = { activity.openPluginForUi(plugin) },
                                onLongPress = onLongPress,
                                dragState = dragState,
                                sourceIndex = index,
                                onDrop = { destination -> moveTo(plugins.getOrNull(destination)) },
                                allowHorizontalDrag = false,
                                arranging = arranging,
                            )
                        },
                        modifier = Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("上移") { moveTo(plugins.getOrNull(index - 1)); true },
                                CustomAccessibilityAction("下移") { moveTo(plugins.getOrNull(index + 1)); true },
                                CustomAccessibilityAction("移到开头") { moveTo(plugins.firstOrNull()); true },
                                CustomAccessibilityAction("移到末尾") { moveTo(plugins.lastOrNull()); true },
                                CustomAccessibilityAction("从工具页隐藏") {
                                    activity.setToolVisibleForUi(plugin, false)
                                    true
                                },
                            )
                        },
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

        if (draggedIndex >= 0) dragState.destination = IntOffset(0, positions[placeholderIndex][1])
        plugins.indices.filter { it != draggedIndex }.forEach { dragState.rememberPosition(plugins[it].id(), 0, positions[it][1]) }
        val listHeight = (y - gapPx).coerceAtLeast(0)
        layout(listWidth, listHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable?.placeRelative(
                    0,
                    positions[index][1],
                    zIndex = when (index) {
                        placeholderIndex -> 0f
                        draggedIndex -> 10f
                        else -> 0f
                    },
                )
            }
        }
    }
}


@Composable
internal fun ToolCollectionReorderList(
    activity: MainActivity,
    incoming: List<ToolCollectionItem>,
    dragState: DropPreviewState,
    arranging: Boolean,
    collected: Boolean,
    modifier: Modifier = Modifier,
    folderId: String? = null,
) {
    val gap = 12.dp
    val held = remember { object { var items = incoming } }
    val plugins = if (dragState.isDragging) held.items.map { old -> incoming.find { it.key == old.key } ?: old } else incoming.also { held.items = it }
    val draggedIndex = plugins.indexOfFirst { it.key == dragState.draggedKey }
    val targetIndex = dragState.targetIndex.coerceIn(0, (plugins.size - 1).coerceAtLeast(0))
    val sourceItem=plugins.getOrNull(draggedIndex)
    val pointerTarget=dragState.pointerSlot()?.let {slot->plugins.find {it.key==slot.key}}
    val transfer=pointerTarget?.takeIf {sourceItem?.pluginId!=null && (it.folder!=null || it.exit)}
    Layout(
        modifier = modifier.onGloballyPositioned { dragState.bounds = it.boundsInWindow() },
        content = {
            plugins.forEachIndexed { index, plugin ->
                key(plugin.key) {
                    fun moveTo(target: ToolCollectionItem?) {
                        target?.takeIf { !it.exit && it.key != plugin.key }?.let {
                            activity.moveCollectionItemForUi(HostFolders.TOOLS, plugin.key, it.key, plugins.map { item -> item.key })
                        }
                    }
                    ToolCollectionCard(activity, plugin, arranging, collected,
                        onMove = { direction -> moveTo(plugins.getOrNull(index + direction)) },
                        interaction = { onLongPress -> Modifier.dropPreviewReorder(
                            key = plugin.key,
                            onClick = {
                                if (plugin.folder != null) activity.openFolderForUi(plugin.folder.id)
                                else plugin.pluginId?.let { id ->
                                    val tool = activity.findToolForUi(id)
                                    if (tool != null && activity.isImportedPluginEnabled(id)) activity.openPluginForUi(tool)
                                    else activity.showPluginManagementForUi(id, "detail")
                                }
                            },
                            onLongPress = onLongPress, dragState = dragState, sourceIndex = index,
                            onDrop = { destination ->
                                if(transfer!=null)plugin.pluginId?.let {id->activity.moveFolderMemberForUi(HostFolders.TOOLS,id,transfer.folder?.id)}
                                else moveTo(plugins.getOrNull(destination))
                            },
                            allowHorizontalDrag = false, arranging = arranging, respectChildConsumption = true,
                        ) },
                        modifier=if(transfer?.key==plugin.key)Modifier.border(2.dp,MaterialTheme.colorScheme.primary,SuiteShapes.Card) else Modifier,
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
            logicalIndices.add((if(transfer!=null)draggedIndex else targetIndex).coerceAtMost(logicalIndices.size), -1)
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
                        key = plugins[index].key,
                        index = index,
                        left = 0,
                        top = positions[index][1],
                        width = placeable.width,
                        height = placeable.height,
                    )
                },
            )
        }

        if (draggedIndex >= 0) dragState.destination = IntOffset(0, positions[placeholderIndex][1])
        plugins.indices.filter { it != draggedIndex }.forEach { dragState.rememberPosition(plugins[it].key, 0, positions[it][1]) }
        val listHeight = (y - gapPx).coerceAtLeast(0)
        layout(listWidth, listHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable?.placeRelative(
                    0,
                    positions[index][1],
                    zIndex = when (index) {
                        placeholderIndex -> 0f
                        draggedIndex -> 10f
                        else -> 0f
                    },
                )
            }
        }
    }
}

@Composable
private fun PluginListCard(
    activity: MainActivity,
    plugin: HostTool,
    arranging: Boolean,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onMoveFirst: () -> Unit,
    onMoveLast: () -> Unit,
    interaction: @Composable (onLongPress: () -> Unit) -> Modifier,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(plugin.id()) { mutableStateOf(false) }
    Box(modifier = modifier.onPreviewKeyEvent { event ->
        if (!arranging || event.type != KeyEventType.KeyDown) false else when (event.key) {
            Key.DirectionUp -> { onMoveEarlier(); true }
            Key.DirectionDown -> { onMoveLater(); true }
            Key.MoveHome -> { onMoveFirst(); true }
            Key.MoveEnd -> { onMoveLast(); true }
            else -> false
        }
    }) {
        SuiteCard(modifier = interaction { menuExpanded = true }) {
            Row(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                IconBox(pluginIcon(plugin), "${plugin.title()}工具")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                        Text(plugin.title(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(if (arranging) Icons.Rounded.DragHandle else Icons.Rounded.ChevronRight, if (arranging) "拖动排列" else null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        plugin.description(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf("上移" to onMoveEarlier, "下移" to onMoveLater, "移到开头" to onMoveFirst, "移到末尾" to onMoveLast).forEach { (label, move) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menuExpanded = false; move() })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("从工具页隐藏") },
                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                onClick = {
                    menuExpanded = false
                    activity.setToolVisibleForUi(plugin, false)
                },
            )
            DropdownMenuItem(text = { Text(if(activity.isToolPinnedForUi(plugin.id())) "取消固定到首页" else "固定到首页") }, onClick = { menuExpanded=false;activity.setToolPinnedForUi(plugin.id(),!activity.isToolPinnedForUi(plugin.id())) })
            if (activity.canDisablePluginForUi(plugin)) {
                DropdownMenuItem(
                    text = { Text("停用插件") },
                    leadingIcon = { Icon(Icons.Rounded.RemoveCircle, null) },
                    onClick = {
                        menuExpanded = false
                        activity.disablePluginForUi(plugin)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("插件详情") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                onClick = {
                    menuExpanded = false
                    activity.showPluginManagementForUi(plugin.id(), "detail")
                },
            )
        }
    }
}
