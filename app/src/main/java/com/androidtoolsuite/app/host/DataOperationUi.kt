@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.androidtoolsuite.app.host

import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.androidtoolsuite.app.ui.SuiteShapes
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteTheming
import com.androidtoolsuite.app.ui.SuiteTopBar

/** Data selection is a full-height workspace, never a draggable bottom sheet. */
@Composable
internal fun DataOperationDialog(
    sessionKey: Any,
    title: String,
    description: String,
    selectedCount: Int,
    summary: String,
    hasChanges: Boolean,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    selection: LazyListScope.() -> Unit,
    confirmation: LazyListScope.() -> Unit,
) {
    key(sessionKey) {
        var reviewing by remember { mutableStateOf(false) }
        var confirmExit by remember { mutableStateOf(false) }
        val selectionScroll = rememberLazyListState()
        val confirmationScroll = rememberLazyListState()
        val reviewStep = reviewing
        val requestExit = { if (hasChanges) confirmExit = true else onDismiss() }
        val goBack = { if (reviewing) reviewing = false else requestExit() }
        val parentView = LocalView.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val bars = ViewCompat.getRootWindowInsets(parentView)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val screen = IntSize(
            ((parentView.rootView.width.takeIf { it > 0 } ?: parentView.resources.displayMetrics.widthPixels) -
                (bars?.left ?: 0) - (bars?.right ?: 0)).coerceAtLeast(1),
            ((parentView.rootView.height.takeIf { it > 0 } ?: parentView.resources.displayMetrics.heightPixels) -
                (bars?.top ?: 0) - (bars?.bottom ?: 0)).coerceAtLeast(1),
        )
        var viewport by remember(configuration.screenWidthDp, configuration.screenHeightDp) { mutableStateOf(screen) }

        Dialog(
            onDismissRequest = goBack,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val view = LocalView.current
            val dark = SuiteTheming.isDark
            // Size from the activity's stable bounds and keyboard overlap. On some OEMs
            // getWindowVisibleDisplayFrame reports this dialog's already reduced bounds;
            // using it would prevent the dialog from expanding again after the IME closes.
            DisposableEffect(view, screen) {
                val listener = ViewTreeObserver.OnGlobalLayoutListener {
                    // Floating dialogs on newer Android versions receive insets relative
                    // to their already clipped frame. Activity window metrics keep the
                    // keyboard's full display overlap, including when it returns to zero.
                    val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowInsetsCompat.toWindowInsetsCompat(
                            parentView.context.getSystemService(WindowManager::class.java)
                                .currentWindowMetrics.windowInsets,
                        )
                    } else ViewCompat.getRootWindowInsets(view)
                    val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    val keyboardOverlap = (imeBottom - (bars?.bottom ?: 0)).coerceAtLeast(0)
                    viewport = IntSize(screen.width, (screen.height - keyboardOverlap).coerceAtLeast(1))
                }
                view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                view.post { listener.onGlobalLayout() }
                onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            }
            SideEffect {
                (view.parent as? DialogWindowProvider)?.window?.let { window ->
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
            }
            Box(
                Modifier.width(with(density) { viewport.width.toDp() })
                    .height(with(density) { viewport.height.toDp() })
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()
                        .semantics { stateDescription = "data-operation-$title-${if (reviewing) "review" else "selection"}" },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column {
                        SuiteTopBar(title, onBack = goBack) {
                            IconButton(onClick = requestExit) { Icon(Icons.Rounded.Close, "关闭") }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("1 选择内容", color = if (!reviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("2 确认操作", color = if (reviewing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        LazyColumn(
                            state = if (reviewing) confirmationScroll else selectionScroll,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(SuiteSpacing.xl),
                            verticalArrangement = Arrangement.spacedBy(SuiteSpacing.lg),
                        ) {
                            if (reviewing) {
                                confirmation()
                            } else {
                                item("operation-description") {
                                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                selection()
                            }
                        }
                        HorizontalDivider()
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.xl, vertical = SuiteSpacing.md),
                            verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
                        ) {
                            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
                                OutlinedButton(onClick = goBack, modifier = Modifier.weight(1f)) {
                                    Text(if (reviewing) "返回选择" else "取消")
                                }
                                Button(
                                    onClick = {
                                        if (reviewStep) {
                                            if (selectedCount > 0 && confirmEnabled) onConfirm()
                                        } else if (selectedCount > 0) reviewing = true
                                    },
                                    enabled = selectedCount > 0 && (!reviewing || confirmEnabled),
                                    modifier = Modifier.weight(1f),
                                    colors = if (destructive && reviewing) ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ) else ButtonDefaults.buttonColors(),
                                ) { Text(if (reviewing) confirmLabel else "继续") }
                            }
                        }
                    }
                }
            }
            if (confirmExit) {
                AlertDialog(
                    onDismissRequest = { confirmExit = false },
                    title = { Text("放弃当前选择？") },
                    text = { Text("退出后不会执行任何数据操作，当前选择和填写内容不会保存。") },
                    confirmButton = { TextButton(onClick = { confirmExit = false }) { Text("继续选择") } },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("放弃并退出") } },
                )
            }
        }
    }
}

@Composable
internal fun DataOperationGroup(
    title: String,
    summary: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    selectionState: ToggleableState? = null,
    onSelectGroup: (() -> Unit)? = null,
    onSkipGroup: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = SuiteShapes.Card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(SuiteSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionState != null && onSelectGroup != null) TriStateCheckbox(state = selectionState, onClick = onSelectGroup)
                Column(Modifier.weight(1f).padding(horizontal = SuiteSpacing.sm)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onSkipGroup != null) TextButton(onClick = onSkipGroup) { Text("跳过") }
                IconButton(onClick = onExpand) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起$title" else "展开$title")
                }
            }
            if (expanded) content()
        }
    }
}

@Composable
internal fun DataOperationEntry(title: String, detail: String, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SuiteSpacing.lg, vertical = SuiteSpacing.md),
        verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
internal fun DataOperationChoices(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm), content = { content() })
}

@Composable
internal fun DataOperationReviewGroup(title: String, rows: List<Pair<String, String>>) {
    Surface(
        Modifier.fillMaxWidth(), shape = SuiteShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(SuiteSpacing.lg), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (name, detail) ->
                Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
