package com.androidtoolsuite.app.plugin.runtime

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.androidtoolsuite.app.BuildConfig
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.runtime.HostTool
import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure
import com.androidtoolsuite.app.ui.ErrorState
import com.androidtoolsuite.app.ui.LoadingState
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteWebTheme
import com.androidtoolsuite.app.ui.composePluginView
import com.androidtoolsuite.runtime.contract.ContractException
import com.androidtoolsuite.runtime.contract.ContractLimits
import com.androidtoolsuite.runtime.contract.GeneratedContract
import com.androidtoolsuite.runtime.contract.OriginKey
import com.androidtoolsuite.runtime.contract.PackagePathPolicy
import com.androidtoolsuite.runtime.contract.ProtocolVersion
import com.androidtoolsuite.runtime.contract.RpcEnvelope
import com.androidtoolsuite.runtime.contract.RpcErrorCode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

class WebToolPlugin(
    val installed: PluginPackageStore.InstalledPlugin,
    private val actions: HostActions,
) : HostTool {
    private var activeView = WeakReference<androidx.compose.ui.platform.ComposeView>(null)
    @Volatile private var activeSession: WebSession? = null
    private var visible = true
    override fun onVisibilityChanged(visible: Boolean) {
        this.visible = visible
        activeSession?.setVisible(visible)
    }
    private val widgetRevision = mutableIntStateOf(0)

    override fun id(): String = installed.manifest.plugin.id
    override fun title(): String = installed.manifest.plugin.title
    override fun description(): String = installed.manifest.plugin.description
    override fun version(): String = installed.manifest.plugin.version
    override fun removable(): Boolean = true

    override fun dependencies(): Set<String> = installed.manifest.pluginRequirements
        .filterNot { it.optional }
        .map { requirement ->
            val version = requirement.version.trim()
            when {
                version.isEmpty() || version == "*" -> requirement.id
                version.startsWith("^") || version.startsWith("~") || version.contains(' ') || version.contains(',') -> requirement.id
                version.startsWith(">") || version.startsWith("<") || version.startsWith("=") -> requirement.id + version
                else -> requirement.id + "=" + version
            }
        }
        .toCollection(linkedSetOf())

    override fun createView(activity: android.app.Activity, host: HostServices): View {
        val view = composePluginView(activity) {
            WebToolScreen(
                installed = installed,
                actions = actions,
                entryPath = requireNotNull(installed.manifest.defaultUiEntry()).entry,
            ) { session -> activeSession = session; session?.setVisible(visible) }
        } as androidx.compose.ui.platform.ComposeView
        activeView = WeakReference(view)
        return view
    }

    private val homeWidgets by lazy { RuntimeHomeWidgets.create(installed, actions, widgetRevision) }

    override fun createHomeWidgets(activity: android.app.Activity, host: HostServices): List<HostHomeWidget> = homeWidgets

    override fun onSelected() = Unit
    override fun onHostStateChanged() {
        activeSession?.emitHostStateEvents()
        widgetRevision.intValue++
    }

    override fun onDestroy() {
        activeSession?.close()
        activeSession = null
        activeView.get()?.disposeComposition()
        activeView.clear()
    }
}

