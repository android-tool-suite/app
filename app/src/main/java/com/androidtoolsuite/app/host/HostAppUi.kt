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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
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

private const val DASHBOARD = 0
private const val PLUGINS = 1
private const val MANAGER = 2
private const val STORE = 6
private const val SETTINGS = 3
private const val ABOUT = 4

/** State bridge for Java-host callbacks. MutableState is observed by Compose directly. */
class HostUiState {
    val dataDrafts: MutableMap<String, Any> = mutableMapOf()
    private val downloadProgress = mutableStateMapOf<String, Float>()
    var revision: Int by mutableIntStateOf(0)
        private set

    fun bump() {
        revision++
    }

    fun updateDownloadProgress(id: String, downloadedBytes: Long, totalBytes: Long) {
        downloadProgress[id] = if (totalBytes <= 0L) {
            0f
        } else {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun clearDownloadProgress(id: String) {
        downloadProgress.remove(id)
    }

    fun downloadProgress(id: String): Float = downloadProgress[id] ?: 0f
}

/**
 * 订阅宿主的失效计数，并把它作为值返回。
 *
 * 宿主状态大多是 Java 普通字段，靠 [MainActivity.invalidateComposeUi] 撞这个计数来触发重组。
 * 关键在于**读取必须发生在需要重组的那个 composable 自己的作用域里**：把计数当参数传下去只让
 * 调用方订阅了，被调用方仍可能被跳过——弹窗就是这么丢的。每个消费宿主状态的 composable
 * 自己调一次这个函数。
 */
@Composable
internal fun hostRevision(activity: MainActivity): Int = activity.uiStateForUi().revision

@Composable
internal fun rememberPageListState(activity: MainActivity, page: String): LazyListState {
    return key(page) {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = activity.scrollIndexForUi(page),
        initialFirstVisibleItemScrollOffset = activity.scrollOffsetForUi(page),
    )
    LaunchedEffect(state, page) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> activity.saveScrollPositionForUi(page, index, offset) }
    }
        state
    }
}

fun createHostAppView(activity: MainActivity): View {
    activity.enableEdgeToEdge()
    val composeView = ComposeView(activity).apply {
        setContent {
            SuiteTheme(
                themePreference = SuiteThemePreferences.themePreference,
                colorPreference = SuiteThemePreferences.colorPreference,
            ) {
                SyncSystemBarsWithTheme(activity)
                HostApp(activity)
            }
        }
    }
    return object : FrameLayout(activity) {
        private var resetFrameRate: Runnable? = null

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resetFrameRate?.let(::removeCallbacks)
                        resetFrameRate = null
                        requestInteractiveFrameRate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetFrameRate?.let(::removeCallbacks)
                        resetFrameRate = Runnable { clearRequestedFrameRate() }.also {
                            // 手指离开后 Pager 还可能在回弹或惯性滚动，留出一小段余量。
                            postDelayed(it, 600L)
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        addView(
            composeView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}

/**
 * 让状态栏图标的明暗跟随应用实际渲染出的主题。
 *
 * 窗口主题是 `Theme.Material3.DayNight`，它只看系统的深色模式；而应用允许用户把主题钉死成
 * 浅色或深色。两者不一致时（比如系统浅色、应用选深色），状态栏图标会是深色叠在深色背景上，
 * 基本看不见。这里从已解析的主题里取深浅状态，直接写回窗口。
 */
@Composable
private fun SyncSystemBarsWithTheme(activity: MainActivity) {
    val dark = SuiteTheming.isDark
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        // 三键导航时系统会把按键画在应用的导航栏区域上，同样需要跟随明暗。
        controller.isAppearanceLightNavigationBars = !dark
    }
}

private data class Destination(val section: Int, val label: String, val icon: ImageVector)

private fun View.requestInteractiveFrameRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val highestRate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 120f
    updateRequestedFrameRate(highestRate)
}

private fun View.clearRequestedFrameRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    updateRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT)
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun View.updateRequestedFrameRate(rate: Float) {
    requestedFrameRate = rate
    if (this is ViewGroup) {
        for (index in 0 until childCount) getChildAt(index).updateRequestedFrameRate(rate)
    }
}

