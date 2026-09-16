package com.androidtoolsuite.app.plugin.runtime

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.runtime.HostTool
import com.androidtoolsuite.app.ui.EmptyState
import com.androidtoolsuite.app.ui.ErrorState
import com.androidtoolsuite.app.ui.LoadingState
import com.androidtoolsuite.app.ui.SectionHeader
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteSemantic
import com.androidtoolsuite.app.ui.SuiteShapes
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteStatusChip
import com.androidtoolsuite.app.ui.composePluginView
import com.androidtoolsuite.runtime.contract.ContractException
import com.androidtoolsuite.runtime.contract.DeclarativeUiDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 插件运行时 Tool whose UI is a bounded component document rendered by the Host. */
class DeclarativeToolPlugin(
    val installed: PluginPackageStore.InstalledPlugin,
    private val actions: HostActions,
) : HostTool {
    private var activeView = WeakReference<ComposeView>(null)
    @Volatile private var activeWebSession: WebSession? = null
    private val hostRevision: MutableIntState = mutableIntStateOf(0)

    override fun id(): String = installed.manifest.plugin.id
    override fun title(): String = installed.manifest.plugin.title
    override fun description(): String = installed.manifest.plugin.description
    override fun version(): String = installed.manifest.plugin.version
    override fun removable(): Boolean = true

    override fun dependencies(): Set<String> = installed.manifest.pluginRequirements
        .filterNot { it.optional }
        .map { it.id }
        .toCollection(linkedSetOf())

    override fun createView(activity: android.app.Activity, host: HostServices): View {
        val view = composePluginView(activity) {
            DeclarativeToolScreen(installed, actions, hostRevision.intValue) { session ->
                activeWebSession = session
            }
        } as ComposeView
        activeView = WeakReference(view)
        return view
    }

    override fun createHomeWidgets(activity: android.app.Activity, host: HostServices): List<HostHomeWidget> =
        RuntimeHomeWidgets.create(installed, actions, hostRevision)

    override fun onHostStateChanged() {
        hostRevision.intValue++
        activeWebSession?.emitHostStateEvents()
    }

    override fun onSelected() = Unit

    override fun onDestroy() {
        activeWebSession?.close()
        activeWebSession = null
        activeView.get()?.disposeComposition()
        activeView.clear()
    }
}

private sealed interface DocumentState {
    data object Loading : DocumentState
    data class Ready(val document: DeclarativeUiDocument) : DocumentState
    data class Failed(val message: String) : DocumentState
}

@Composable
private fun DeclarativeToolScreen(
    installed: PluginPackageStore.InstalledPlugin,
    actions: HostActions,
    hostRevision: Int,
    onWebSessionChanged: (WebSession?) -> Unit,
) {
    var reloadKey by remember(installed.generationDirectory) { mutableIntStateOf(0) }
    var documentState by remember(installed.generationDirectory) { mutableStateOf<DocumentState>(DocumentState.Loading) }
    LaunchedEffect(installed.generationDirectory, reloadKey) {
        documentState = try {
            val document = withContext(Dispatchers.IO) { loadDocument(installed) }
            DocumentState.Ready(document)
        } catch (error: Exception) {
            DocumentState.Failed(safeMessage(error))
        }
    }
    when (val current = documentState) {
        DocumentState.Loading -> Box(Modifier.fillMaxSize()) { LoadingState("正在打开工具…") }
        is DocumentState.Failed -> Box(
            Modifier.fillMaxSize().padding(horizontal = SuiteSpacing.ScreenPadding, vertical = SuiteSpacing.xxl),
        ) {
            ErrorState("工具页面无法打开", current.message, onRetry = {
                documentState = DocumentState.Loading
                reloadKey++
            })
        }
        is DocumentState.Ready -> if (current.document.isWebView) {
            WebToolScreen(
                installed = installed,
                actions = actions,
                entryPath = current.document.webEntry(),
                onSessionChanged = onWebSessionChanged,
            )
        } else {
            DeclarativeDocumentScreen(current.document, installed, actions, hostRevision)
        }
    }
}

private fun loadDocument(installed: PluginPackageStore.InstalledPlugin): DeclarativeUiDocument {
    val entry = requireNotNull(installed.manifest.defaultUiEntry())
    if (entry.type != "declarative") throw ContractException("UI entry 不是声明式文档")
    val file = File(installed.generationDirectory, entry.entry)
    if (!file.isFile || file.length() !in 1..(256L * 1024L)) {
        throw ContractException("声明式 UI 文件不存在或超出大小限制")
    }
    val document = DeclarativeUiDocument.parse(file.readText(Charsets.UTF_8))
    document.validateAgainst(installed.manifest)
    return document
}