@Composable
internal fun WebToolScreen(
    installed: PluginPackageStore.InstalledPlugin,
    actions: HostActions,
    entryPath: String,
    onSessionChanged: (WebSession?) -> Unit,
) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember(reloadKey) { mutableStateOf<WebUiState>(WebUiState.Loading) }
    var showLoading by remember(reloadKey) { mutableStateOf(false) }
    LaunchedEffect(reloadKey) { kotlinx.coroutines.delay(250); showLoading = true }
    var backendRequested by remember(reloadKey) { mutableStateOf(false) }
    key(reloadKey) {
        val session = remember(installed.generationDirectory, reloadKey) {
            WebSession(
                installed = installed,
                actions = actions,
                entryPath = entryPath,
                onReady = { state = WebUiState.Ready },
                onError = { message -> state = WebUiState.Failed(message) },
            )
        }
        DisposableEffect(session) {
            onSessionChanged(session)
            onDispose {
                onSessionChanged(null)
                session.close()
            }
        }
        LaunchedEffect(session) {
            // Commit the shared loading shell before WebView's platform initialization runs on main.
            withFrameNanos { }
            backendRequested = true
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (backendRequested) {
                AndroidView(
                    factory = { session.createWebView() },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { session.close() },
                )
            }
            when (val current = state) {
                WebUiState.Loading -> Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                ) { if (showLoading) LoadingState("正在打开工具…") }
                WebUiState.Ready -> Unit
                is WebUiState.Failed -> Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = SuiteSpacing.ScreenPadding, vertical = SuiteSpacing.xxl),
                ) {
                    ErrorState(
                        title = "工具页面无法打开",
                        body = current.message,
                        onRetry = { reloadKey++ },
                    )
                }
            }
        }
    }
}

private sealed interface WebUiState {
    data object Loading : WebUiState
    data object Ready : WebUiState
    data class Failed(val message: String) : WebUiState
}