private val destinations = listOf(
    Destination(DASHBOARD, "主页", Icons.Rounded.Home),
    Destination(PLUGINS, "工具", Icons.Rounded.Apps),
    Destination(STORE, "仓库", Icons.Rounded.Storefront),
    Destination(SETTINGS, "设置", Icons.Rounded.Settings),
)

@Composable
private fun HostApp(activity: MainActivity) {
    val refreshVersion = hostRevision(activity)
    val selectedSection = activity.currentSectionForUi()
    val selectedPlugin = activity.selectedPluginForUi()
    val pagerState = rememberPagerState(
        initialPage = destinations.indexOfFirst { it.section == selectedSection }.coerceAtLeast(0),
        pageCount = destinations::size,
    )
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val navigateTo: (Int) -> Unit = { requestedPage ->
        val targetPage = destinations.indexOfFirst { it.section == requestedPage }.coerceAtLeast(0)
        navigationJob?.cancel()
        navigationJob = navigationScope.launch { pagerState.animateToSection(targetPage) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(selectedSection, selectedPlugin) {
        if (selectedPlugin == null && destinations.any { it.section == selectedSection }) {
            val targetPage = destinations.indexOfFirst { it.section == selectedSection }
            if (pagerState.settledPage != targetPage || pagerState.currentPageOffsetFraction != 0f) {
                navigationJob?.cancel()
                navigationJob = navigationScope.launch { pagerState.animateToSection(targetPage) }
            }
        }
    }
    BackHandler(enabled = activity.canHandleBackForUi()) { activity.handleBackForUi() }
    LaunchedEffect(activity.snackbarRevisionForUi()) {
        val snackbarMessage = activity.consumeSnackbarMessageForUi()
        val snackbarAction = activity.consumeSnackbarActionForUi()
        val callback = activity.consumeSnackbarCallbackForUi()
        if (!snackbarMessage.isNullOrBlank()) {
            val result = snackbarHostState.showSnackbar(snackbarMessage, snackbarAction, withDismissAction = true,
                duration = if (snackbarAction == null) androidx.compose.material3.SnackbarDuration.Short else androidx.compose.material3.SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                if (callback != null) callback.run() else activity.retrySnackbarActionForUi()
            }
        }
    }

    // Keep the Java host's invalidation state observable to Compose without replacing the
    // root view, which would reset LazyColumn scroll positions after every action.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics { stateDescription = "host-refresh-$refreshVersion" },
    ) {
        val expanded = maxWidth >= 840.dp
        val showMainNavigation = activity.widgetSettingsPluginForUi() == null && activity.managedPluginIdForUi() == null && selectedPlugin == null && destinations.any { it.section == selectedSection }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { if (activity.managedPluginIdForUi() == null && activity.widgetSettingsPluginForUi() == null) AppTopBar(activity, if (showMainNavigation) destinations[pagerState.currentPage].section else selectedSection, selectedPlugin) },
            bottomBar = {
                if (!expanded && showMainNavigation) {
                    AppNavigationBar(
                        activity,
                        pagerState,
                        onNavigate = navigateTo,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (expanded && showMainNavigation) {
                    AppNavigationRail(
                        activity,
                        pagerState,
                        onNavigate = navigateTo,
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    AppContent(
                        activity,
                        refreshVersion,
                        snackbarHostState,
                        pagerState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    UpdatePrompt(activity)
    ComposeDialog(activity)
    MigrationBridgeExportDialog(activity)
    MigrationBridgeImportDialog(activity)
    MigrationBridgeDeleteDialog(activity)
    HomeWidgetSettingsDialog(activity)
    CollectionDialogs(activity)
}

@Composable
private fun AppTopBar(
    activity: MainActivity,
    section: Int,
    plugin: HostTool?,
) {
    var menuExpanded by remember(plugin?.id(), section) { mutableStateOf(false) }
    when {
        plugin != null -> SuiteTopBar(
            title = plugin.title(),
            onBack = activity::handleBackForUi,
            actions = {
                IconButton(onClick = { activity.showPluginManagementForUi(plugin.id(), "detail") }) { Icon(Icons.Rounded.Tune, "插件设置") }
            },
        )
        // 关于是设置的子页，所以有返回箭头；设置本身是底栏分区，没有。
        section == ABOUT -> SuiteTopBar("关于", onBack = activity::handleBackForUi)
        section == 5 -> SuiteTopBar("数据管理", onBack = activity::handleBackForUi)
        section == 7 -> SuiteTopBar("任务与结果", onBack = activity::handleBackForUi)
        section == 8 -> SuiteTopBar("旧数据迁移", onBack = activity::handleBackForUi)
        section == 9 -> SuiteTopBar("开源许可", onBack = activity::handleBackForUi)
        else -> SuiteTopBar(destinations.firstOrNull { it.section == section }?.label ?: "Android Tool Suite")
    }
}

@Composable
private fun AppNavigationBar(
    activity: MainActivity,
    pagerState: PagerState,
    onNavigate: (Int) -> Unit,
) {
    val selectedSection = destinations[pagerState.currentPage].section
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(80.dp)) {
            val itemWidth = maxWidth / destinations.size
            val indicatorWidth = 64.dp
            val density = LocalDensity.current
            val itemWidthPx = with(density) { itemWidth.toPx() }
            val indicatorInsetPx = with(density) { ((itemWidth - indicatorWidth) / 2).toPx() }
            Box(
                Modifier
                    .offset(y = 12.dp)
                    // 在 layer 阶段读取滑动位置，只更新 GPU 平移矩阵，不触发底栏重新组合或布局。
                    .graphicsLayer {
                        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        translationX = itemWidthPx * position + indicatorInsetPx
                    }
                    .width(indicatorWidth)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            Row(Modifier.fillMaxSize()) {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        modifier = Modifier.weight(1f),
                        selected = selectedSection == destination.section,
                        onClick = { onNavigate(destination.section) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (destination.section == MANAGER && activity.availablePluginUpdatesForUi().isNotEmpty()) {
                                        Badge { Text(activity.availablePluginUpdatesForUi().size.toString()) }
                                    }
                                },
                            ) { Icon(destination.icon, contentDescription = destination.label) }
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppNavigationRail(
    activity: MainActivity,
    pagerState: PagerState,
    onNavigate: (Int) -> Unit,
) {
    val selectedSection = destinations[pagerState.currentPage].section
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = selectedSection == destination.section,
                onClick = { onNavigate(destination.section) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun AppContent(
    activity: MainActivity,
    refreshVersion: Int,
    snackbarHostState: SnackbarHostState,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    hostRevision(activity)  // 订阅：选中插件变化时 selected 不变但 Java 字段已变
    val selected = activity.selectedPluginForUi()
    val section = activity.currentSectionForUi()
    val widgetSnapshots = remember(activity) {
        com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(activity).widgetSnapshots()
    }
    val mainPagesVisible = activity.widgetSettingsPluginForUi() == null && selected == null && destinations.any { it.section == section } && activity.managedPluginIdForUi() == null
    LaunchedEffect(widgetSnapshots, pagerState, mainPagesVisible) {
        // Bottom navigation and swipes update PagerState without invalidating the Java host.
        // Observe the actual settled page so returning Home resumes pending widget reads.
        snapshotFlow { mainPagesVisible && pagerState.settledPage == DASHBOARD }
            .collect(widgetSnapshots::setVisible)
    }
    // Keep the two most recently used tools mounted; ordinary navigation preserves JS state.
    var recent by remember(activity) { mutableStateOf<List<HostTool>>(emptyList()) }
    val available = activity.allToolsForUi()
    val retained = (recent.filter { it in available && it !== selected } + listOfNotNull(selected)).takeLast(2)
    androidx.compose.runtime.SideEffect { if (recent != retained) recent = retained }
    Box(modifier.fillMaxSize()) {
        RetainedPage(activity.widgetSettingsPluginForUi() == null && selected == null && destinations.any { it.section == section } && activity.managedPluginIdForUi() == null) {
            SectionPager(activity, pagerState, refreshVersion, snackbarHostState, Modifier.fillMaxSize())
        }
        retained.forEach { plugin ->
            key(plugin) {
                androidx.compose.runtime.SideEffect { plugin.onVisibilityChanged(plugin === selected && activity.managedPluginIdForUi() == null && activity.widgetSettingsPluginForUi() == null) }
                RetainedPage(plugin === selected && activity.managedPluginIdForUi() == null && activity.widgetSettingsPluginForUi() == null) {
                    PluginDetailScreen(activity, plugin, refreshVersion, Modifier.fillMaxSize())
                }
            }
        }
        if (destinations.none { it.section == section } && selected == null) RetainedPage(activity.managedPluginIdForUi() == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = if (section == 5) 1100.dp else 720.dp).fillMaxSize()) {
                    when (section) {
                        ABOUT -> AboutScreen(activity, refreshVersion, Modifier.fillMaxSize())
                        5 -> DataCenterScreen(activity)
                        7 -> HostTasksScreen(activity)
                        8 -> CompatibilityScreen(activity)
                        9 -> OpenSourceScreen(activity)
                    }
                }
            }
        }
        activity.managedPluginIdForUi()?.let { PluginManagementScreen(activity, it) }
    }

}

/** Unplaced pages keep composition but cannot draw or receive input. */
@Composable
private fun RetainedPage(visible: Boolean, content: @Composable () -> Unit) {
    val semanticsModifier = if (visible) Modifier else Modifier.clearAndSetSemantics { }
    Layout(content = content, modifier = semanticsModifier.fillMaxSize()) { children, constraints ->
        val placeables = children.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (visible) placeables.forEach { it.placeRelative(0, 0) }
        }
    }
}

/**
 * 五个一级分区的左右滑动容器。
 *
 * [PagerState] 是页面、底栏选中态和滑动指示器的唯一状态源；宿主的 `currentSection` 只在页面停稳
 * 后更新，用于返回栈和从插件详情返回时恢复分区。
 */
@Composable
private fun SectionPager(
    activity: MainActivity,
    pagerState: PagerState,
    refreshVersion: Int,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    hostRevision(activity)  // 订阅：页面切换时 section 不变但内部 Java 字段已变
    val pagerFlingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        // 默认需要拖过半页；降到 30% 后短一些的明确横划也会翻页。
        snapPositionalThreshold = 0.3f,
    )
    val composeView = LocalView.current
    DisposableEffect(composeView, pagerState.isScrollInProgress) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (pagerState.isScrollInProgress) composeView.requestInteractiveFrameRate()
            else composeView.clearRequestedFrameRate()
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) composeView.clearRequestedFrameRate()
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val section = destinations[page].section
            if (section != activity.currentSectionForUi()) activity.setMainSectionFromPagerForUi(section)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        flingBehavior = pagerFlingBehavior,
        // 运行期只保留左右相邻页，连续来回滑动不重复创建，远页也不长期参与布局。
        pageSpacing = 0.dp,
        beyondViewportPageCount = 1,
        key = { it },
    ) { page ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val pageModifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
        when (destinations[page].section) {
            DASHBOARD -> DashboardScreen(activity, refreshVersion, pageModifier)
            PLUGINS -> ToolCollectionsScreen(activity, refreshVersion, pageModifier)
            MANAGER -> PluginCenterScreen(activity, discover = false, modifier = pageModifier)
            STORE -> PluginCenterScreen(activity, discover = true, modifier = pageModifier)
            else -> SettingsScreen(activity, refreshVersion, snackbarHostState, pageModifier)
        }
        }
    }
}

/**
 * 采用 AndroidX Pager 的常规跳页方式；只有跨越三页以上时绕开其有意设计的预跳行为。
 * AndroidX 会先瞬移到目标附近再补最后一段，这适合很长的信息流，却会让本应用只有五页的底栏
 * 看起来少播了一截。长跳转改为从当前位置连续滚完整距离，同时仍由 Pager 处理触摸中断与吸附。
 */
private suspend fun PagerState.animateToSection(targetPage: Int) {
    val target = targetPage.coerceIn(0, pageCount - 1)
    val remainingPages = target - currentPage - currentPageOffsetFraction
    if (abs(remainingPages) < 0.001f) return

    if (abs(target - currentPage) < 3) {
        animateScrollToPage(target)
        return
    }

    val pageDistancePx = layoutInfo.pageSize + layoutInfo.pageSpacing
    if (pageDistancePx <= 0) {
        animateScrollToPage(target)
        return
    }
    animateScrollBy(
        value = remainingPages * pageDistancePx,
        animationSpec = tween(
            durationMillis = 240 + abs(remainingPages).roundToInt() * 70,
            easing = FastOutSlowInEasing,
        ),
    )
}

/**
 * 图标底座：圆角方块 + secondaryContainer 底色。
 *
 * 之前每处都是裸 [Icon] 直接染 primary，图标本身的视觉重量差异（盾牌很实、扩展块很空）
 * 会让列表看上去参差不齐。统一加底座后，无论插件给什么图标，列表左沿都是同一个方块。
 * [dense] 对应管理屏那种一行一项的密排场景。
 */
@Composable
internal fun IconBox(
    icon: ImageVector,
    contentDescription: String?,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(if (dense) 38.dp else 48.dp)
            .background(
                MaterialTheme.colorScheme.secondaryContainer,
                if (dense) SuiteShapes.Chip else SuiteShapes.Inner,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(if (dense) 20.dp else 24.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * 工具页的一张卡。
 *
 * [interaction] 由调用方给出，参数是「长按时要做什么」——菜单的展开状态归这张卡自己管，
 * 但手势判定要和排序拖动共用一次 pointerInput，只能在外面拼。
 */
@Composable
private fun PluginDetailScreen(activity: MainActivity, plugin: HostTool, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    val contentModifier = if (activity.isRuntimeToolForUi(plugin)) {
        modifier
    } else {
        modifier.padding(horizontal = SuiteSpacing.lg, vertical = SuiteSpacing.sm)
    }
    PluginAndroidView(
        contentModifier
            .semantics { stateDescription = "plugin-$refreshVersion" }
            .fillMaxSize(),
    ) { plugin.createView(activity, activity) }
}

@Composable
internal fun RepositoryPluginCard(activity: MainActivity, release: UpdateCatalog.PluginRelease) {
    hostRevision(activity)
    val installed = activity.isRepositoryPluginVersionInstalledForUi(release)
    val selectable = activity.isRepositoryPluginVersionSelectableForUi(release)
    val compatible = activity.isRepositoryPluginCompatibleForUi(release)
    val busy = activity.isUpdateOperationRunningForUi(release.id)
    SuiteCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("v${release.versionName}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (installed) SuiteStatusChip("已安装", positive = true)
        }
        Text("${release.publishedAt.take(10)} · ${release.size / 1024} KB · Android ${androidVersionLabel(release.minAndroidApi)}+", style = MaterialTheme.typography.bodySmall)
        Text(activity.repositoryPluginTransitionLabelForUi(release), style = MaterialTheme.typography.bodySmall,
            color = if (selectable || installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        if (release.dependencies.isNotEmpty()) Text("依赖：${release.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (busy) RepositoryDownloadProgress(activity, release.id)
        if (Build.VERSION.SDK_INT < release.minAndroidApi) {
            Notice("需要 Android ${androidVersionLabel(release.minAndroidApi)} 或更高版本；请先升级系统。", warning = true)
        } else if (!compatible) {
            Notice("需要应用 ${activity.requiredAppVersionLabelForUi(release.minHostVersionCode)} 或更高版本", warning = true)
            if (!activity.isDebugBuildForUi()) OutlinedButton(onClick = activity::installAppUpdateForUi, enabled = activity.appUpdateForUi() != null) { Text("更新应用") }
        } else {
            val action=activity.repositoryPluginActionLabelForUi(release)
            if(action=="安装"||action=="更新") Button(onClick={activity.installRepositoryPluginVersionForUi(release)},enabled=selectable&&!busy,modifier=Modifier.fillMaxWidth()) { Text(action) }
            else OutlinedButton(onClick={activity.installRepositoryPluginVersionForUi(release)},enabled=selectable&&!busy,modifier=Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

@Composable
internal fun RepositoryDownloadProgress(activity: MainActivity, pluginId: String) {
    val progress = activity.downloadProgressForUi(pluginId)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        if (progress > 0f) "正在下载 ${(progress * 100).roundToInt()}%" else "正在准备下载…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsScreen(
    activity: MainActivity,
    refreshVersion: Int,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    hostRevision(activity)
    val autoCheckUpdates = activity.autoCheckUpdatesForUi()
    val listState = rememberPageListState(activity, "settings")
    var themeMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    val themeLabel = when (activity.themePreferenceForUi()) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }
    val colorLabel = if (activity.colorPreferenceForUi() == "dynamic") "跟随壁纸" else "应用自带"
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "settings-$refreshVersion" },
        contentPadding = PaddingValues(vertical = SuiteSpacing.lg),
    ) {
        item {
            SuiteSettingsGroup("外观") {
                SettingsDropdownRow(
                    title = "主题",
                    selectedValue = activity.themePreferenceForUi(),
                    selectedLabel = themeLabel,
                    options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                    expanded = themeMenu,
                    onExpandedChange = { themeMenu = it },
                    onSelected = activity::setThemePreferenceForUi,
                )
                SettingsDropdownRow(
                    title = "应用配色",
                    selectedValue = activity.colorPreferenceForUi(),
                    selectedLabel = colorLabel,
                    options = listOf("brand" to "应用自带", "dynamic" to "跟随壁纸"),
                    expanded = colorMenu,
                    onExpandedChange = { colorMenu = it },
                    onSelected = activity::setColorPreferenceForUi,
                )
            }
        }
        item {
            SuiteSettingsGroup("应用更新") {
                if (activity.isDebugBuildForUi()) SuiteSettingsRow("本地 Debug 构建", leadingIcon = Icons.Rounded.Info, supportingText = "通过本地安装更新，不提供远端应用更新")
                else {
                    SuiteSettingsSwitchRow("自动检查应用更新", supportingText = "只检查正式发布的应用版本", checked = activity.autoCheckAppUpdatesForUi(), onCheckedChange = activity::setAutoCheckAppUpdatesForUi)
                    SuiteSettingsRow("检查应用更新", leadingIcon = Icons.Rounded.Refresh, onClick = activity::checkAppUpdatesForUi, emphasized = true)
                    activity.appUpdateForUi()?.let { release -> SuiteSettingsRow("更新到 ${release.versionName}", onClick = activity::installAppUpdateForUi) }
                }
            }
        }
        item {
            SuiteSettingsGroup("插件更新") {
                SuiteSettingsSwitchRow(
                    "自动检查插件更新",
                    supportingText = "所有构建均使用正式插件目录",
                    checked = autoCheckUpdates,
                    onCheckedChange = activity::setAutoCheckUpdatesForUi,
                )
                SuiteSettingsRow(
                    "检查插件更新",
                    leadingIcon = Icons.Rounded.Refresh,
                    supportingText = "正式插件目录 · 上次检查：${formatLastChecked(activity.lastUpdateCheckForUi())}",
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar("正在检查更新…") }
                        activity.checkPluginUpdatesForUi()
                    },
                    trailingText = if (activity.isUpdateOperationRunningForUi("__check__")) "正在检查…" else null,
                    emphasized = true,
                )
            }
        }
        item {
            SuiteSettingsGroup("管理") {
                SuiteSettingsRow("数据管理", leadingIcon = Icons.Rounded.Folder, supportingText = "所有插件的备份、恢复与清理", onClick = { activity.showDataManagementForUi(null) })
                SuiteSettingsRow("任务与结果", leadingIcon = Icons.Rounded.Notifications, supportingText = "本次应用使用中的操作与结果", onClick = activity::showTaskListForUi)
            }
        }
        item {
            SuiteSettingsGroup("关于") {
                SuiteSettingsRow(
                    "关于 Android Tool Suite",
                    supportingText = activity.appVersionNameForUi(),
                    leading = { IconBox(Icons.Rounded.Info, null, dense = true) },
                    trailing = { Icon(Icons.Rounded.ChevronRight, null) },
                    onClick = activity::showAboutForUi,
                )
            }
        }
    }
}

@Composable
private fun SettingsDropdownRow(
    title: String,
    selectedValue: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (String) -> Unit,
    supportingText: String? = null,
) {
    Box {
        SuiteSettingsRow(
            title = title,
            supportingText = supportingText,
            leadingIcon = if (title == "主题") Icons.Rounded.LightMode else Icons.Rounded.Palette,
            trailingText = selectedLabel,
            trailing = {
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = "展开$title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = { onExpandedChange(true) },
        )
        Box(Modifier.fillMaxWidth().wrapContentSize(Alignment.TopEnd)) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.widthIn(min = 168.dp),
            ) {
                options.forEach { (value, label) ->
                    val selected = value == selectedValue
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        leadingIcon = {
                            if (selected) {
                                Icon(Icons.Rounded.Check, contentDescription = "已选择", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            onExpandedChange(false)
                            onSelected(value)
                        },
                    )
                }
            }
        }
    }
}

private fun formatLastChecked(timestamp: Long): String {
    if (timestamp <= 0L) return "尚未检查"
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(now)) ==
            SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(timestamp))
    return if (sameDay) SimpleDateFormat("今天 HH:mm", Locale.CHINA).format(Date(timestamp))
    else SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(timestamp))
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AboutScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    val clipboard = LocalClipboardManager.current
    val fullVersion = "${activity.appVersionNameForUi()} (${activity.appVersionCodeForUi()}) · SDK ${activity.pluginSdkVersionForUi()} · ${activity.buildTypeForUi()}"
    LazyColumn(
        modifier = modifier.semantics { stateDescription = "about-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = SuiteSpacing.ScreenPadding, vertical = SuiteSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xl),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(66.dp)
                        .background(MaterialTheme.colorScheme.primary, SuiteShapes.Inner),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Build, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.height(SuiteSpacing.md))
                Text("安卓工具合集", style = MaterialTheme.typography.headlineSmall)
                Text(activity.appVersionNameForUi(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            // 一整组不分小标题：这一屏只有「这个应用的身份信息」一件事，
            // 拆成「版本信息 / 项目」两组只是给五行内容加了两条无用的分割。
            SuiteSettingsGroup("") {
                SuiteSettingsRow(
                    "版本号",
                    // 长按能复制这件事必须写出来，否则没有任何提示。
                    supportingText = "长按复制完整版本串",
                    trailingText = "${activity.appVersionNameForUi()} (${activity.appVersionCodeForUi()})",
                    modifier = Modifier.combinedClickable(
                        onClickLabel = "复制完整版本信息",
                        onClick = {
                            clipboard.setText(AnnotatedString(fullVersion))
                            activity.showToast("已复制完整版本信息")
                        },
                        onLongClick = {
                            clipboard.setText(AnnotatedString(fullVersion))
                            activity.showToast("已复制完整版本信息")
                        },
                    ),
                )
                SuiteSettingsRow("插件 SDK", trailingText = activity.pluginSdkVersionForUi())
                SuiteSettingsRow("构建类型", trailingText = activity.buildTypeForUi())
                if (activity.buildCommitForUi().isNotBlank()) SuiteSettingsRow("构建提交", trailingText = activity.buildCommitForUi().take(12))
                SuiteSettingsRow(
                    "项目地址",
                    supportingText = "github.com/android-tool-suite",
                    trailing = { Icon(Icons.Rounded.ChevronRight, null) },
                    onClick = activity::openProjectForUi,
                )
                SuiteSettingsRow("发布中心", supportingText = "应用和插件的正式下载目录", trailing = { Icon(Icons.Rounded.ChevronRight, null) }, onClick = { activity.openSourceUrlForUi("https://android-tool-suite.github.io/plugin-registry/") })
                SuiteSettingsRow(
                    "开源许可",
                    trailing = { Icon(Icons.Rounded.ChevronRight, null) },
                    onClick = activity::showOpenSourceLicensesForUi,
                )
            }
            Text(
                "插件仓库仅收录 Android Tool Suite 组织维护的插件。",
                modifier = Modifier.padding(horizontal = SuiteSpacing.ScreenPadding, vertical = SuiteSpacing.md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdatePromptRow(icon: ImageVector, title: String, transition: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
        IconBox(icon, null, dense = true)
        Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(transition, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdatePrompt(activity: MainActivity) {
    hostRevision(activity)
    if (!activity.isUpdatePromptVisibleForUi()) return
    val appUpdate = activity.promptAppUpdateForUi()
    val pluginUpdates = activity.promptPluginUpdatesForUi()
    AlertDialog(
        onDismissRequest = activity::closeUpdatePromptForUi,
        title = { Text("发现 ${activity.promptUpdateCountForUi()} 项更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                if (appUpdate != null) {
                    UpdatePromptRow(
                        icon = Icons.Rounded.Build,
                        title = "安卓工具合集",
                        transition = "${activity.appVersionNameForUi()} → ${appUpdate.versionName}",
                    )
                }
                pluginUpdates.forEach { release ->
                    UpdatePromptRow(
                        icon = Icons.Rounded.Extension,
                        title = release.title,
                        transition = "→ ${release.versionName}",
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = activity::dismissCurrentUpdatesForUi) { Text("本次不再提示") }
                TextButton(onClick = activity::closeUpdatePromptForUi) { Text("稍后") }
                Button(onClick = activity::installPromptUpdatesForUi) { Text("全部更新") }
            }
        },
    )
}

@Composable
private fun ComposeDialog(activity: MainActivity) {
    hostRevision(activity)
    val dialog = activity.composeDialogForUi() ?: return
    var migrationExplanation by remember(dialog) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (dialog.dismissible) activity.dismissComposeDialogForUi() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = dialog.dismissible,
            dismissOnClickOutside = dialog.dismissible,
        ),
        title = { Text(dialog.title) },
        text = { Column {
            Text(dialog.message)
            if (dialog.title == "导入旧版 Android Tool Suite 迁移包？") {
                TextButton(onClick = { migrationExplanation = !migrationExplanation }) { Text(if(migrationExplanation) "收起迁移说明" else "查看迁移说明") }
                if(migrationExplanation) Text("未迁移的旧插件数据应先在支持 Migration Bridge 的旧版中导出。当前仅恢复可识别的应用状态，不安装旧插件代码，也不继承旧 Shizuku 授权。原有业务数据不会因此清理。",style=MaterialTheme.typography.bodySmall)
            }
        } },
        dismissButton = {
            if (dialog.dismissLabel.isNotBlank()) {
                TextButton(onClick = activity::dismissComposeDialogForUi) { Text(dialog.dismissLabel) }
            }
        },
        confirmButton = {
            if (dialog.dismissible) {
                TextButton(onClick = activity::confirmComposeDialogForUi) { Text(dialog.confirmLabel) }
            } else {
                Button(onClick = activity::confirmComposeDialogForUi) { Text(dialog.confirmLabel) }
            }
        },
    )
}

@Composable
internal fun PluginPermissionRow(
    permission: PluginPermissionManager.Permission,
    scope: String,
    onGrantedChange: (Boolean) -> Unit,
) {
    val granted = permission.state == PluginPermissionManager.State.GRANTED
    val stateLabel = when {
        permission.state == PluginPermissionManager.State.GRANTED -> "已允许"
        permission.state == PluginPermissionManager.State.DENIED -> "已拒绝"
        else -> "待决定"
    }
    val riskLabel = when (permission.risk) {
        "restricted" -> "敏感操作"
        "sensitive" -> "需授权"
        else -> stateLabel
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.lg, vertical = SuiteSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                Text(permission.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                SuiteStatusChip(
                    text = if (permission.risk == "normal") stateLabel else "$riskLabel · $stateLabel",
                    positive = granted && permission.risk != "restricted",
                )
            }
            Text(
                permission.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(scope, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (permission.optional) {
                Text("可选权限；拒绝后插件仍可使用其他功能。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            modifier = Modifier.semantics { contentDescription = permission.title },
            checked = granted,
            onCheckedChange = onGrantedChange,
            enabled = true,
        )
    }
}

@Composable
private fun PluginMetadata(plugin: HostTool) {
    val text = buildList {
        add("v${plugin.version()}")
        if (plugin.dependencies().isNotEmpty()) add("${plugin.dependencies().size} 项依赖")
    }.joinToString(" · ")
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
}

internal fun pluginIcon(plugin: HostTool): ImageVector = pluginIconForId(plugin.id())

internal fun pluginIconForId(id: String): ImageVector = when {
    id.contains("shizuku") -> Icons.Rounded.Security
    id.contains("phigros") -> Icons.Rounded.QueryStats
    id.contains("gacha") -> Icons.Rounded.AutoAwesome
    id.contains("accessibility") -> Icons.Rounded.AccessibilityNew
    id.contains("host") -> Icons.Rounded.Settings
    else -> Icons.Rounded.Extension
}

@Composable
internal fun PluginAndroidView(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    factory: () -> View,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        onRelease = { (it as? androidx.compose.ui.platform.ComposeView)?.disposeComposition() },
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
