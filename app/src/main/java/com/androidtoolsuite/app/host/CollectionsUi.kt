@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.androidtoolsuite.app.host

import android.app.Activity
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.ui.*
import kotlinx.coroutines.isActive

internal class FolderHomeWidget(val folder: HostFolders.Folder, private val owner: MainActivity) : HostHomeWidget {
    private val display = mutableStateOf(HomeWidgetSize(1, 1))
    override fun id() = folder.id
    override fun title() = folder.name
    override fun pluginId() = "@folder"
    override fun supportedSizes() = (1..4).flatMap { width -> (1..4).map { height -> HomeWidgetSize(width,height) } }
    override fun setDisplaySize(size: HomeWidgetSize) { display.value=size }
    override fun createView(activity: Activity, host: HostServices): View = composePluginView(activity) {
        hostRevision(owner)
        val size=display.value
        val current=owner.foldersForUi(HostFolders.HOME).find { it.id==folder.id } ?: folder
        val members=owner.widgetsForUi().filter { it.pluginId()+":"+it.id() in current.members }
        if(size.widthUnits==1 && size.heightUnits==1) {
            Column(Modifier.fillMaxSize().padding(SuiteSpacing.sm),verticalArrangement=Arrangement.Center) {
                Text(current.name,style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(current.members.size.toString()+" 个部件",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(SuiteSpacing.sm),verticalArrangement=Arrangement.spacedBy(SuiteSpacing.xs)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(current.name,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(current.members.size.toString()+" 个",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(members.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("空文件夹",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                else {
                    val count=if(size.widthUnits>=2 && size.heightUnits>=2)4 else 1
                    val shown=members.take(count)
                    Column(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(SuiteSpacing.xs)) {
                        shown.chunked(if(count==4)2 else 1).forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(SuiteSpacing.xs)) {
                                row.forEach { child -> FolderMemberPreview(owner,child,Modifier.weight(1f).fillMaxHeight()) }
                                if(count==4 && row.size==1)Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderMemberPreview(activity: MainActivity,child: HostHomeWidget,modifier: Modifier) {
    val width=activity.widgetWidthUnitsForUi(child);val height=activity.widgetHeightUnitsForUi(child)
    val unit=(LocalConfiguration.current.screenWidthDp.dp-SuiteSpacing.ScreenPadding*2-SuiteSpacing.md*3)/4
    val fullWidth=unit*width+SuiteSpacing.md*(width-1);val fullHeight=72.dp*height+SuiteSpacing.md*(height-1)
    BoxWithConstraints(modifier.clip(SuiteShapes.Inner).clearAndSetSemantics { }) {
        val scale=minOf(maxWidth/fullWidth,maxHeight/fullHeight)
        Layout(content={
            PluginAndroidView(Modifier.graphicsLayer { scaleX=scale;scaleY=scale }) {
                child.setDisplaySize(HomeWidgetSize(width,height))
                child.createView(activity,activity).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            }
        }) { measurables,constraints ->
            val placeable=measurables.first().measure(Constraints.fixed(fullWidth.roundToPx(),fullHeight.roundToPx()))
            layout(constraints.maxWidth,constraints.maxHeight) { placeable.placeRelative((constraints.maxWidth-placeable.width)/2,(constraints.maxHeight-placeable.height)/2) }
        }
    }
}

internal class FolderExitWidget : HostHomeWidget {
    override fun id()="root"
    override fun title()="移回主页"
    override fun pluginId()="@exit"
    override fun supportedSizes()=listOf(HomeWidgetSize(4,1))
    override fun createView(activity: Activity,host: HostServices): View=composePluginView(activity) { MoveOutTarget("拖到这里移回主页") }
}

@Composable internal fun MoveOutTarget(title: String) {
    Surface(Modifier.fillMaxWidth(),shape=SuiteShapes.Inner,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(SuiteSpacing.lg),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(SuiteSpacing.sm)) {
            Icon(Icons.AutoMirrored.Rounded.DriveFileMove,null)
            Text(title,style=MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun homeCollections(activity: MainActivity, widgets: List<HostHomeWidget>): List<HostHomeWidget> {
    val folders = activity.foldersForUi(HostFolders.HOME)
    val entries = linkedMapOf<String, HostHomeWidget>()
    widgets.filter { widget -> folders.none { it.members.contains(widget.pluginId() + ":" + widget.id()) } }
        .forEach { entries[it.pluginId() + ":" + it.id()] = it }
    folders.forEach { entries[it.key()] = FolderHomeWidget(it,activity) }
    return activity.collectionOrderForUi(HostFolders.HOME, entries.keys.toList()).mapNotNull(entries::get)
}

@Composable
internal fun CollectionDialogs(activity: MainActivity) {
    hostRevision(activity)
    val all = activity.foldersForUi(HostFolders.HOME) + activity.foldersForUi(HostFolders.TOOLS)
    val open = all.find { it.id == activity.openFolderForUi() }
    if (open != null) {
        Dialog(onDismissRequest = { activity.openFolderForUi(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val scroll=rememberScrollState()
            val widgetDrag=remember { DropPreviewState() }
            var arranging by remember(open.id) { mutableStateOf(false) }
            var folderViewport by remember(open.id) { mutableStateOf(Rect.Zero) }
            CollectionAutoScroll(widgetDrag,scroll)
            Surface(Modifier.fillMaxWidth(.96f).widthIn(max = 720.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * .82f).dp), shape = SuiteShapes.Dialog) {
                Column(Modifier.padding(SuiteSpacing.lg).onGloballyPositioned { widgetDrag.viewport=it.boundsInWindow();if(folderViewport!=it.boundsInWindow())folderViewport=it.boundsInWindow() }.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(open.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if(open.scope==HostFolders.HOME)TextButton(onClick={arranging=!arranging}) {Text(if(arranging)"完成" else "排列")}
                        IconButton(onClick = { activity.editFolderForUi(open.scope, open.id) }) { Icon(Icons.Rounded.Edit, "编辑文件夹") }
                        IconButton(onClick = { activity.openFolderForUi(null) }) { Icon(Icons.Rounded.Close, "关闭文件夹") }
                    }
                    if (open.scope == HostFolders.HOME) {
                        val children = activity.widgetsForUi().filter { (it.pluginId() + ":" + it.id()) in open.members }
                        if (children.isEmpty()) Text("这个文件夹暂时没有可显示的小部件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else WidgetGrid(children+FolderExitWidget(), activity, widgetDrag, arranging, Modifier.fillMaxWidth(),"home:"+open.id)
                    } else {
                        ToolCollectionsScreen(activity, activity.hostStateRevision(), folderId = open.id, parentScroll = scroll, parentViewport = folderViewport)
                    }
                }
            }
        }
    }
    val moveScope = activity.folderMoveScopeForUi()
    val moveMember = activity.folderMoveMemberForUi()
    if (moveScope != null && moveMember != null && activity.folderEditorScopeForUi() == null) {
        AlertDialog(
            onDismissRequest = activity::closeFolderEditorForUi,
            title = { Text("移入文件夹") },
            text = {
                Column {
                    SuiteSettingsRow("放在主页面", onClick = { activity.moveFolderMemberForUi(moveScope, moveMember, null) })
                    activity.foldersForUi(moveScope).forEach { folder ->
                        SuiteSettingsRow(folder.name, supportingText = folder.members.size.toString() + " 个成员",
                            onClick = { activity.moveFolderMemberForUi(moveScope, moveMember, folder.id) })
                    }
                    SuiteSettingsRow("新建文件夹", leadingIcon = Icons.Rounded.CreateNewFolder, onClick = {
                        activity.editFolderForUi(moveScope, null)
                    })
                }
            },
            confirmButton = { TextButton(onClick = activity::closeFolderEditorForUi) { Text("取消") } },
        )
    }
    val editScope = activity.folderEditorScopeForUi()
    if (editScope != null) {
        val existing = all.find { it.id == activity.folderEditorIdForUi() }
        var name by remember(editScope, existing?.id) { mutableStateOf(existing?.name ?: "新建文件夹") }

        AlertDialog(
            onDismissRequest = activity::closeFolderEditorForUi,
            title = { Text(if (existing == null) "新建文件夹" else "编辑文件夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                    OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text("文件夹名称") }, singleLine = true)
                    Text("长按并拖动卡片整理成员。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { activity.saveFolderForUi(editScope, existing?.id, name, existing?.members ?: activity.folderMoveMemberForUi()?.let { listOf(it) } ?: emptyList()) }) { Text("保存") } },
            dismissButton = {
                Row {
                    if (existing != null) TextButton(onClick = { activity.dissolveFolderForUi(existing.id); activity.closeFolderEditorForUi() }) { Text("解散", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = activity::closeFolderEditorForUi) { Text("取消") }
                }
            },
        )
    }
}

internal data class ToolCollectionItem(val key: String, val title: String, val detail: String,
    val pluginId: String? = null, val folder: HostFolders.Folder? = null, val exit: Boolean = false)

internal fun toolCollections(activity: MainActivity, folderId: String? = null, collected: Boolean = false): List<ToolCollectionItem> {
    val descriptors = activity.importedDescriptorsForUi()
    val folders = activity.foldersForUi(HostFolders.TOOLS)
    val folder = folders.find { it.id == folderId }
    val items = linkedMapOf<String, ToolCollectionItem>()
    descriptors.filter { descriptor ->
        when {
            folder != null -> descriptor.id in folder.members && activity.isToolVisibleByIdForUi(descriptor.id)
            collected -> !activity.isToolVisibleByIdForUi(descriptor.id)
            else -> activity.isToolVisibleByIdForUi(descriptor.id) && folders.none { descriptor.id in it.members }
        }
    }.forEach { descriptor ->
        items[descriptor.id] = ToolCollectionItem(descriptor.id, descriptor.title,
            "v" + descriptor.version + " · " + if (activity.isImportedPluginEnabled(descriptor.id)) "已启用" else "已停用", pluginId = descriptor.id)
    }
    if (folderId == null && !collected) folders.forEach {
        items[it.key()] = ToolCollectionItem(it.key(), it.name, it.members.size.toString() + " 个插件", folder = it)
    }
    if(folderId!=null)items["@exit:tools"]=ToolCollectionItem("@exit:tools","移回工具页","拖到这里移出文件夹",exit=true)
    return activity.collectionOrderForUi(HostFolders.TOOLS, items.keys.toList()).mapNotNull(items::get)
}

@Composable
internal fun ToolCollectionsScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier, folderId: String? = null, parentScroll: androidx.compose.foundation.ScrollState? = null,parentViewport: Rect = Rect.Zero) {
    hostRevision(activity)
    var collected by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val drag = remember { DropPreviewState() }
    val scroll = rememberScrollState()
    val edge = with(LocalDensity.current) { 60.dp.toPx() }
    val speed = with(LocalDensity.current) { 14.dp.toPx() }
    BackHandler(enabled = drag.isDragging) { drag.cancel() }
    LaunchedEffect(drag.draggedKey) {
        while (isActive && drag.isDragging) {
            withFrameNanos { }
            if (drag.settling || drag.viewport == Rect.Zero) continue
            val direction = when {
                drag.pointerY < drag.viewport.top + edge -> -((drag.viewport.top + edge - drag.pointerY) / edge).coerceIn(0f, 1f)
                drag.pointerY > drag.viewport.bottom - edge -> ((drag.pointerY - drag.viewport.bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (direction != 0f) drag.dragBy(0f, (parentScroll ?: scroll).scrollBy(direction * speed))
        }
    }
    val entries = toolCollections(activity, folderId, collected).filter { it.exit || query.isBlank() || (it.title + " " + it.detail).contains(query, true) }
    BackHandler(enabled = folderId == null && collected) { collected = false }
    val scrolling = if (folderId == null) modifier.onGloballyPositioned { drag.viewport = it.boundsInWindow() }.verticalScroll(scroll) else modifier.onGloballyPositioned { drag.viewport=if(parentViewport==Rect.Zero)it.boundsInWindow() else parentViewport }
    Column(scrolling.padding(if (folderId == null) SuiteSpacing.xl else 0.dp), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (collected) TextButton(onClick = { collected = false }) { Text("返回工具") }
            Text(if (collected) "工具收纳" else if (folderId == null) "已安装 ${activity.importedDescriptorsForUi().size} 个插件" else "插件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            BrowseSearchToggle(searching || query.isNotBlank(), { searching = !searching; if (!searching) query = "" })
            if (folderId == null && !collected) IconButton(onClick = { activity.editFolderForUi(HostFolders.TOOLS, null) }) { Icon(Icons.Rounded.CreateNewFolder, "新建文件夹") }
            TextButton(onClick = { arranging = !arranging }) { Text(if (arranging) "完成" else "排列") }
        }
        if (searching || query.isNotBlank()) BrowseSearchField(query, { query = it }, "搜索插件或文件夹") { query = ""; searching = false }
        activity.folderErrorForUi()?.let { Notice(it, warning = true) }
        if (entries.none { !it.exit }) EmptyState("这里还没有插件", "可以从仓库安装，或调整当前筛选。")
        ToolCollectionReorderList(activity, entries, drag, arranging, collected, folderId=folderId)
        if (folderId == null && !collected) {
            val count = activity.importedDescriptorsForUi().count { !activity.isToolVisibleByIdForUi(it.id) }
            if (count > 0) SuiteSettingsRow("工具收纳", supportingText = count.toString() + " 个不常用插件", leadingIcon = Icons.Rounded.Archive, onClick = { collected = true }, trailing = { Icon(Icons.Rounded.ChevronRight, null) })
        }
    }
}

@Composable
internal fun ToolCollectionCard(activity: MainActivity, entry: ToolCollectionItem, arranging: Boolean,
    collected: Boolean, onMove: (Int) -> Unit, interaction: @Composable (onLongPress: () -> Unit) -> Modifier, modifier: Modifier = Modifier) {
    if(entry.exit){MoveOutTarget("拖到这里移回工具页");return}
    var menu by remember(entry.key) { mutableStateOf(false) }
    Box(modifier) {
        SuiteCard(interaction { menu = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                val tool = activity.allToolsForUi().firstOrNull { it.id() == entry.pluginId }
                IconBox(if (entry.folder != null) Icons.Rounded.Folder else tool?.let(::pluginIcon) ?: Icons.Rounded.Extension, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (arranging) Icon(Icons.Rounded.DragHandle, "拖动排列")
                else if (entry.pluginId != null) IconButton(onClick = { activity.showPluginManagementForUi(entry.pluginId, "detail") }) { Icon(Icons.Rounded.Tune, "插件详情 " + entry.title) }
                else Icon(Icons.Rounded.ChevronRight, null)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMove(-1) })
            DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMove(1) })
            if (entry.folder != null) {
                DropdownMenuItem(text = { Text("编辑文件夹") }, onClick = { menu = false; activity.editFolderForUi(HostFolders.TOOLS, entry.folder.id) })
                DropdownMenuItem(text = { Text("解散文件夹") }, onClick = { menu = false; activity.dissolveFolderForUi(entry.folder.id) })
            } else entry.pluginId?.let { id ->
                DropdownMenuItem(text = { Text("移入文件夹") }, onClick = { menu = false; activity.moveToFolderForUi(HostFolders.TOOLS, id) })
                DropdownMenuItem(text = { Text(if (collected) "移出收纳" else "移入工具收纳") }, onClick = {
                    menu = false
                    activity.setToolVisibleByIdForUi(id, collected)
                })
            }
        }
    }
}

@Composable private fun CollectionAutoScroll(state: DropPreviewState,scroll: androidx.compose.foundation.ScrollState) {
    val edge=with(LocalDensity.current){60.dp.toPx()};val speed=with(LocalDensity.current){14.dp.toPx()}
    BackHandler(enabled=state.isDragging){state.cancel()}
    LaunchedEffect(state.draggedKey) {
        while(isActive && state.isDragging) {
            withFrameNanos { };if(state.settling || state.viewport==Rect.Zero)continue
            val direction=when {state.pointerY<state.viewport.top+edge -> -((state.viewport.top+edge-state.pointerY)/edge).coerceIn(0f,1f);state.pointerY>state.viewport.bottom-edge -> ((state.pointerY-state.viewport.bottom+edge)/edge).coerceIn(0f,1f);else -> 0f}
            if(direction!=0f)state.dragBy(0f,scroll.scrollBy(direction*speed))
        }
    }
}