@Composable
private fun DeclarativeDocumentScreen(
    document: DeclarativeUiDocument,
    installed: PluginPackageStore.InstalledPlugin,
    actions: HostActions,
    hostRevision: Int,
) {
    val sessionId = remember(installed.generationDirectory) { randomSessionId() }
    var state by remember(document) { mutableStateOf(copyJson(document.initialState)) }
    var requiredError by remember(document) { mutableStateOf<String?>(null) }
    var runningAction by remember(document) { mutableStateOf<String?>(null) }
    var pendingConfirmation by remember(document) { mutableStateOf<DeclarativeUiDocument.Action?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    suspend fun runQuery(query: DeclarativeUiDocument.Query) {
        try {
            val result = invoke(actions, installed, sessionId, query, state, userGesture = false)
            state = setPath(state, query.target, result)
            if (query.required) requiredError = null
        } catch (error: Throwable) {
            if (query.required) requiredError = safeMessage(error)
        }
    }

    fun runAction(action: DeclarativeUiDocument.Action) {
        if (runningAction != null) return
        scope.launch {
            runningAction = action.id
            try {
                val result = invoke(actions, installed, sessionId, action, state, userGesture = true)
                if (action.target.isNotBlank()) state = setPath(state, action.target, result)
                for (queryId in action.refresh) {
                    document.queriesById[queryId]?.let { runQuery(it) }
                }
                if (action.successMessage.isNotBlank()) snackbar.showSnackbar(action.successMessage)
            } catch (error: Throwable) {
                snackbar.showSnackbar(safeMessage(error))
            } finally {
                runningAction = null
            }
        }
    }

    DisposableEffect(sessionId) {
        onDispose { actions.closeRuntimeSession(sessionId) }
    }
    LaunchedEffect(document, hostRevision) {
        document.queries.forEach { runQuery(it) }
    }

    pendingConfirmation?.let { action ->
        val confirm = requireNotNull(action.confirm)
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(confirm.title) },
            text = { Text(confirm.body) },
            dismissButton = { TextButton(onClick = { pendingConfirmation = null }) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    pendingConfirmation = null
                    runAction(action)
                }) { Text(confirm.confirmLabel) }
            },
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { innerPadding ->
        Box(
            Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = SuiteSpacing.ScreenPadding,
                    vertical = SuiteSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(
                    gap(document.body.raw.optString("gap", "large")),
                ),
            ) {
                requiredError?.let { message ->
                    item("required-query-error") {
                        ErrorState(
                            title = "工具状态暂时不可用",
                            body = message,
                            onRetry = { scope.launch { document.queries.filter { it.required }.forEach { runQuery(it) } } },
                        )
                    }
                }
                if (requiredError == null) {
                    document.body.children.forEachIndexed { index, node ->
                        item("node-$index") {
                            DeclarativeNode(
                                node = node,
                                state = state,
                                runningAction = runningAction,
                                onAction = { actionId ->
                                    document.actionsById[actionId]?.let { action ->
                                        if (action.confirm == null) runAction(action) else pendingConfirmation = action
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeclarativeNode(
    node: DeclarativeUiDocument.Node,
    state: JSONObject,
    runningAction: String?,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!matches(node.raw.optJSONObject("when"), state)) return
    when (node.type) {
        "column" -> Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap(node.raw.optString("gap", "medium"))),
        ) { node.children.forEach { DeclarativeNode(it, state, runningAction, onAction) } }
        "row" -> Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap(node.raw.optString("gap", "medium"))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            node.children.forEach { child ->
                val childModifier = if (child.type in setOf("icon", "status")) Modifier else Modifier.weight(1f)
                DeclarativeNode(child, state, runningAction, onAction, childModifier)
            }
        }
        "card" -> SuiteCard(modifier = modifier, containerColor = containerColor(node.raw.optString("tone", "surface"))) {
            node.children.forEach { DeclarativeNode(it, state, runningAction, onAction) }
        }
        "section" -> Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SuiteSpacing.md),
        ) {
            SectionHeader(
                title = textValue(node.raw.opt("title"), state),
                subtitle = textValue(node.raw.opt("subtitle"), state).ifBlank { null },
            )
            node.children.forEach { DeclarativeNode(it, state, runningAction, onAction) }
        }
        "text" -> Text(
            textValue(node.raw.opt("value"), state),
            modifier = modifier,
            style = textStyle(node.raw.optString("style", "body")),
            color = contentColor(node.raw.optString("tone", "default")),
        )
        "status" -> SuiteStatusChip(
            text = textValue(node.raw.opt("value"), state),
            modifier = modifier,
            positive = node.raw.optString("tone", "default") !in setOf("warning", "danger", "muted"),
        )
        "icon" -> Icon(
            imageVector = declarativeIcon(node.raw.optString("name")),
            contentDescription = null,
            modifier = modifier,
            tint = contentColor(node.raw.optString("tone", "default")),
        )
        "metric" -> Column(modifier, verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
            Text(
                textValue(node.raw.opt("label"), state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                textValue(node.raw.opt("value"), state),
                style = MaterialTheme.typography.headlineSmall,
                color = contentColor(node.raw.optString("tone", "default")),
            )
            textValue(node.raw.opt("supporting"), state).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "notice" -> DeclarativeNotice(
            textValue(node.raw.opt("value"), state),
            node.raw.optString("tone", "info"),
            modifier,
        )
        "button" -> {
            val action = node.raw.optString("action")
            val enabled = runningAction == null && matches(node.raw.optJSONObject("enabledWhen"), state)
            val buttonModifier = if (node.raw.optBoolean("fullWidth", false)) modifier.fillMaxWidth() else modifier
            val label = textValue(node.raw.opt("label"), state)
            when (node.raw.optString("style", "primary")) {
                "secondary" -> OutlinedButton({ onAction(action) }, buttonModifier, enabled = enabled) { Text(label) }
                "text" -> TextButton({ onAction(action) }, buttonModifier, enabled = enabled) { Text(label) }
                else -> Button({ onAction(action) }, buttonModifier, enabled = enabled) { Text(label) }
            }
        }
        "state" -> {
            val title = textValue(node.raw.opt("title"), state)
            val body = textValue(node.raw.opt("body"), state)
            val action = node.raw.optString("action", "")
            when (node.raw.optString("variant")) {
                "loading" -> LoadingState(title, modifier)
                "error" -> ErrorState(title, body, action.takeIf { it.isNotBlank() }?.let { { onAction(it) } }, modifier)
                else -> Column(modifier, verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
                    EmptyState(title, body)
                    if (action.isNotBlank()) Button(onClick = { onAction(action) }) {
                        Text(node.raw.optString("actionLabel"))
                    }
                }
            }
        }
        "divider" -> HorizontalDivider(modifier)
        "spacer" -> Spacer(modifier.height(gap(node.raw.optString("size", "medium"))))
    }
}

@Composable
private fun DeclarativeNotice(text: String, tone: String, modifier: Modifier = Modifier) {
    val semantic = SuiteSemantic.current
    val background: Color
    val foreground: Color
    when (tone) {
        "neutral" -> { background = MaterialTheme.colorScheme.secondaryContainer; foreground = MaterialTheme.colorScheme.onSecondaryContainer }
        "success" -> { background = semantic.successContainer; foreground = semantic.onSuccessContainer }
        "warning" -> { background = semantic.warningContainer; foreground = semantic.onWarningContainer }
        "danger" -> { background = semantic.dangerContainer; foreground = semantic.onDangerContainer }
        else -> { background = semantic.infoContainer; foreground = semantic.onInfoContainer }
    }
    Surface(modifier.fillMaxWidth(), color = background, shape = SuiteShapes.Inner) {
        Text(text, Modifier.padding(SuiteSpacing.lg), style = MaterialTheme.typography.bodyMedium, color = foreground)
    }
}

@Composable
private fun containerColor(tone: String): Color {
    val semantic = SuiteSemantic.current
    return when (tone) {
        "primary" -> MaterialTheme.colorScheme.primaryContainer
        "success" -> semantic.successContainer
        "warning" -> semantic.warningContainer
        "danger" -> semantic.dangerContainer
        "info" -> semantic.infoContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
}

@Composable
private fun contentColor(tone: String): Color {
    val semantic = SuiteSemantic.current
    return when (tone) {
        "primary" -> MaterialTheme.colorScheme.primary
        "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
        "success" -> semantic.success
        "warning" -> semantic.warning
        "danger" -> semantic.danger
        "info" -> semantic.info
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
private fun textStyle(style: String): TextStyle = when (style) {
    "headline" -> MaterialTheme.typography.headlineSmall
    "title" -> MaterialTheme.typography.titleLarge
    "supporting" -> MaterialTheme.typography.bodySmall
    "label" -> MaterialTheme.typography.labelLarge
    else -> MaterialTheme.typography.bodyMedium
}

private fun declarativeIcon(name: String): ImageVector = when (name) {
    "check-circle" -> Icons.Rounded.CheckCircle
    else -> Icons.Rounded.CloudOff
}

private fun gap(value: String): Dp = when (value) {
    "small" -> SuiteSpacing.sm
    "large" -> SuiteSpacing.lg
    else -> SuiteSpacing.md
}

private fun textValue(raw: Any?, state: JSONObject): String {
    if (raw == null || raw == JSONObject.NULL) return ""
    if (raw is String) return raw
    if (raw !is JSONObject) return ""
    val value = readPath(state, raw.optString("path"))
    return when (value) {
        null, JSONObject.NULL -> raw.optString("fallback", "")
        is Boolean, is Number, is String -> value.toString()
        else -> raw.optString("fallback", "")
    }
}

private fun matches(condition: JSONObject?, state: JSONObject): Boolean {
    if (condition == null) return true
    val actual = readPath(state, condition.optString("path"))
    val expected = condition.opt("equals")
    if (actual == null || actual == JSONObject.NULL) return expected == null || expected == JSONObject.NULL
    if (actual is Number && expected is Number) return actual.toDouble() == expected.toDouble()
    return actual == expected
}

private fun readPath(state: JSONObject, path: String): Any? {
    var current: Any = state
    for (segment in path.split('.')) {
        if (current !is JSONObject || !current.has(segment)) return null
        current = current.opt(segment) ?: return null
    }
    return current
}

private fun setPath(original: JSONObject, path: String, value: Any): JSONObject {
    val result = copyJson(original)
    val segments = path.split('.')
    var current = result
    for (segment in segments.dropLast(1)) {
        val next = current.optJSONObject(segment) ?: JSONObject().also { current.put(segment, it) }
        current = next
    }
    current.put(segments.last(), JSONObject.wrap(value))
    return result
}

private suspend fun invoke(
    actions: HostActions,
    installed: PluginPackageStore.InstalledPlugin,
    sessionId: String,
    invocation: DeclarativeUiDocument.Invocation,
    state: JSONObject,
    userGesture: Boolean,
): JSONObject = suspendCancellableCoroutine { continuation ->
    val future = actions.capabilityRouter().invoke(
        installed.manifest,
        installed.manifest.plugin.id,
        sessionId,
        invocation.method,
        resolvePayload(invocation.payload, state),
        userGesture,
        invocation.deadlineMs,
    )
    continuation.invokeOnCancellation { future.cancel(true) }
    future.whenComplete { value, rawError ->
        if (!continuation.isActive) return@whenComplete
        if (rawError == null) continuation.resume(value ?: JSONObject())
        else continuation.resumeWithException(unwrap(rawError))
    }
}

private fun resolvePayload(value: JSONObject, state: JSONObject): JSONObject =
    resolveTemplate(value, state) as JSONObject

private fun resolveTemplate(value: Any?, state: JSONObject): Any {
    if (value == null || value == JSONObject.NULL) return JSONObject.NULL
    if (value is JSONObject) {
        if (value.length() == 1 && value.has("$state")) {
            return readPath(state, value.optString("$state")) ?: JSONObject.NULL
        }
        return JSONObject().also { output ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                output.put(key, resolveTemplate(value.opt(key), state))
            }
        }
    }
    if (value is org.json.JSONArray) {
        return org.json.JSONArray().also { output ->
            for (index in 0 until value.length()) output.put(resolveTemplate(value.opt(index), state))
        }
    }
    return value
}

private fun copyJson(value: JSONObject): JSONObject = try {
    JSONObject(value.toString())
} catch (error: JSONException) {
    throw IllegalStateException("Validated JSON could not be copied", error)
}

private fun unwrap(error: Throwable): Throwable {
    var current = error
    while (current is CompletionException && current.cause != null) current = current.cause!!
    return current
}

private fun safeMessage(error: Throwable): String {
    val current = unwrap(error)
    return current.message?.takeIf { it.isNotBlank() } ?: current.javaClass.simpleName
}

private fun randomSessionId(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
}
