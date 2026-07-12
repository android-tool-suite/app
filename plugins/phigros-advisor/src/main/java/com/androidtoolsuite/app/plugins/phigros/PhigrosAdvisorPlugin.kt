package com.androidtoolsuite.app.plugins.phigros

import android.app.Activity
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.androidtoolsuite.app.plugin.api.HomeWidget
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.api.PluginHost
import com.androidtoolsuite.app.plugin.api.ToolPlugin
import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor
import com.androidtoolsuite.app.ui.composePluginView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class PhigrosAdvisorPlugin(
    private val descriptor: ImportedPluginDescriptor = PhigrosAdvisorPluginDescriptor.create(),
) : ToolPlugin {
    internal val state: MutableState<PhigrosUiState> = mutableStateOf(PhigrosUiState())

    private val executor = Executors.newFixedThreadPool(3)
    private val cloudClient = PhigrosCloudClient()
    private var activity: Activity? = null
    private var rootView: View? = null
    private var tokenStore: SecureTokenStore? = null
    private var catalogRepository: SongCatalogRepository? = null
    private var historyStore: PhigrosHistoryStore? = null
    private var saveCache: PhigrosSaveCache? = null
    private var imageRenderer: PhigrosImageRenderer? = null
    @Volatile private var loginGeneration: Long = 0L

    override fun id(): String = descriptor.id
    override fun title(): String = descriptor.title
    override fun description(): String = descriptor.description
    override fun version(): String = descriptor.version
    override fun removable(): Boolean = true
    override fun dependencies(): Set<String> = descriptor.dependencies

    override fun createHomeWidgets(activity: Activity, host: PluginHost): List<HomeWidget> = listOf(
        object : HomeWidget {
            override fun id(): String = "rks_summary"
            override fun title(): String = "Phigros RKS"
            override fun pluginId(): String = this@PhigrosAdvisorPlugin.id()
            override fun supportedSizes(): List<HomeWidgetSize> = listOf(HomeWidgetSize(2, 2), HomeWidgetSize(4, 2))
            override fun createView(activity: Activity, host: PluginHost): View {
                val prefs = activity.getSharedPreferences(WIDGET_PREFS, Activity.MODE_PRIVATE)
                val rks = Double.fromBits(prefs.getLong("rks", 0L))
                val count = prefs.getInt("count", 0)
                val player = prefs.getString("player", "等待同步").orEmpty()
                return composePluginView(activity) { PhigrosHomeWidget(player, rks, count) }
            }
        },
    )

    override fun createView(activity: Activity, host: PluginHost): View {
        if (this.activity !== activity || rootView == null) {
            this.activity = activity
            tokenStore = SecureTokenStore(activity)
            catalogRepository = SongCatalogRepository(activity)
            historyStore = PhigrosHistoryStore(activity)
            saveCache = PhigrosSaveCache(activity)
            imageRenderer = PhigrosImageRenderer(activity)
            rootView = composePluginView(activity) { PhigrosAdvisorScreen(this) }
            initialize()
        }
        return rootView!!
    }

    internal fun selectPage(page: PhigrosPage) = updateState { copy(page = page) }

    internal fun dismissDialog() = updateState { copy(dialog = null) }

    internal fun dismissGeneratedImage() = updateState { copy(generatedImage = null) }

    internal fun saveToken(label: String, token: String, server: PhigrosServer) = runTask {
        val profile = requireStore().save(label, token.trim(), server)
        val local = restoreLocalSave(profile.id)
        postState {
            copy(
                tokenProfiles = requireStore().profiles(),
                selectedTokenId = profile.id,
                timeline = requireHistory().load(profile.id),
                save = local.save,
                snapshot = local.snapshot,
                pushTargets = local.pushTargets,
                generatedImage = null,
            )
        }
        warmPushTargets(profile.id, local)
    }

    internal fun deleteToken(id: String) = runTask {
        requireStore().delete(id)
        val selected = requireStore().selectedId()
        val local = restoreLocalSave(selected)
        postState {
            copy(
                tokenProfiles = requireStore().profiles(),
                selectedTokenId = selected,
                timeline = requireHistory().load(selected),
                save = local.save,
                snapshot = local.snapshot,
                pushTargets = local.pushTargets,
                generatedImage = null,
            )
        }
        warmPushTargets(selected, local)
    }

    internal fun selectToken(id: String) = runTask {
        requireStore().select(id)
        val local = restoreLocalSave(id)
        postState {
            copy(
                tokenProfiles = requireStore().profiles(),
                selectedTokenId = id,
                timeline = requireHistory().load(id),
                save = local.save,
                snapshot = local.snapshot,
                pushTargets = local.pushTargets,
                generatedImage = null,
            )
        }
        warmPushTargets(id, local)
    }

    internal fun startTapLogin(server: PhigrosServer) {
        val generation = ++loginGeneration
        runTask {
            val request = cloudClient.requestTapLogin(server)
            val qr = QrBitmap.encode(request.loginUrl)
            postState {
                copy(
                    loginRequest = request,
                    loginQr = qr,
                    loginSecondsLeft = max(0, ((request.expiresAt - System.currentTimeMillis()) / 1000L).toInt()),
                )
            }
            executor.submit { pollTapLogin(request, generation) }
        }
    }

    internal fun openLoginLink() {
        val url = state.value.loginRequest?.loginUrl ?: return
        activity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    internal fun refreshCatalog() = runTask {
        val catalog = requireCatalog().load(forceRefresh = true)
        postState { copy(catalog = catalog) }
    }

    internal fun fetchLatest() = runTask {
        val stored = requireStore().selected() ?: error("请先保存或选择一个 SessionToken")
        val catalog = state.value.catalog.ifEmpty { requireCatalog().load() }
        val save = cloudClient.fetchSave(stored.token, stored.profile.server, catalog)
        val snapshot = PhigrosRks.calculate(save.records)
        val targets = PhigrosRks.pushTargets(save.records, snapshot)
        val timeline = requireHistory().record(stored.profile.id, save, snapshot)
        requireSaveCache().save(stored.profile.id, save, targets)
        requireStore().select(stored.profile.id)
        activity?.getSharedPreferences(WIDGET_PREFS, Activity.MODE_PRIVATE)?.edit()
            ?.putLong("rks", snapshot.overall.toBits())
            ?.putInt("count", save.records.size)
            ?.putString("player", save.profile.playerId)
            ?.apply()
        postState {
            copy(
                catalog = catalog,
                save = save,
                snapshot = snapshot,
                pushTargets = targets,
                timeline = timeline,
                tokenProfiles = requireStore().profiles(),
            )
        }
    }

    internal fun generateB30Image() = runTask {
        val save = state.value.save ?: error("请先同步云存档")
        val image = requireRenderer().renderB30(save, state.value.snapshot, state.value.pushTargets)
        postState { copy(generatedImage = image) }
    }

    internal fun generateProfileImage() = runTask {
        val save = state.value.save ?: error("请先同步云存档")
        val image = requireRenderer().renderProfile(save, state.value.snapshot)
        postState { copy(generatedImage = image) }
    }

    internal fun saveGeneratedImageToGallery() {
        val currentActivity = activity ?: return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            currentActivity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            currentActivity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            updateState {
                copy(dialog = UiDialog("需要存储权限", "请允许存储权限，然后再次点击“保存到相册”。"))
            }
            return
        }
        runTask { saveGeneratedImageToGalleryInternal(currentActivity) }
    }

    @Suppress("DEPRECATION")
    private fun saveGeneratedImageToGalleryInternal(currentActivity: Activity) {
        val image = state.value.generatedImage ?: error("没有可保存的图片，请先生成预览")
        val resolver = currentActivity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.suggestedFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$GALLERY_ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val album = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    GALLERY_ALBUM,
                ).apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, java.io.File(album, image.suggestedFileName).absolutePath)
            }
        }
        val destination = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在系统相册中创建图片")
        try {
            val saved = resolver.openOutputStream(destination, "w")?.use { output ->
                image.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
            } ?: false
            check(saved) { "无法写入系统相册" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(destination, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            } else {
                MediaScannerConnection.scanFile(
                    currentActivity,
                    arrayOf(values.getAsString(MediaStore.Images.Media.DATA)),
                    arrayOf("image/png"),
                    null,
                )
            }
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
        postState {
            copy(
                generatedImage = null,
                dialog = UiDialog("保存成功", "图片已保存到相册“$GALLERY_ALBUM”。"),
            )
        }
    }

    private fun initialize() {
        executor.submit {
            try {
                val profiles = requireStore().profiles()
                val selected = requireStore().selectedId()
                val timeline = requireHistory().load(selected)
                val local = restoreLocalSave(selected)
                postState {
                    copy(
                        loading = false,
                        tokenProfiles = profiles,
                        selectedTokenId = selected,
                        timeline = timeline,
                        save = local.save,
                        snapshot = local.snapshot,
                        pushTargets = local.pushTargets,
                    )
                }
                warmPushTargets(selected, local)

                val catalogResult = runCatching { requireCatalog().load() }
                postState {
                    copy(
                        catalog = catalogResult.getOrDefault(emptyList()),
                        dialog = catalogResult.exceptionOrNull()?.let {
                            UiDialog("曲库加载失败", "暂时无法加载曲目与定数：${safeMessage(it)}")
                        },
                    )
                }
            } catch (error: Throwable) {
                postError(error)
            }
        }
    }

    private fun pollTapLogin(request: TapLoginRequest, generation: Long) {
        try {
            while (generation == loginGeneration && System.currentTimeMillis() < request.expiresAt) {
                val seconds = max(0, ((request.expiresAt - System.currentTimeMillis()) / 1000L).toInt())
                postState { copy(loginSecondsLeft = seconds) }
                val token = cloudClient.pollTapLogin(request)
                if (token != null) {
                    val label = "TapTap ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}"
                    val profile = requireStore().save(label, token, request.server)
                    val local = restoreLocalSave(profile.id)
                    postState {
                        copy(
                            tokenProfiles = requireStore().profiles(),
                            selectedTokenId = profile.id,
                            timeline = requireHistory().load(profile.id),
                            loginRequest = null,
                            loginQr = null,
                            loginSecondsLeft = 0,
                            loading = false,
                            save = local.save,
                            snapshot = local.snapshot,
                            pushTargets = local.pushTargets,
                            generatedImage = null,
                            dialog = UiDialog("登录成功", "SessionToken 已获取并使用 Android Keystore 加密保存。"),
                        )
                    }
                    warmPushTargets(profile.id, local)
                    return
                }
                Thread.sleep(request.intervalSeconds * 1000L)
            }
            if (generation == loginGeneration) {
                postState {
                    copy(
                        loginRequest = null,
                        loginQr = null,
                        loginSecondsLeft = 0,
                        loading = false,
                        dialog = UiDialog("二维码已过期", "请重新获取 TapTap 登录二维码。"),
                    )
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (generation == loginGeneration) postError(error)
        }
    }

    private fun runTask(block: () -> Unit) {
        updateState { copy(loading = true) }
        executor.submit {
            try {
                block()
                postState { copy(loading = false) }
            } catch (error: Throwable) {
                postError(error)
            }
        }
    }

    private fun postError(error: Throwable) {
        postState { copy(loading = false, dialog = UiDialog("操作失败", safeMessage(error))) }
    }

    private fun postState(transform: PhigrosUiState.() -> PhigrosUiState) {
        activity?.runOnUiThread { updateState(transform) }
    }

    private fun updateState(transform: PhigrosUiState.() -> PhigrosUiState) {
        state.value = state.value.transform()
    }

    private fun requireStore() = tokenStore ?: error("令牌存储尚未初始化")
    private fun requireCatalog() = catalogRepository ?: error("曲库尚未初始化")
    private fun requireHistory() = historyStore ?: error("历史记录尚未初始化")
    private fun requireSaveCache() = saveCache ?: error("本地存档缓存尚未初始化")
    private fun requireRenderer() = imageRenderer ?: error("图片生成器尚未初始化")
    private fun safeMessage(error: Throwable): String = error.message?.take(240) ?: error.javaClass.simpleName

    private fun restoreLocalSave(scope: String?): RestoredSave {
        val cached = requireSaveCache().load(scope)
        val save = cached?.save
        val snapshot = save?.let { PhigrosRks.calculate(it.records) } ?: RksSnapshot.EMPTY
        val targets = cached?.pushTargets.orEmpty()
        return RestoredSave(save, snapshot, targets)
    }

    private fun warmPushTargets(scope: String?, local: RestoredSave) {
        val cachedSave = local.save ?: return
        if (scope.isNullOrBlank() || local.pushTargets.isNotEmpty()) return
        if (cachedSave.records.none { it.score > 0 && it.constant > 0.0 }) return
        executor.submit {
            val targets = runCatching { PhigrosRks.pushTargets(cachedSave.records, local.snapshot) }
                .getOrElse { return@submit }
            runCatching { requireSaveCache().save(scope, cachedSave, targets) }
            postState {
                if (selectedTokenId == scope && save?.profile?.saveUpdatedAt == cachedSave.profile.saveUpdatedAt) {
                    copy(pushTargets = targets)
                } else {
                    this
                }
            }
        }
    }

    override fun onSelected() = Unit
    override fun onHostStateChanged() = Unit

    override fun onDestroy() {
        loginGeneration++
        executor.shutdownNow()
        rootView = null
        activity = null
    }

    companion object {
        private const val WIDGET_PREFS = "phigros_data_studio_widget"
        private const val GALLERY_ALBUM = "Phigros Data Studio"
        private const val STORAGE_PERMISSION_REQUEST = 0x5048
    }

    private data class RestoredSave(
        val save: PhigrosSave?,
        val snapshot: RksSnapshot,
        val pushTargets: Map<String, PushTarget>,
    )
}
