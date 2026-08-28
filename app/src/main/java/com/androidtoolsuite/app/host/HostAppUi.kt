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
import androidx.compose.ui.viewinterop.AndroidView
import com.androidtoolsuite.app.plugin.api.HomeWidget
import com.androidtoolsuite.app.plugin.api.ToolPlugin
import com.androidtoolsuite.app.migration.MigrationBridgeManager
import com.androidtoolsuite.app.plugin.migration.DatasetCategory
import com.androidtoolsuite.app.plugin.migration.DatasetRestoreMode
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.plugin.v2.V2PluginPermissionManager
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
private const val STORE = 3
private const val SETTINGS = 4
private const val ABOUT = 5

/** State bridge for Java-host callbacks. MutableState is observed by Compose directly. */
class HostUiState {
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
private fun hostRevision(activity: MainActivity): Int = activity.uiStateForUi().revision

@Composable
private fun rememberPageListState(activity: MainActivity, page: String): LazyListState {
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = activity.scrollIndexForUi(page),
        initialFirstVisibleItemScrollOffset = activity.scrollOffsetForUi(page),
    )
    LaunchedEffect(state, page) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> activity.saveScrollPositionForUi(page, index, offset) }
    }
    return state
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
    Destination(MANAGER, "管理", Icons.Rounded.Tune),
    Destination(STORE, "仓库", Icons.Rounded.Storefront),
    Destination(SETTINGS, "设置", Icons.Rounded.Settings),
)