@SuppressLint("RequiresFeature")
internal class WebSession(
    private val installed: PluginPackageStore.InstalledPlugin,
    private val actions: HostActions,
    private val entryPath: String,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val sessionId = randomSessionId()
    private val createdAt = SystemClock.elapsedRealtime()
    private val packageOrigin = OriginKey.virtualOrigin(installed.manifest.plugin.id)
    private val developmentUri = if (BuildConfig.DEBUG) developmentUri() else null
    private val origin = packageOrigin
    private val originUri = Uri.parse(origin)
    private val webRoot = File(installed.generationDirectory, "web")
    private var webView: WebView? = null
    private var rendererGone = false
    private var handshakeComplete = false
    private var visible = true
    private var lastUserGestureAt = 0L
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private var replyProxy: JavaScriptReplyProxy? = null
    private var eventSequence = 0L
    private val bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val runtimeEventRegistration = actions.capabilityRouter().subscribeEvents(
        installed.manifest.plugin.id,
        installed.manifest.capabilityRequirements.map { it.id }.toSet(),
    ) { event, payload ->
        val capability = GeneratedContract.capabilityForEvent(event)
        if (capability != null && (capability == GeneratedContract.Capabilities.APP || declaresCapability(capability))
            && handshakeComplete
        ) {
            emitEvent(event, payload)
        }
    }

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(requireNotNull(Uri.parse(packageOrigin).host))
        .addPathHandler("/web/", WebViewAssetLoader.PathHandler(::loadWebResource))
        .addPathHandler("/__ats__/", WebViewAssetLoader.PathHandler(::loadHostResource))
        .build()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun createWebView(): WebView {
        if (!bridgeAvailable && installed.manifest.capabilityRequirements.any { !it.optional }) {
            onError("当前 Android System WebView 不支持 ATS 消息通道，无法使用此工具声明的能力。")
        }
        return WebView(actions.activity()).also { view ->
            webView = view
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            view.setBackgroundColor(Color.TRANSPARENT)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastUserGestureAt = SystemClock.elapsedRealtime()
                }
                false
            }
            configureSettings(view.settings)
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.webViewClient = runtimeClient()
            view.setDownloadListener { _, _, _, _, _ ->
                actions.showMessage("此工具不能直接下载文件，请使用 ATS 文件能力。")
            }
            if (bridgeAvailable) {
                WebViewCompat.addWebMessageListener(
                    view,
                    "atsTransport",
                    setOf(origin),
                    ::onMessage,
                )
            }
            val encodedEntry = entryPath.removePrefix("web/").split('/').joinToString("/") {
                Uri.encode(it)
            }
            val launchUrl = Uri.parse("$origin/web/$encodedEntry").buildUpon().apply {
                if (developmentUri != null) {
                    appendQueryParameter("ats-host", "android")
                    appendQueryParameter("ats-plugin-id", installed.manifest.plugin.id)
                    appendQueryParameter("ats-session-id", sessionId)
                }
            }.build().toString()
            if (BuildConfig.DEBUG) Log.d("AtsRuntimeWeb", "Loading $launchUrl")
            view.loadUrl(launchUrl)
        }
    }

    fun close() {
        try {
            runtimeEventRegistration.close()
        } catch (_: Exception) {
        }
        pending.values.forEach { it.cancel(true) }
        pending.clear()
        actions.closeRuntimeSession(sessionId)
        val view = webView ?: return
        webView = null
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        webView?.let { if (visible) it.onResume() else it.onPause() }
        if (handshakeComplete) emitEvent(GeneratedContract.Events.APP_VISIBILITYCHANGED, JSONObject().put("visible", visible))
    }

    fun emitHostStateEvents() {
        if (!handshakeComplete) return
        emitEvent(GeneratedContract.Events.APP_THEMECHANGED, themePayload())
        emitEvent(GeneratedContract.Events.APP_CONTAINERCHANGED, containerPayload())
        emitEvent(
            GeneratedContract.Events.APP_VISIBILITYCHANGED,
            JSONObject().put("visible", visible && webView?.isShown == true),
        )
    }

    private fun configureSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        run {
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
        }
        settings.blockNetworkLoads = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.userAgentString = settings.userAgentString + " ATS-Runtime/2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }
    }

    private fun runtimeClient() = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (isAllowedOrigin(Uri.parse(url))) {
                pending.values.forEach { it.cancel(true) }
                pending.clear()
                handshakeComplete = false
                replyProxy = null
                eventSequence = 0L
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!isAllowedOrigin(request.url) || hasUnsafeEncodedPath(request.url)) {
                return deniedResponse()
            }
            if (developmentUri != null) {
                if (request.method != "GET") return deniedResponse()
                return loadDevelopmentResource(request.url)
            }
            return assetLoader.shouldInterceptRequest(request.url) ?: notFoundResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isForMainFrame && !isAllowedOrigin(request.url)) {
                actions.showMessage("已阻止工具打开未声明的外部页面。")
            }
            return !isAllowedOrigin(request.url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (!rendererGone && isAllowedOrigin(Uri.parse(url))) {
                if (BuildConfig.DEBUG) Log.d("AtsRuntimeWeb", "Page ready plugin=${installed.manifest.plugin.id} elapsedMs=${SystemClock.elapsedRealtime() - createdAt}")
                onReady()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (request.isForMainFrame) {
                if (BuildConfig.DEBUG) {
                    Log.e("AtsRuntimeWeb", "Main-frame load failed ${request.url}: ${error.description}")
                }
                onError(if (developmentUri == null) {
                    "本地页面加载失败，请重新加载；若问题持续存在，请重新安装此工具。"
                } else {
                    "无法连接 插件运行时 dev server，请检查 ats dev 与 adb reverse。"
                })
            }
        }

        @android.annotation.TargetApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            rendererGone = true
            onError(if (detail.didCrash()) "工具页面进程已崩溃。" else "工具页面因系统资源不足被关闭。")
            view.post { close() }
            return true
        }
    }

    private fun loadWebResource(relativePath: String): WebResourceResponse {
        return try {
            val packagePath = PackagePathPolicy.validateFilePath("web/$relativePath")
            val file = File(installed.generationDirectory, packagePath)
            val rootPath = webRoot.canonicalPath + File.separator
            if (!file.canonicalPath.startsWith(rootPath) || !file.isFile) return notFoundResponse()
            val mime = mimeType(file.name)
            val headers = linkedMapOf(
                "Content-Security-Policy" to CSP,
                "X-Content-Type-Options" to "nosniff",
                "Cache-Control" to "no-store",
            )
            val input = if (mime == "text/html") {
                ByteArrayInputStream(
                    injectBootstrap(file.readText(StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8),
                )
            } else {
                FileInputStream(file)
            }
            WebResourceResponse(mime, encodingFor(mime), 200, "OK", headers, input)
        } catch (_: IOException) {
            notFoundResponse()
        } catch (_: ContractException) {
            deniedResponse()
        }
    }

    private fun loadHostResource(relativePath: String): WebResourceResponse {
        if (relativePath != "theme.css") return notFoundResponse()
        val css = SuiteWebTheme.css(actions.activity()).toByteArray(StandardCharsets.UTF_8)
        return WebResourceResponse(
            "text/css",
            "UTF-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
            ByteArrayInputStream(css),
        )
    }

    private fun loadDevelopmentResource(uri: Uri): WebResourceResponse {
        var connection: HttpURLConnection? = null
        return try {
            val upstreamBase = requireNotNull(developmentUri)
            val upstream = upstreamBase.buildUpon()
                .encodedPath(uri.encodedPath)
                .encodedQuery(uri.encodedQuery)
                .build()
            connection = URL(upstream.toString()).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 0
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            if (status in 300..399) {
                connection.disconnect()
                return deniedResponse()
            }
            val type = connection.contentType.orEmpty().substringBefore(';').ifBlank { mimeType(uri.lastPathSegment.orEmpty()) }
            val encoding = connection.contentEncoding ?: encodingFor(type)
            val source = if (status >= 400) connection.errorStream else connection.inputStream
            val stream = object : FilterInputStream(source ?: ByteArrayInputStream(ByteArray(0))) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
            val headers = linkedMapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values != null && name.equals("Content-Security-Policy", true)) {
                    headers[name] = values.joinToString(",")
                }
            }
            WebResourceResponse(type, encoding, status, connection.responseMessage ?: "OK", headers, stream)
        } catch (error: IOException) {
            connection?.disconnect()
            if (BuildConfig.DEBUG) Log.e("AtsRuntimeWeb", "Dev proxy failed $uri", error)
            textResponse(502, "Bad Gateway", "插件运行时 dev server unavailable")
        }
    }

    private fun injectBootstrap(html: String): String {
        val metadata = "<meta name=\"ats-plugin-id\" content=\"${installed.manifest.plugin.id}\">" +
            "<meta name=\"ats-session-id\" content=\"$sessionId\">" +
            "<meta name=\"ats-protocol\" content=\"${ProtocolVersion.CURRENT}\">"
        val closingHead = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
        return if (closingHead.containsMatchIn(html)) {
            closingHead.replaceFirst(html, metadata + "</head>")
        } else {
            metadata + html
        }
    }

    private fun developmentUri(): Uri? {
        val raw = actions.activity().getSharedPreferences("runtime_v2_dev_servers", android.content.Context.MODE_PRIVATE)
            .getString(installed.manifest.plugin.id, "")
            .orEmpty()
        if (raw.isBlank()) return null
        val uri = Uri.parse(raw)
        val host = uri.host?.lowercase(Locale.ROOT)
        return if (uri.scheme == "http" && (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2")
            && uri.port in 1..65535 && uri.userInfo == null && uri.fragment == null
        ) uri else null
    }

    private fun onMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val raw = message.data ?: return
        if (!isMainFrame || sourceOrigin.toString() != origin
            || raw.toByteArray(StandardCharsets.UTF_8).size > ContractLimits.MAX_RPC_BYTES
        ) {
            if (BuildConfig.DEBUG) Log.w("AtsRuntimeWeb", "Rejected bridge message from $sourceOrigin main=$isMainFrame")
            return
        }
        try {
            val envelope = RpcEnvelope.parse(raw)
            if (BuildConfig.DEBUG) Log.d("AtsRuntimeWeb", "RPC ${envelope.kind} ${envelope.requestId}")
            envelope.requireIdentity(installed.manifest.plugin.id, sessionId)
            if (envelope.protocol.major != ProtocolVersion.CURRENT.major) {
                replyProxy.postMessage(failure(envelope.requestId, RpcErrorCode.PROTOCOL_MISMATCH, "RPC 主版本不兼容"))
                return
            }
            when (envelope.kind) {
                RpcEnvelope.Kind.HELLO -> {
                    if (handshakeComplete || envelope.requestId != "0") {
                        replyProxy.postMessage(failure(envelope.requestId, RpcErrorCode.INVALID_REQUEST, "重复或无效握手"))
                    } else {
                        handshakeComplete = true
                        this.replyProxy = replyProxy
                        replyProxy.postMessage(ready())
                        view.post { emitHostStateEvents() }
                    }
                }
                RpcEnvelope.Kind.REQUEST -> {
                    if (!handshakeComplete) {
                        replyProxy.postMessage(failure(envelope.requestId, RpcErrorCode.PROTOCOL_MISMATCH, "请先完成 RPC 握手"))
                    } else {
                        dispatch(view, envelope, replyProxy)
                    }
                }
                RpcEnvelope.Kind.CANCEL -> pending.remove(envelope.requestId)?.cancel(true)
                else -> replyProxy.postMessage(
                    failure(envelope.requestId, RpcErrorCode.INVALID_REQUEST, "客户端消息类型无效"),
                )
            }
        } catch (error: ContractException) {
            if (BuildConfig.DEBUG) Log.e("AtsRuntimeWeb", "Rejected RPC envelope", error)
            replyProxy.postMessage(failure("invalid", RpcErrorCode.INVALID_REQUEST, error.message ?: "RPC 消息无效"))
        }
    }

    private fun dispatch(view: WebView, envelope: RpcEnvelope, replyProxy: JavaScriptReplyProxy) {
        if (pending.size >= ContractLimits.MAX_PENDING_REQUESTS || pending.containsKey(envelope.requestId)) {
            replyProxy.postMessage(failure(envelope.requestId, RpcErrorCode.RESOURCE_LIMIT, "并发请求过多或 requestId 重复"))
            return
        }
        val recentGesture = SystemClock.elapsedRealtime() - lastUserGestureAt <= USER_GESTURE_WINDOW_MS
        val future = actions.capabilityRouter().invoke(
            installed.manifest,
            installed.manifest.plugin.id,
            sessionId,
            envelope.method,
            envelope.payload(),
            recentGesture,
            envelope.deadlineMs,
        )
        pending[envelope.requestId] = future
        future.whenComplete { result, rawFailure ->
            pending.remove(envelope.requestId)
            view.post {
                if (webView !== view || rendererGone) return@post
                val response = if (rawFailure == null) {
                    try {
                        RpcEnvelope.success(
                            ProtocolVersion.CURRENT,
                            installed.manifest.plugin.id,
                            sessionId,
                            envelope.requestId,
                            result,
                        ).toString()
                    } catch (_: JSONException) {
                        failure(envelope.requestId, RpcErrorCode.INTERNAL, "Host 无法编码响应")
                    }
                } else {
                    val cause = unwrap(rawFailure)
                    val capabilityFailure = cause as? CapabilityFailure
                    val code = capabilityFailure?.code?.let {
                        try { RpcErrorCode.valueOf(it) } catch (_: IllegalArgumentException) { RpcErrorCode.INTERNAL }
                    } ?: if (future.isCancelled) RpcErrorCode.CANCELLED else RpcErrorCode.INTERNAL
                    failure(envelope.requestId, code, capabilityFailure?.message ?: "功能调用失败")
                }
                if (response.toByteArray(StandardCharsets.UTF_8).size <= ContractLimits.MAX_RPC_BYTES) {
                    replyProxy.postMessage(response)
                } else {
                    replyProxy.postMessage(failure(envelope.requestId, RpcErrorCode.RESOURCE_LIMIT, "响应超出消息大小限制"))
                }
            }
        }
    }

    private fun ready(): String = JSONObject()
        .put("protocol", ProtocolVersion.CURRENT.toString())
        .put("kind", "ready")
        .put("pluginId", installed.manifest.plugin.id)
        .put("sessionId", sessionId)
        .put("requestId", "0")
        .put("payload", sessionPayload())
        .toString()

    private fun sessionPayload(): JSONObject = JSONObject()
        .put("platform", JSONObject().put("id", "android").put("api", Build.VERSION.SDK_INT))
        .put("protocol", ProtocolVersion.CURRENT.toString())
        .put("capabilities", JSONArray(actions.capabilityRouter().availableCapabilities(installed.manifest)))
        .put("permissions", actions.capabilityRouter().permissionSnapshot(installed.manifest))
        .put("theme", themePayload())
        .put("container", containerPayload())

    private fun themePayload(): JSONObject {
        val nightMask = actions.activity().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return JSONObject()
            .put("dark", nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            .put("css", SuiteWebTheme.css(actions.activity()))
    }

    private fun containerPayload(): JSONObject {
        val density = actions.activity().resources.displayMetrics.density.coerceAtLeast(1f)
        val view = webView
        return JSONObject()
            .put("widthDp", ((view?.width ?: 0) / density).toInt())
            .put("heightDp", ((view?.height ?: 0) / density).toInt())
    }

    private fun emitEvent(event: String, payload: JSONObject) {
        val view = webView ?: return
        val proxy = replyProxy ?: return
        val message = JSONObject()
            .put("protocol", ProtocolVersion.CURRENT.toString())
            .put("kind", "event")
            .put("pluginId", installed.manifest.plugin.id)
            .put("sessionId", sessionId)
            .put("requestId", "event-${eventSequence + 1}")
            .put("event", event)
            .put("sequence", ++eventSequence)
            .put("payload", payload)
            .toString()
        if (message.toByteArray(StandardCharsets.UTF_8).size <= ContractLimits.MAX_RPC_BYTES) {
            view.post { if (webView === view && !rendererGone) proxy.postMessage(message) }
        }
    }

    private fun declaresCapability(id: String): Boolean = installed.manifest.capabilityRequirements.any { it.id == id }

    private fun failure(requestId: String, code: RpcErrorCode, message: String): String = try {
        RpcEnvelope.failure(
            ProtocolVersion.CURRENT,
            installed.manifest.plugin.id,
            sessionId,
            requestId,
            code,
            message,
            false,
        ).toString()
    } catch (_: JSONException) {
        "{}"
    }

    private fun isAllowedOrigin(uri: Uri): Boolean = uri.scheme == "https"
        && uri.host == originUri.host
        && uri.port == -1

    private fun hasUnsafeEncodedPath(uri: Uri): Boolean {
        val path = uri.encodedPath.orEmpty().lowercase(Locale.ROOT)
        return '%' in path || '\\' in path || path.contains("//")
    }

    private fun mimeType(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (extension) {
            "js", "mjs" -> "application/javascript"
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "wasm" -> "application/wasm"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }
    }

    private fun encodingFor(mime: String): String? = if (
        mime.startsWith("text/") || mime == "application/javascript" || mime == "application/json" || mime == "image/svg+xml"
    ) "UTF-8" else null

    private fun deniedResponse(): WebResourceResponse = textResponse(403, "Forbidden", "Resource blocked")
    private fun notFoundResponse(): WebResourceResponse = textResponse(404, "Not Found", "Resource not found")

    private fun textResponse(status: Int, reason: String, body: String): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        reason,
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
        ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
    )

    companion object {
        private const val USER_GESTURE_WINDOW_MS = 1_000L
        private const val CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'none'; media-src 'self' blob:; " +
            "worker-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; " +
            "frame-src 'none'; frame-ancestors 'none'"

        private fun randomSessionId(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }

        private fun unwrap(failure: Throwable): Throwable {
            var current = failure
            while ((current is CompletionException || current is java.util.concurrent.ExecutionException)
                && current.cause != null
            ) {
                current = current.cause!!
            }
            return current
        }
    }
}