@Composable
private fun HostApp(activity: MainActivity) {
    val refreshVersion = hostRevision(activity)
    val selectedSection = activity.currentSectionForUi()
    val selectedPlugin = activity.selectedPluginForUi()
    val pagerState = rememberPagerState(
        initialPage = selectedSection.coerceIn(DASHBOARD, SETTINGS),
        pageCount = destinations::size,
    )
    val navigationScope = rememberCoroutineScope()
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    val navigateTo: (Int) -> Unit = { requestedPage ->
        val targetPage = requestedPage.coerceIn(DASHBOARD, SETTINGS)
        navigationJob?.cancel()
        navigationJob = navigationScope.launch { pagerState.animateToSection(targetPage) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(selectedSection, selectedPlugin) {
        if (selectedPlugin == null && selectedSection in DASHBOARD..SETTINGS) {
            if (pagerState.settledPage != selectedSection || pagerState.currentPageOffsetFraction != 0f) {
                navigationJob?.cancel()
                navigationJob = navigationScope.launch { pagerState.animateToSection(selectedSection) }
            }
        }
    }
    BackHandler(enabled = activity.canHandleBackForUi()) { activity.handleBackForUi() }
    LaunchedEffect(refreshVersion) {
        val snackbarMessage = activity.consumeSnackbarMessageForUi()
        val snackbarAction = activity.consumeSnackbarActionForUi()
        if (!snackbarMessage.isNullOrBlank()) {
            val result = snackbarHostState.showSnackbar(snackbarMessage, snackbarAction)
            if (result == SnackbarResult.ActionPerformed) activity.retrySnackbarActionForUi()
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
        val showMainNavigation = selectedPlugin == null && selectedSection in DASHBOARD..SETTINGS
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { AppTopBar(activity, selectedSection, selectedPlugin) },
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
            Row(Modifier.fillMaxSize().padding(padding)) {
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
                        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
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
}

@Composable
private fun AppTopBar(
    activity: MainActivity,
    section: Int,
    plugin: ToolPlugin?,
) {
    var menuExpanded by remember(plugin?.id(), section) { mutableStateOf(false) }
    when {
        plugin != null -> SuiteTopBar(
            title = plugin.title(),
            onBack = activity::closePluginForUi,
            actions = {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("在管理中设置") },
                        onClick = { menuExpanded = false; activity.navigateForUi(MANAGER) },
                    )
                    if (plugin.removable()) {
                        DropdownMenuItem(
                            text = { Text("删除插件") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menuExpanded = false; activity.requestDeletePluginForUi(plugin.id()) },
                        )
                    }
                }
            },
        )
        // 关于是设置的子页，所以有返回箭头；设置本身是底栏分区，没有。
        section == ABOUT -> SuiteTopBar("关于", onBack = activity::handleBackForUi)
    }
}

@Composable
private fun AppNavigationBar(
    activity: MainActivity,
    pagerState: PagerState,
    onNavigate: (Int) -> Unit,
) {
    val selectedSection = pagerState.currentPage
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
                                    if (destination.section == STORE && activity.availableUpdateCountForUi() > 0) {
                                        Badge { Text(activity.availableUpdateCountForUi().toString()) }
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
    val selectedSection = pagerState.currentPage
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
    when {
        // 插件详情和关于都是压在分区之上的独立页面，不参与左右滑动。
        selected != null -> PluginDetailScreen(activity, selected, refreshVersion, modifier.fillMaxSize())
        section == ABOUT -> AboutScreen(activity, refreshVersion, modifier.fillMaxSize())
        else -> SectionPager(
            activity,
            pagerState,
            refreshVersion,
            snackbarHostState,
            modifier.fillMaxSize(),
        )
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
            if (page != activity.currentSectionForUi()) activity.setMainSectionFromPagerForUi(page)
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
        when (page) {
            DASHBOARD -> DashboardScreen(activity, refreshVersion, Modifier.fillMaxSize())
            PLUGINS -> PluginListScreen(activity, refreshVersion, Modifier.fillMaxSize())
            MANAGER -> ManagerScreen(activity, refreshVersion, Modifier.fillMaxSize())
            STORE -> PluginRepositoryScreen(activity, refreshVersion, snackbarHostState, Modifier.fillMaxSize())
            else -> SettingsScreen(activity, refreshVersion, snackbarHostState, Modifier.fillMaxSize())
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

@Composable
private fun DashboardScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    val plugins = activity.pluginsForUi()
    val widgets = activity.widgetsForUi()
    val hidden = activity.allWidgetsForUi().filterNot(activity::isWidgetVisibleForUi)
    var addSheetVisible by remember { mutableStateOf(false) }
    val listState = rememberPageListState(activity, "dashboard")
    if (addSheetVisible) {
        AddWidgetSheet(activity, hidden) { addSheetVisible = false }
    }

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
                "集中查看运行状态，快速进入常用工具。",
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
                            "${widgets.size} 个主页组件",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            // 隐藏项的入口跟着「有没有隐藏项」出现在分段标题右侧，而不是常驻一个空位。
            SectionHeader("主页小部件", "长按调整大小或移除 · 长按拖动排序") {
                if (hidden.isNotEmpty()) {
                    TextButton(onClick = { addSheetVisible = true }) { Text("添加 ${hidden.size}") }
                }
            }
        }
        if (widgets.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                    if (hidden.isEmpty()) {
                        EmptyState("主页很清爽", "在管理中开启插件的主页显示。")
                        OutlinedButton(onClick = { activity.navigateForUi(MANAGER) }) { Text("前往管理") }
                    } else {
                        EmptyState("主页很清爽", "${hidden.size} 个小部件被移除了，可以重新添加回来。")
                        OutlinedButton(onClick = { addSheetVisible = true }) { Text("添加小部件") }
                    }
                }
            }
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
                        onMoveEarlier = {
                            widgets.getOrNull(index - 1)?.let { activity.moveWidgetToUi(widget, it) }
                        },
                        onMoveLater = {
                            widgets.getOrNull(index + 1)?.let { activity.moveWidgetToUi(widget, it) }
                        },
                        onMoveFirst = {
                            widgets.firstOrNull()?.takeIf { it !== widget }?.let { activity.moveWidgetToUi(widget, it) }
                        },
                        onMoveLast = {
                            widgets.lastOrNull()?.takeIf { it !== widget }?.let { activity.moveWidgetToUi(widget, it) }
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
        val placeholderIndex = widgets.size
        val logicalIndices = widgets.indices.toMutableList()
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

        // 摆一格：算出它落在当前行还是下一行，量好、记下位置。
        fun placeCell(measurableIndex: Int, widthUnits: Int, heightUnits: Int) {
            val units = widthUnits.coerceIn(1, 4)
            if (usedUnits > 0 && usedUnits + units > 4) finishRow()
            val itemWidth = (unitWidth * units + gapPx * (units - 1)).roundToInt()
            val itemHeight = (72.dp * heightUnits + gap * (heightUnits - 1)).roundToPx()
            val placeable = measurables[measurableIndex].measure(Constraints.fixed(itemWidth, itemHeight))
            placeables[measurableIndex] = placeable
            positions[measurableIndex][0] = ((unitWidth + gapPx) * usedUnits).roundToInt()
            rowMeasurableIndices += measurableIndex
            usedUnits += units
            rowHeight = maxOf(rowHeight, placeable.height)
            if (usedUnits == 4) finishRow()
        }

        logicalIndices.forEach { logicalIndex ->
            val isPlaceholder = logicalIndex < 0
            val widgetIndex = if (isPlaceholder) draggedIndex else logicalIndex
            val measurableIndex = if (isPlaceholder) placeholderIndex else widgetIndex
            val widget = widgets[widgetIndex]
            placeCell(
                measurableIndex = measurableIndex,
                widthUnits = activity.widgetWidthUnitsForUi(widget),
                heightUnits = activity.widgetHeightUnitsForUi(widget).coerceAtLeast(1),
            )
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
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onMoveFirst: () -> Unit,
    onMoveLast: () -> Unit,
) {
    val shape = SuiteShapes.Card
    var menuExpanded by remember(widget.pluginId(), widget.id()) { mutableStateOf(false) }
    val width = activity.widgetWidthUnitsForUi(widget)
    val height = activity.widgetHeightUnitsForUi(widget)
    Box(
        modifier = modifier
            .dropPreviewReorder(
                key = widgetKey(widget),
                onClick = onClick,
                onLongPress = { menuExpanded = true },
                dragState = dragState,
                sourceIndex = sourceIndex,
                onDrop = onDrop,
                allowHorizontalDrag = true,
            )
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上移") { onMoveEarlier(); true },
                    CustomAccessibilityAction("下移") { onMoveLater(); true },
                    CustomAccessibilityAction("移到开头") { onMoveFirst(); true },
                    CustomAccessibilityAction("移到末尾") { onMoveLast(); true },
                    CustomAccessibilityAction("从主页移除") {
                        activity.setWidgetVisibleForUi(widget, false)
                        true
                    },
                )
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        PluginAndroidView(Modifier.fillMaxSize()) { widget.createView(activity, activity) }
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
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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
            DropdownMenuItem(
                text = { Text("从主页移除") },
                leadingIcon = { Icon(Icons.Rounded.RemoveCircle, null) },
                onClick = {
                    activity.setWidgetVisibleForUi(widget, false)
                    menuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun AddWidgetSheet(activity: MainActivity, hidden: List<HomeWidget>, onDismiss: () -> Unit) {
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
    hostRevision(activity)
    val plugins = activity.pluginsForUi()
    val hiddenCount = activity.allToolsForUi().count { !activity.isToolVisibleForUi(it) }
    val listState = rememberPageListState(activity, "tools")
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "tools-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "点击打开 · 长按拖动排序或调整",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SuiteSpacing.xs))
        }
        if (plugins.isEmpty()) item { EmptyState("没有可用工具", "请先在插件管理中启用插件。") }
        if (plugins.isNotEmpty()) item { ToolReorderList(activity, plugins, Modifier.fillMaxWidth()) }
        if (hiddenCount > 0) {
            // 隐藏项不在这一页出现，得说清它们去哪了，否则「工具少了一个」看不出是自己隐藏的。
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$hiddenCount 个工具已隐藏",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { activity.navigateForUi(MANAGER) }) { Text("在管理中恢复") }
                }
            }
        }
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
                    fun moveTo(target: ToolPlugin?) {
                        target?.takeIf { it !== plugin }?.let { activity.moveToolToUi(plugin.id(), it.id()) }
                    }
                    PluginListCard(
                        activity = activity,
                        plugin = plugin,
                        interaction = { onLongPress ->
                            Modifier.dropPreviewReorder(
                                key = plugin.id(),
                                onClick = { activity.openPluginForUi(plugin) },
                                onLongPress = onLongPress,
                                dragState = dragState,
                                sourceIndex = index,
                                onDrop = { destination -> moveTo(plugins.getOrNull(destination)) },
                                allowHorizontalDrag = false,
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

/**
 * 图标底座：圆角方块 + secondaryContainer 底色。
 *
 * 之前每处都是裸 [Icon] 直接染 primary，图标本身的视觉重量差异（盾牌很实、扩展块很空）
 * 会让列表看上去参差不齐。统一加底座后，无论插件给什么图标，列表左沿都是同一个方块。
 * [dense] 对应管理屏那种一行一项的密排场景。
 */
@Composable
private fun IconBox(
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
private fun PluginListCard(
    activity: MainActivity,
    plugin: ToolPlugin,
    interaction: @Composable (onLongPress: () -> Unit) -> Modifier,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(plugin.id()) { mutableStateOf(false) }
    Box(modifier = modifier) {
        SuiteCard(modifier = interaction { menuExpanded = true }) {
            Row(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                IconBox(pluginIcon(plugin), "${plugin.title()}工具")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    // 版本号在标题右侧做次要标签，而不是标题下方的独立 chip：
                    // chip 的视觉重量跟「可点」暗示都太强，版本号只是参考信息。
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                        Text(plugin.title(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            "v${plugin.version()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            DropdownMenuItem(
                text = { Text("从工具页隐藏") },
                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                onClick = {
                    menuExpanded = false
                    activity.setToolVisibleForUi(plugin, false)
                },
            )
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
                text = { Text("在管理中设置") },
                leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                onClick = {
                    menuExpanded = false
                    activity.navigateForUi(MANAGER)
                },
            )
        }
    }
}

@Composable
private fun PluginDetailScreen(activity: MainActivity, plugin: ToolPlugin, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
    val contentModifier = if (activity.isRuntimeV2ToolForUi(plugin)) {
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
private fun ManagerScreen(activity: MainActivity, refreshVersion: Int, modifier: Modifier = Modifier) {
    hostRevision(activity)
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
            Text("控制插件是否启用，以及在哪里显示。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (optionalBuiltIns.isNotEmpty()) {
            item { SectionHeader("系统工具") }
            items(optionalBuiltIns, key = ToolPlugin::id) { plugin ->
                ManagedPluginCard(
                    pluginId = plugin.id(),
                    title = plugin.title(),
                    version = plugin.version(),
                    icon = pluginIcon(plugin),
                    enabled = activity.isBuiltInPluginEnabled(plugin.id()),
                    loadedPlugin = activity.findToolForUi(plugin.id()),
                    refreshVersion = refreshVersion,
                    onEnabledChange = { activity.setBuiltInPluginEnabled(plugin.id(), it) },
                    activity = activity,
                )
            }
        }
        item { SectionHeader("已安装插件", "${imported.size} 个") }
        if (imported.isEmpty()) item { EmptyState("尚未安装插件", "可在仓库中安装，或导入本地插件包。") }
        items(imported, key = ImportedPluginDescriptor::id) { descriptor ->
            ManagedPluginCard(
                pluginId = descriptor.id,
                title = descriptor.title,
                version = descriptor.version,
                icon = Icons.Rounded.Extension,
                enabled = activity.isImportedPluginEnabled(descriptor.id),
                loadedPlugin = activity.findToolForUi(descriptor.id),
                refreshVersion = refreshVersion,
                onEnabledChange = { activity.setImportedPluginEnabled(descriptor.id, it) },
                activity = activity,
                removable = true,
                activationPending = activity.isRuntimeV2ActivationPendingForUi(descriptor.id),
                loadFailed = activity.isImportedPluginEnabled(descriptor.id) &&
                    !activity.isPluginLoadedForUi(descriptor.id) &&
                    !activity.isRuntimeV2ActivationPendingForUi(descriptor.id),
            )
        }
    }
}

@Composable
private fun ManagedPluginCard(
    activity: MainActivity,
    pluginId: String,
    title: String,
    version: String,
    icon: ImageVector,
    enabled: Boolean,
    loadedPlugin: ToolPlugin?,
    refreshVersion: Int,
    onEnabledChange: (Boolean) -> Unit,
    removable: Boolean = false,
    loadFailed: Boolean = false,
    activationPending: Boolean = false,
) {
    var expanded by remember(title) { mutableStateOf(false) }
    val hasHomeWidgets = enabled && loadedPlugin != null && activity.hasHomeWidgetsForUi(loadedPlugin)
    val toolVisible = loadedPlugin?.let(activity::isToolVisibleForUi) ?: false
    val homeVisible = loadedPlugin?.let(activity::isPluginHomeVisibleForUi) ?: false
    val permissions = activity.pluginPermissionsForUi(pluginId)
    val trustedProvider = activity.isTrustedProviderForUi(pluginId)
    val visibilitySummary = when {
        toolVisible && hasHomeWidgets && homeVisible -> "工具页 · 主页"
        toolVisible -> "仅工具页"
        hasHomeWidgets && homeVisible -> "仅主页"
        else -> "已隐藏"
    }
    val summary = buildString {
        append(
            when {
                !enabled -> "v$version · 已停用"
                activationPending -> "v$version · 等待重启激活"
                else -> "v$version · $visibilitySummary"
            },
        )
        if (enabled && trustedProvider) append(" · 完全信任")
        // 关掉更新检查是个容易忘的设置，收起时也得看得见。
        if (removable && !activity.isPluginUpdateCheckEnabledForUi(pluginId)) append(" · 不检查更新")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.62f }
            .semantics { stateDescription = "managed-plugin-$title-$refreshVersion" },
        shape = SuiteShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // 展开项用内描边标出来。多项可同时展开，没有描边时很难看出某个开关区属于上面哪张卡。
        border = if (expanded) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            // 内边距放在 Row 里而不是外层 Column 上：否则卡片左右各 16dp 是死区，
            // 点在标题左侧或箭头右侧都展不开。
            Row(
                Modifier
                    .fillMaxWidth()
                    // 停用的插件也要能展开：删除和导出在展开区里，停用之后才更需要它们。
                    .clickable { expanded = !expanded }
                    .padding(horizontal = SuiteSpacing.lg, vertical = SuiteSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
            ) {
                IconBox(icon, null, dense = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起" else "展开",
                    tint = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = SuiteSpacing.lg, vertical = SuiteSpacing.xs)) {
                    if (loadedPlugin != null) {
                        VisibilitySwitch(
                            "在工具页显示",
                            toolVisible,
                            { activity.setToolVisibleForUi(loadedPlugin, it) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                        if (hasHomeWidgets) {
                            VisibilitySwitch(
                                "在主页显示",
                                homeVisible,
                                { activity.setPluginHomeVisibleForUi(loadedPlugin, it) },
                                modifier = Modifier.heightIn(min = 40.dp),
                            )
                        } else {
                            // 明确说清「没有」，否则少一行开关会被当成加载失败。
                            Text(
                                "此插件没有主页小部件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = SuiteSpacing.sm),
                            )
                        }
                    }
                    if (permissions.isNotEmpty()) {
                        Text(
                            "插件权限",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = SuiteSpacing.sm, bottom = SuiteSpacing.sm),
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = SuiteSpacing.md),
                            shape = SuiteShapes.Inner,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                permissions.forEachIndexed { index, permission ->
                                    PluginPermissionRow(
                                        permission = permission,
                                        scope = activity.pluginPermissionScopeForUi(permission),
                                        onGrantedChange = {
                                            activity.setPluginPermissionForUi(pluginId, permission.capabilityId, it)
                                        },
                                    )
                                    if (index != permissions.lastIndex) HorizontalDivider()
                                }
                            }
                        }
                    } else if (trustedProvider) {
                        Notice(
                            "这是完全信任的系统插件。启用后，它可以直接使用本应用拥有的系统权限，无法逐项限制。其他插件使用它提供的系统功能时，仍需单独获得允许。",
                            warning = true,
                            modifier = Modifier.padding(bottom = SuiteSpacing.md),
                        )
                    } else if (removable && !activity.isRuntimeV2PluginForUi(pluginId)) {
                        Notice(
                            "此旧版插件可以直接使用应用拥有的功能，无法逐项限制。请只安装来源可信的版本。",
                            warning = true,
                            modifier = Modifier.padding(bottom = SuiteSpacing.md),
                        )
                    }
                    // 外部插件才有更新与删除；内置插件跟着应用走。
                    if (removable) {
                        VisibilitySwitch(
                            "检查更新",
                            activity.isPluginUpdateCheckEnabledForUi(pluginId),
                            { activity.setPluginUpdateCheckEnabledForUi(pluginId, it) },
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = SuiteSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
                        ) {
                            OutlinedButton(onClick = { activity.exportPlugin(pluginId) }, modifier = Modifier.weight(1f)) {
                                Text("导出")
                            }
                            OutlinedButton(
                                onClick = { activity.requestDeletePluginForUi(pluginId) },
                                modifier = Modifier.weight(1f),
                            ) { Text("删除") }
                        }
                    }
                    if (activity.hasPluginDataForUi(pluginId, loadedPlugin)) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = SuiteSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
                        ) {
                            OutlinedButton(
                                onClick = { activity.prepareMigrationBridgeExportForPluginUi(pluginId) },
                                modifier = Modifier.weight(1f),
                            ) { Text("导出数据") }
                            OutlinedButton(
                                onClick = { activity.importMigrationBridgeForPluginUi(pluginId) },
                                modifier = Modifier.weight(1f),
                            ) { Text("导入数据") }
                            OutlinedButton(
                                onClick = { activity.prepareMigrationBridgeDeleteForPluginUi(pluginId) },
                                modifier = Modifier.weight(1f),
                            ) { Text("清除数据") }
                        }
                    }
                }
            }
            if (loadFailed) {
                Column(Modifier.padding(start = SuiteSpacing.lg, end = SuiteSpacing.lg, bottom = SuiteSpacing.md)) {
                    ErrorState("插件无法加载", "请重新安装插件。", onRetry = activity::importPlugin)
                }
            }
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
private fun PluginRepositoryScreen(
    activity: MainActivity,
    refreshVersion: Int,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    hostRevision(activity)
    val scope = rememberCoroutineScope()
    val plugins = activity.repositoryPluginsForUi()
    val installed = activity.importedDescriptorsForUi()
    val installedIds = installed.mapTo(mutableSetOf(), ImportedPluginDescriptor::id)
    val available = plugins.filterNot { it.id in installedIds }
    val appUpdate = activity.appUpdateForUi()
    val listState = rememberPageListState(activity, "plugin-repository")
    var showRisk by remember { mutableStateOf(activity.shouldShowStoreRiskForUi()) }
    if (showRisk) {
        AlertDialog(
            onDismissRequest = { showRisk = false; activity.acknowledgeStoreRiskForUi() },
            title = { Text("安装可信插件") },
            text = { Text("新版插件只能使用你允许的功能；旧版插件和完全信任的系统插件仍需确认来源可靠。") },
            confirmButton = {
                TextButton(onClick = { showRisk = false; activity.acknowledgeStoreRiskForUi() }) { Text("知道了") }
            },
        )
    }
    LazyColumn(
        state = listState,
        modifier = modifier.semantics { stateDescription = "plugin-repository-$refreshVersion" },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (appUpdate != null) {
            item {
                SuiteCard {
                    val appUpdateBusy = activity.isUpdateOperationRunningForUi("__app__")
                    Text("应用更新 ${appUpdate.versionName}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "部分新插件需要先更新应用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = activity::installAppUpdateForUi,
                        enabled = !appUpdateBusy,
                    ) {
                        Text("更新应用")
                    }
                    if (appUpdateBusy) {
                        RepositoryDownloadProgress(activity, "__app__")
                    }
                }
            }
        }
        item {
            // 副标题同时给出总数和可更新数，「全部更新」按钮出现的原因就在旁边写着。
            val updatableCount = activity.availablePluginUpdatesForUi().size
            SectionHeader(
                "已安装",
                if (updatableCount > 0) "${installed.size} 个 · $updatableCount 项可更新" else "${installed.size} 个",
            ) {
                IconButton(
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar("正在刷新…") }
                        activity.refreshUpdatesForUi()
                    },
                    enabled = !activity.isUpdateOperationRunningForUi("__check__"),
                ) { Icon(Icons.Rounded.Refresh, "刷新仓库") }
                if (activity.availableUpdateCountForUi() > 0) {
                    TextButton(onClick = activity::installAllUpdatesForUi) { Text("全部更新") }
                }
            }
        }
        if (activity.updateCheckStateForUi() == MainActivity.UpdateCheckState.FAILED) {
            item { ErrorState("仓库刷新失败", activity.updateErrorForUi(), activity::refreshUpdatesForUi) }
        }
        if (installed.isEmpty()) item { EmptyState("还没有安装外部插件", "从下方选择插件，或导入本地插件包。") }
        items(installed, key = ImportedPluginDescriptor::id) { descriptor ->
            InstalledRepositoryCard(activity, descriptor, plugins.firstOrNull { it.id == descriptor.id })
        }
        item { SectionHeader("可安装", "${available.size} 个") }
        if (plugins.isEmpty() && activity.updateCheckStateForUi() != MainActivity.UpdateCheckState.CHECKING) {
            item { EmptyState("仓库暂不可用", "请检查网络后刷新；首次发布完成前仓库也可能为空。") }
        } else if (available.isEmpty()) {
            item { EmptyState("没有更多插件", "所有可用插件都已经安装。") }
        }
        items(available, key = UpdateCatalog.PluginRelease::id) { release ->
            RepositoryPluginCard(activity, release)
        }
        item {
            OutlinedButton(onClick = activity::importPlugin, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(SuiteSpacing.sm))
                Text("导入本地插件包")
            }
        }
    }
}

@Composable
private fun InstalledRepositoryCard(
    activity: MainActivity,
    descriptor: ImportedPluginDescriptor,
    latest: UpdateCatalog.PluginRelease?,
) {
    hostRevision(activity)
    var menuExpanded by remember(descriptor.id) { mutableStateOf(false) }
    var versionSheetVisible by remember(descriptor.id) { mutableStateOf(false) }
    val versions = activity.repositoryPluginVersionsForUi(descriptor.id)
    val hasUpdate = latest != null && activity.isRepositoryPluginUpdateAvailableForUi(latest)
    val updateChecked = activity.isPluginUpdateCheckEnabledForUi(descriptor.id)
    val busy = activity.isUpdateOperationRunningForUi(descriptor.id)
    if (versionSheetVisible) {
        VersionPickerSheet(activity, descriptor.title, versions) { versionSheetVisible = false }
    }
    SuiteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
            IconBox(Icons.Rounded.Extension, null, dense = true)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    Text(descriptor.title, style = MaterialTheme.typography.titleMedium)
                    if (activity.isRepositoryVerifiedForUi(descriptor.id)) Icon(Icons.Rounded.Verified, "已验证", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                // 副标题直接说清「要往哪走」，而不是只报一个当前版本号。
                Text(
                    when {
                        hasUpdate -> "${descriptor.version} → ${latest?.versionName}"
                        !updateChecked -> "不检查更新 · ${descriptor.version}"
                        else -> "已是最新 · ${descriptor.version}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "更多操作") }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    // 已安装的插件不在「可安装」列表里出现，切版本、降级的入口只能挂在这张卡上。
                    if (versions.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("选择版本…") },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            onClick = { menuExpanded = false; versionSheetVisible = true },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (updateChecked) "不检查此插件更新" else "检查此插件更新") },
                        leadingIcon = { Icon(if (updateChecked) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications, null) },
                        onClick = {
                            menuExpanded = false
                            activity.setPluginUpdateCheckEnabledForUi(descriptor.id, !updateChecked)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导出插件包") },
                        leadingIcon = { Icon(Icons.Rounded.FileUpload, null) },
                        onClick = { menuExpanded = false; activity.exportPlugin(descriptor.id) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menuExpanded = false; activity.requestDeletePluginForUi(descriptor.id) },
                    )
                }
            }
        }
        if (busy) {
            RepositoryDownloadProgress(activity, descriptor.id)
        }
        if (hasUpdate) {
            Button(
                onClick = { activity.installRepositoryPluginVersionForUi(requireNotNull(latest)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("更新到 v${latest.versionName}") }
        }
    }
}

/**
 * 版本列表。每一行自己说明这次跳转是升级、降级还是被拦下的，点了直接装。
 *
 * 不做「先选中再确认」两步：选版本这件事本身就只有一个后续动作。
 */
@Composable
private fun VersionPickerSheet(
    activity: MainActivity,
    title: String,
    versions: List<UpdateCatalog.PluginRelease>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, modifier = Modifier.padding(horizontal = SuiteSpacing.xl), style = MaterialTheme.typography.titleLarge)
        Text(
            "可切换到其他已发布版本。降级需要目标版本能读取当前数据格式。",
            modifier = Modifier.padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(versions, key = UpdateCatalog.PluginRelease::sha256) { version ->
                val installed = activity.isRepositoryPluginVersionInstalledForUi(version)
                val selectable = activity.isRepositoryPluginVersionSelectableForUi(version)
                val label = version.versionName +
                        if (version.channel == UpdateCatalog.CHANNEL_DEBUG) " · ${version.commitSha.take(7)}" else ""
                SuiteSettingsRow(
                    label,
                    supportingText = activity.repositoryPluginTransitionLabelForUi(version),
                    leading = { if (installed) Icon(Icons.Rounded.Check, "当前安装") else Spacer(Modifier.size(24.dp)) },
                    trailingText = if (selectable) activity.repositoryPluginActionLabelForUi(version) else null,
                    emphasized = selectable,
                    onClick = if (selectable) {
                        {
                            activity.installRepositoryPluginVersionForUi(version)
                            onDismiss()
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(Modifier.height(SuiteSpacing.xxl))
    }
}

@Composable
private fun RepositoryPluginCard(activity: MainActivity, release: UpdateCatalog.PluginRelease) {
    hostRevision(activity)
    var menuExpanded by remember(release.id) { mutableStateOf(false) }
    var versionSheetVisible by remember(release.id) { mutableStateOf(false) }
    val versions = activity.repositoryPluginVersionsForUi(release.id)
    val compatible = activity.isRepositoryPluginCompatibleForUi(release)
    val busy = activity.isUpdateOperationRunningForUi(release.id)
    val selectable = activity.isRepositoryPluginVersionSelectableForUi(release)
    val actionText = activity.repositoryPluginActionLabelForUi(release)
    if (versionSheetVisible) {
        VersionPickerSheet(activity, release.title, versions) { versionSheetVisible = false }
    }
    SuiteCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
        ) {
            IconBox(Icons.Rounded.Extension, null, dense = true)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    Text(release.title, style = MaterialTheme.typography.titleMedium)
                    Icon(Icons.Rounded.Verified, "已验证发布", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Text(
                    release.versionName
                            + if (release.channel == UpdateCatalog.CHANNEL_DEBUG) " · ${release.commitSha.take(7)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "更多操作") }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (versions.size > 1) {
                    DropdownMenuItem(
                        text = { Text("选择版本…") },
                        leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                        onClick = { menuExpanded = false; versionSheetVisible = true },
                    )
                }
            }
        }
        Text(
            activity.repositoryPluginTransitionLabelForUi(release),
            style = MaterialTheme.typography.bodySmall,
            color = if (selectable || activity.isRepositoryPluginVersionInstalledForUi(release)) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            release.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (release.dependencies.isNotEmpty()) {
            Text(
                "依赖：${release.dependencies.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!compatible) {
            Notice("需要应用 ${activity.requiredAppVersionLabelForUi(release.minHostVersionCode)} 或更高版本", warning = true)
            Button(onClick = activity::installAppUpdateForUi, modifier = Modifier.fillMaxWidth(), enabled = activity.appUpdateForUi() != null) {
                Text("更新应用")
            }
        } else {
            if (busy) {
                RepositoryDownloadProgress(activity, release.id)
            }
            Button(
                onClick = { activity.installRepositoryPluginVersionForUi(release) },
                enabled = !busy && selectable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(actionText) }
        }
    }
}

@Composable
private fun RepositoryDownloadProgress(activity: MainActivity, pluginId: String) {
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
    val listState = rememberPageListState(activity, "settings")
    var themeMenu by remember { mutableStateOf(false) }
    var colorMenu by remember { mutableStateOf(false) }
    var channelMenu by remember { mutableStateOf(false) }
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
                    title = "配色",
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
            SuiteSettingsGroup("更新") {
                SuiteSettingsSwitchRow(
                    "自动检查更新",
                    checked = activity.autoCheckUpdatesForUi(),
                    onCheckedChange = activity::setAutoCheckUpdatesForUi,
                )
                SuiteSettingsRow(
                    "立即检查更新",
                    onClick = {
                        scope.launch { snackbarHostState.showSnackbar("正在检查更新…") }
                        activity.checkUpdatesManuallyForUi()
                    },
                    trailingText = if (activity.isUpdateOperationRunningForUi("__check__")) "正在检查…" else null,
                    emphasized = true,
                )
                SuiteSettingsRow("上次检查", trailingText = formatLastChecked(activity.lastUpdateCheckForUi()))
            }
        }
        item {
            SuiteSettingsGroup("备份与迁移") {
                SuiteSettingsRow(
                    "导出数据包",
                    onClick = activity::prepareMigrationBridgeExportForUi,
                )
                SuiteSettingsRow(
                    "导入数据包",
                    onClick = activity::importMigrationBridgeForUi,
                )
                SuiteSettingsRow(
                    "删除插件数据",
                    onClick = activity::prepareMigrationBridgeDeleteForUi,
                )
            }
        }
        if (activity.isDebugBuildForUi()) {
            item {
                SuiteSettingsGroup("开发者选项") {
                    SettingsDropdownRow(
                        title = "插件仓库渠道",
                        supportingText = if (activity.isDebugPluginRepositoryForUi()) "调试版本可能不稳定" else null,
                        selectedValue = activity.pluginRepositoryChannelForUi(),
                        selectedLabel = activity.pluginRepositoryChannelLabelForUi(),
                        options = listOf(UpdateCatalog.CHANNEL_RELEASE to "正式仓库", UpdateCatalog.CHANNEL_DEBUG to "调试仓库"),
                        expanded = channelMenu,
                        onExpandedChange = { channelMenu = it },
                        onSelected = activity::selectPluginRepositoryChannelForUi,
                    )
                }
            }
        }
        item {
            SuiteSettingsGroup("关于") {
                SuiteSettingsRow(
                    "安卓工具合集",
                    supportingText = activity.appVersionNameForUi(),
                    leading = { IconBox(Icons.Rounded.Build, null, dense = true) },
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
    val appUpdate = activity.appUpdateForUi()
    val pluginUpdates = activity.availablePluginUpdatesForUi()
    AlertDialog(
        onDismissRequest = activity::closeUpdatePromptForUi,
        title = { Text("发现 ${activity.availableUpdateCountForUi()} 项更新") },
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
                Button(onClick = activity::installAllUpdatesForUi) { Text("全部更新") }
            }
        },
    )
}

@Composable
private fun ComposeDialog(activity: MainActivity) {
    hostRevision(activity)
    val dialog = activity.composeDialogForUi() ?: return
    AlertDialog(
        onDismissRequest = activity::dismissComposeDialogForUi,
        title = { Text(dialog.title) },
        text = { Text(dialog.message) },
        dismissButton = {
            if (dialog.dismissLabel.isNotBlank()) {
                TextButton(onClick = activity::dismissComposeDialogForUi) { Text(dialog.dismissLabel) }
            }
        },
        confirmButton = {
            TextButton(onClick = activity::confirmComposeDialogForUi) { Text(dialog.confirmLabel) }
        },
    )
}

@Composable
private fun MigrationBridgeExportDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeExportOptionsForUi()
    if (options.isEmpty()) return
    val grouped = options.groupBy { it.pluginId }
    var expanded by remember(options) {
        mutableStateOf(grouped.keys.firstOrNull()?.let(::setOf) ?: emptySet())
    }
    var selected by remember(options) {
        mutableStateOf(
            closeRestoreSelection(
                options,
                options.filter {
                    it.isHostItem || it.descriptor.category == DatasetCategory.SETTINGS ||
                        (it.descriptor.category == DatasetCategory.DATA &&
                            it.descriptor.estimatedSize <= 32L * 1024L * 1024L)
                }.mapTo(linkedSetOf()) { it.key() },
            ),
        )
    }
    var passwordProtected by remember(options) {
        mutableStateOf<Set<String>>(
            options.filter {
                it.key() in selected && exportDatasetIsSensitive(it)
            }.mapTo(linkedSetOf()) { it.key() },
        )
    }
    var password by remember(options) { mutableStateOf("") }
    val selectedCount = selected.size
    val encryptedCount = selected.count { it in passwordProtected }
    val plainCount = selectedCount - encryptedCount
    val plainSensitive = options.filter {
        it.key() in selected && it.key() !in passwordProtected && exportDatasetIsSensitive(it)
    }
    val containsPlainSensitive = plainSensitive.isNotEmpty()
    val passwordValid = passwordProtected.isEmpty() || password.length >= 8
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = activity::dismissMigrationBridgeExportForUi,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = SuiteSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs),
            ) {
                Text("导出数据包", style = MaterialTheme.typography.titleLarge)
                Text(
                    "每项数据都可以选择不导出、明文或加密。普通数据也可以加密，敏感数据选择明文时会提示后果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                shape = SuiteShapes.Inner,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(SuiteSpacing.lg), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("已选择 $selectedCount 项", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "$plainCount 项明文 · $encryptedCount 项密码保护",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            enabled = selected.isNotEmpty() && passwordProtected.isNotEmpty(),
                            onClick = {
                                passwordProtected = emptySet()
                            },
                        ) { Text("全部明文") }
                        TextButton(
                            enabled = selected.isNotEmpty() && passwordProtected != selected,
                            onClick = {
                                passwordProtected = selected.toSet()
                            },
                        ) { Text("全部加密") }
                    }
                    Text(
                        "展开应用或插件，可单独调整每项数据及其保护方式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(max = 420.dp),
                contentPadding = PaddingValues(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
            ) {
                grouped.forEach { (ownerId, ownerOptions) ->
                    item(key = "owner-$ownerId") {
                        val ownerKeys = ownerOptions.mapTo(linkedSetOf()) { it.key() }
                        val ownerSelected = ownerKeys.intersect(selected)
                        val ownerEncrypted = ownerSelected.count { it in passwordProtected }
                        val ownerPlain = ownerSelected.size - ownerEncrypted
                        val selectionState = when (ownerSelected.size) {
                            0 -> ToggleableState.Off
                            ownerOptions.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        Surface(
                            shape = SuiteShapes.Inner,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        expanded = if (ownerId in expanded) expanded - ownerId else expanded + ownerId
                                    }.padding(start = SuiteSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TriStateCheckbox(
                                        state = selectionState,
                                        onClick = {
                                            val previous = selected
                                            val updated = if (selectionState == ToggleableState.On) {
                                                pruneRestoreSelection(options, selected - ownerKeys)
                                            } else {
                                                closeRestoreSelection(options, selected + ownerKeys)
                                            }
                                            selected = updated
                                            passwordProtected = (
                                                passwordProtected + options.filter {
                                                    it.key() in updated && it.key() !in previous && exportDatasetIsSensitive(it)
                                                }.map { it.key() }
                                                ).intersect(updated)
                                        },
                                    )
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(ownerOptions.first().pluginTitle, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (ownerSelected.isEmpty()) {
                                                "未选择 · 共 ${ownerOptions.size} 项"
                                            } else {
                                                "${ownerSelected.size}/${ownerOptions.size} 已选 · $ownerPlain 明文 · $ownerEncrypted 加密"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = {
                                        expanded = if (ownerId in expanded) expanded - ownerId else expanded + ownerId
                                    }) {
                                        Icon(
                                            if (ownerId in expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            if (ownerId in expanded) "收起" else "展开",
                                        )
                                    }
                                }
                                if (ownerId in expanded) {
                                    ownerOptions.forEach { option ->
                                        val checked = option.key() in selected
                                        val encrypted = option.key() in passwordProtected
                                        val sensitive = exportDatasetIsSensitive(option)
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .padding(
                                                    start = 48.dp,
                                                    end = SuiteSpacing.md,
                                                    top = SuiteSpacing.sm,
                                                    bottom = SuiteSpacing.sm,
                                                ),
                                        ) {
                                            Text(option.descriptor.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                "${bridgeCategoryLabel(option.descriptor.category)} · " +
                                                    bridgeDatasetSize(option.descriptor.estimatedSize) +
                                                    if (sensitive) " · 敏感" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                                                FilterChip(
                                                    selected = !checked,
                                                    onClick = {
                                                        selected = pruneRestoreSelection(options, selected - option.key())
                                                        passwordProtected = passwordProtected.intersect(selected)
                                                    },
                                                    label = { Text("不导出") },
                                                )
                                                FilterChip(
                                                    selected = checked && !encrypted,
                                                    onClick = {
                                                        val previous = selected
                                                        val updated = closeRestoreSelection(options, selected + option.key())
                                                        selected = updated
                                                        val addedSensitive = options.filter {
                                                            it.key() in updated && it.key() !in previous && exportDatasetIsSensitive(it)
                                                        }.map { it.key() }
                                                        passwordProtected = ((passwordProtected + addedSensitive) - option.key())
                                                            .intersect(updated)
                                                    },
                                                    label = { Text("明文") },
                                                )
                                                FilterChip(
                                                    selected = checked && encrypted,
                                                    onClick = {
                                                        val updated = closeRestoreSelection(options, selected + option.key())
                                                        selected = updated
                                                        passwordProtected = (passwordProtected + option.key()).intersect(updated)
                                                    },
                                                    label = { Text("加密") },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = SuiteSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
            ) {
                if (passwordProtected.isNotEmpty()) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码保护区密码") },
                        supportingText = { Text("至少 8 位；只用于本次数据包") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = password.length in 1..7,
                    )
                }
                if (containsPlainSensitive) {
                    Notice(
                        "${plainSensitive.size} 项敏感数据将写入未加密的明文区。任何能读取文件的人都可能看到其中的凭据或隐私数据。",
                        warning = true,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = SuiteSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectedCount == 0) "尚未选择数据" else "将导出 $selectedCount 项",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = activity::dismissMigrationBridgeExportForUi) { Text("取消") }
                Button(
                    onClick = {
                        activity.confirmMigrationBridgeExportForUi(
                            selected.toList(),
                            passwordProtected.toList(),
                            password,
                        )
                    },
                    enabled = selected.isNotEmpty() && passwordValid,
                ) { Text("选择保存位置") }
            }
        }
    }
}

@Composable
private fun PluginPermissionRow(
    permission: V2PluginPermissionManager.Permission,
    scope: String,
    onGrantedChange: (Boolean) -> Unit,
) {
    val granted = permission.state == V2PluginPermissionManager.State.GRANTED
    val stateLabel = when {
        permission.state == V2PluginPermissionManager.State.GRANTED -> "已允许"
        permission.state == V2PluginPermissionManager.State.DENIED -> "已拒绝"
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
            checked = granted,
            onCheckedChange = onGrantedChange,
            enabled = true,
        )
    }
}

private fun exportDatasetIsSensitive(option: MigrationBridgeManager.DatasetOption): Boolean =
    option.descriptor.sensitive || option.descriptor.category == DatasetCategory.SECRET

@Composable
private fun MigrationBridgeImportDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeImportOptionsForUi()
    if (options.isEmpty()) return

    val grouped = options.groupBy { it.pluginId }
    var expanded by remember(options) { mutableStateOf(grouped.keys.toSet()) }
    var actions by remember(options) {
        mutableStateOf<Map<String, DatasetRestoreMode?>>(
            options.associate { option ->
                option.key() to if (option.hasExistingData &&
                    DatasetRestoreMode.MERGE !in option.descriptor.restoreModes
                ) null else preferredImportMode(option)
            },
        )
    }
    var password by remember(options) { mutableStateOf("") }
    val selected = actions.filterValues { it != null }.keys
    val needsPassword = options.any {
        it.key() in selected && activity.migrationBridgeImportProtectionForUi(it.key()) == "PASSWORD"
    }
    val passwordValid = !needsPassword || password.length >= 8
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun updateAction(option: MigrationBridgeManager.DatasetOption, mode: DatasetRestoreMode?) {
        val currentKeys = actions.filterValues { it != null }.keys
        val nextKeys = if (mode == null) {
            pruneRestoreSelection(options, currentKeys - option.key())
        } else {
            closeRestoreSelection(options, currentKeys + option.key())
        }
        val next = actions.toMutableMap()
        options.forEach { candidate ->
            next[candidate.key()] = when {
                candidate.key() !in nextKeys -> null
                candidate.key() == option.key() && mode != null -> mode
                next[candidate.key()] != null -> next[candidate.key()]
                else -> preferredImportMode(candidate)
            }
        }
        actions = next
    }

    ModalBottomSheet(
        onDismissRequest = activity::dismissMigrationBridgeImportForUi,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(
                modifier = Modifier.padding(horizontal = SuiteSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs),
            ) {
                Text("导入数据包", style = MaterialTheme.typography.titleLarge)
                Text(
                    "来源：${activity.migrationBridgeImportSourceForUi()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "没有现有数据时可导入或跳过；已有数据时可选择跳过、替换或合并。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(max = 460.dp),
                contentPadding = PaddingValues(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
            ) {
                grouped.forEach { (ownerId, ownerOptions) ->
                    item(key = "import-owner-$ownerId") {
                        Surface(
                            shape = SuiteShapes.Inner,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        expanded = if (ownerId in expanded) expanded - ownerId else expanded + ownerId
                                    }.padding(start = SuiteSpacing.lg),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(ownerOptions.first().pluginTitle, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "${ownerOptions.count { actions[it.key()] != null }}/${ownerOptions.size} 将导入",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = {
                                        val ownerKeys = ownerOptions.map { it.key() }.toSet()
                                        val base = pruneRestoreSelection(options, selected - ownerKeys)
                                        actions = actions.mapValues { (key, value) -> if (key in base) value else null }
                                    }) { Text("全部跳过") }
                                    IconButton(onClick = {
                                        expanded = if (ownerId in expanded) expanded - ownerId else expanded + ownerId
                                    }) {
                                        Icon(if (ownerId in expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                                    }
                                }
                                if (ownerId in expanded) {
                                    ownerOptions.forEach { option ->
                                        val mode = actions[option.key()]
                                        val supportsReplace = DatasetRestoreMode.REPLACE in option.descriptor.restoreModes
                                        val supportsMerge = DatasetRestoreMode.MERGE in option.descriptor.restoreModes
                                        val protection = if (activity.migrationBridgeImportProtectionForUi(option.key()) == "PASSWORD") {
                                            "加密"
                                        } else {
                                            "明文"
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                .padding(
                                                    start = 48.dp,
                                                    end = SuiteSpacing.md,
                                                    top = SuiteSpacing.sm,
                                                    bottom = SuiteSpacing.sm,
                                                ),
                                            verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs),
                                        ) {
                                            Text(option.descriptor.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                (if (option.hasExistingData) "已有数据" else "当前没有数据") +
                                                    " · $protection · ${bridgeCategoryLabel(option.descriptor.category)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                                                FilterChip(
                                                    selected = mode == null,
                                                    onClick = { updateAction(option, null) },
                                                    label = { Text("跳过") },
                                                )
                                                if (!option.hasExistingData) {
                                                    FilterChip(
                                                        selected = mode != null,
                                                        onClick = { updateAction(option, preferredImportMode(option)) },
                                                        label = { Text("导入") },
                                                    )
                                                } else {
                                                    FilterChip(
                                                        selected = mode == DatasetRestoreMode.REPLACE,
                                                        enabled = supportsReplace,
                                                        onClick = { updateAction(option, DatasetRestoreMode.REPLACE) },
                                                        label = { Text("替换") },
                                                    )
                                                    FilterChip(
                                                        selected = mode == DatasetRestoreMode.MERGE,
                                                        enabled = supportsMerge,
                                                        onClick = { updateAction(option, DatasetRestoreMode.MERGE) },
                                                        label = { Text("合并") },
                                                    )
                                                }
                                            }
                                            if (option.hasExistingData && !supportsMerge) {
                                                Text(
                                                    "已有数据 · 此项不能合并，只能跳过或替换。",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = SuiteSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
            ) {
                if (needsPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("数据包密码") },
                        supportingText = { if (password.isNotEmpty() && password.length < 8) Text("至少 8 位") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = password.isNotEmpty() && password.length < 8,
                    )
                }
                Notice("所有所选项目会先完成校验。带“已有数据”的项目将按这里选择的方式处理。")
            }
            HorizontalDivider(Modifier.padding(top = SuiteSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selected.isEmpty()) "尚未选择数据" else "将导入 ${selected.size} 项",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = activity::dismissMigrationBridgeImportForUi) { Text("取消") }
                Button(
                    onClick = {
                        activity.confirmMigrationBridgeImportForUi(
                            actions.filterValues { it == DatasetRestoreMode.REPLACE }.keys.toList(),
                            actions.filterValues { it == DatasetRestoreMode.MERGE }.keys.toList(),
                            password,
                        )
                    },
                    enabled = selected.isNotEmpty() && passwordValid,
                ) { Text("开始导入") }
            }
        }
    }
}

private fun preferredImportMode(option: MigrationBridgeManager.DatasetOption): DatasetRestoreMode = when {
    option.hasExistingData && DatasetRestoreMode.MERGE in option.descriptor.restoreModes -> DatasetRestoreMode.MERGE
    DatasetRestoreMode.REPLACE in option.descriptor.restoreModes -> DatasetRestoreMode.REPLACE
    else -> option.descriptor.restoreModes.first()
}

@Composable
private fun MigrationBridgeDeleteDialog(activity: MainActivity) {
    hostRevision(activity)
    val options = activity.migrationBridgeDeleteOptionsForUi()
    if (options.isEmpty()) return
    val grouped = options.groupBy { it.pluginId }
    var expanded by remember(options) { mutableStateOf(grouped.keys.toSet()) }
    var selected by remember(options) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmed by remember(options) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = activity::dismissMigrationBridgeDeleteForUi,
        title = { Text("删除插件数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                Notice("删除不可撤销。选择被其他数据项目依赖的内容时，相关数据会自动一并选中。", warning = true)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    grouped.forEach { (ownerId, ownerOptions) ->
                        item(key = "delete-owner-$ownerId") {
                            val allSelected = ownerOptions.all { it.key() in selected }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = allSelected,
                                    onCheckedChange = {
                                        selected = if (allSelected) {
                                            pruneDeleteSelection(options, selected - ownerOptions.map { it.key() }.toSet())
                                        } else {
                                            closeDeleteSelection(options, selected + ownerOptions.map { it.key() })
                                        }
                                        confirmed = false
                                    },
                                )
                                Text(ownerOptions.first().pluginTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text("${ownerOptions.count { it.key() in selected }}/${ownerOptions.size}", style = MaterialTheme.typography.labelSmall)
                                IconButton(onClick = {
                                    expanded = if (ownerId in expanded) expanded - ownerId else expanded + ownerId
                                }) { Icon(if (ownerId in expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                            }
                        }
                        if (ownerId in expanded) {
                            items(ownerOptions, key = { it.key() }) { option ->
                                val checked = option.key() in selected
                                Row(
                                    Modifier.fillMaxWidth().padding(start = SuiteSpacing.lg).clip(SuiteShapes.Inner)
                                        .clickable {
                                            selected = if (checked) {
                                                pruneDeleteSelection(options, selected - option.key())
                                            } else {
                                                closeDeleteSelection(options, selected + option.key())
                                            }
                                            confirmed = false
                                        }.padding(vertical = SuiteSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) {
                                        Text(option.descriptor.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            bridgeCategoryLabel(option.descriptor.category) +
                                                if (option.descriptor.dependencies.isEmpty()) "" else
                                                    " · 依赖 ${option.descriptor.dependencies.joinToString()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("我确认永久删除所选数据", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = { TextButton(onClick = activity::dismissMigrationBridgeDeleteForUi) { Text("取消") } },
        confirmButton = {
            Button(
                onClick = { activity.confirmMigrationBridgeDeleteForUi(selected.toList()) },
                enabled = selected.isNotEmpty() && confirmed,
            ) { Text("永久删除") }
        },
    )
}

private fun closeRestoreSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { option ->
            if (option.requiresBridgeResolution()) {
                val packageKey = "${MigrationBridgeManager.HOST_OWNER_ID}/" +
                    "${MigrationBridgeManager.HOST_PLUGIN_PACKAGE_PREFIX}${option.pluginId}"
                if (result.add(packageKey)) changed = true
            }
            option.descriptor.dependencies.forEach { dependency ->
                if (result.add("${option.pluginId}/$dependency")) changed = true
            }
        }
    } while (changed)
    return result
}

private fun pruneRestoreSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { option ->
            val requiredPackageKey = "${MigrationBridgeManager.HOST_OWNER_ID}/" +
                "${MigrationBridgeManager.HOST_PLUGIN_PACKAGE_PREFIX}${option.pluginId}"
            val packageMissing = option.requiresBridgeResolution() && requiredPackageKey !in result
            if (packageMissing || option.descriptor.dependencies.any { "${option.pluginId}/$it" !in result }) {
                result.remove(option.key())
                changed = true
            }
        }
    } while (changed)
    return result
}

private fun closeDeleteSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.forEach { option ->
            if (option.descriptor.dependencies.any { "${option.pluginId}/$it" in result }) {
                if (result.add(option.key())) changed = true
            }
        }
    } while (changed)
    return result
}

private fun pruneDeleteSelection(
    options: List<MigrationBridgeManager.DatasetOption>,
    initial: Collection<String>,
): Set<String> {
    val result = initial.toMutableSet()
    var changed: Boolean
    do {
        changed = false
        options.filter { it.key() in result }.forEach { parent ->
            val missingDependent = options.any { dependent ->
                dependent.pluginId == parent.pluginId && dependent.key() !in result &&
                    parent.descriptor.id in dependent.descriptor.dependencies
            }
            if (missingDependent) {
                result.remove(parent.key())
                changed = true
            }
        }
    } while (changed)
    return result
}

private fun bridgeCategoryLabel(category: DatasetCategory): String = when (category) {
    DatasetCategory.SETTINGS -> "设置"
    DatasetCategory.DATA -> "业务数据"
    DatasetCategory.SECRET -> "凭据"
    DatasetCategory.CACHE -> "缓存"
}

private fun bridgeDatasetSize(bytes: Long): String = when {
    bytes <= 0L -> "大小未知"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
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
