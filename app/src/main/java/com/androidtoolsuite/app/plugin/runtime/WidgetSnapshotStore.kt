package com.androidtoolsuite.app.plugin.runtime

import com.androidtoolsuite.app.BuildConfig
import android.util.Log
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.androidtoolsuite.runtime.contract.GeneratedContract
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Small presentation snapshots outlive the views, never the plugin's permissions or data revision. */
class WidgetSnapshotStore(context: Context, private val runtime: PluginRuntime) {
    private val preferences = context.getSharedPreferences("runtime-widget-snapshots", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = linkedMapOf<String, Entry>()
    private var visible = true

    class Entry internal constructor(
        internal val installed: PluginPackageStore.InstalledPlugin,
        internal val contribution: RuntimePluginManifest.HomeWidgetContribution,
        internal val persistent: Boolean,
        initial: JSONObject?,
    ) {
        var value by mutableStateOf(initial)
            internal set
        var error by mutableStateOf<String?>(null)
            internal set
        internal var dirty = initial == null
        internal var observers = 0
        internal var job: Job? = null
        internal var lastHostRevision = -1
    }

    init {
        runtime.datasets().addChangeListener { id -> main.post { invalidate(id, false) } }
        runtime.permissions().addListener { id, _, _ -> main.post { invalidate(id, true) } }
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible) entries.values.filter { it.observers > 0 }.forEach(::refresh)
    }

    fun reconcile(installed: List<PluginPackageStore.InstalledPlugin>) {
        val active = installed.filter { it.enabled }.associateBy { it.manifest.plugin.id }
        entries.entries.removeAll { (_, entry) ->
            val current = active[entry.installed.manifest.plugin.id]
            val removed = current == null || current.generationDirectory != entry.installed.generationDirectory
            if (removed) entry.job?.cancel()
            removed
        }
        preferences.edit().apply {
            preferences.all.keys.filter { it.substringBefore('/') !in active }.forEach(::remove)
        }.apply()
    }

    fun entry(installed: PluginPackageStore.InstalledPlugin, contribution: RuntimePluginManifest.HomeWidgetContribution): Entry {
        val key = "${installed.manifest.plugin.id}/${contribution.id}"
        entries[key]?.takeIf { it.installed.generationDirectory == installed.generationDirectory }?.let { return it }
        entries.remove(key)?.job?.cancel()
        // Only a plugin's own data summaries persist. System connection/authorization is queried live.
        val persistent = GeneratedContract.capabilityForMethod(contribution.dataSource) == null &&
            installed.manifest.capabilityContributions.any {
                it.workerEntry.isNotBlank() && contribution.dataSource in it.methods
            }
        if (!persistent) preferences.edit().remove(key).apply()
        val cached = if (persistent && allowed(installed, contribution)) runCatching {
            val saved = JSONObject(preferences.getString(key, "")!!)
            if (saved.optString("package") == installed.generationDirectory.absolutePath &&
                saved.optString("data") == runtime.datasets().revisionForCache(installed.manifest.plugin.id)) {
                saved.getJSONObject("value")
            } else null
        }.getOrNull() else null
        if (BuildConfig.DEBUG) Log.d("AtsWidget", "entry plugin=${installed.manifest.plugin.id} restored=${cached != null}")
        return Entry(installed, contribution, persistent, cached).also { entries[key] = it }
    }

    fun observe(entry: Entry): AutoCloseable {
        entry.observers++
        refresh(entry)
        return AutoCloseable { entry.observers-- }
    }

    fun hostChanged(entry: Entry, revision: Int) {
        if (entry.persistent || entry.lastHostRevision == revision) return
        entry.lastHostRevision = revision
        entry.dirty = true
        entry.job?.cancel()
        entry.job = null
        refresh(entry)
    }

    private fun allowed(installed: PluginPackageStore.InstalledPlugin, contribution: RuntimePluginManifest.HomeWidgetContribution): Boolean {
        val capability = GeneratedContract.capabilityForMethod(contribution.dataSource)
            ?: installed.manifest.capabilityContributions.firstOrNull { contribution.dataSource in it.methods }?.id
            ?: return false
        return installed.manifest.capabilityRequirements.any { it.id == capability } &&
            runtime.permissions().isGranted(installed.manifest, capability)
    }

    private fun invalidate(pluginId: String, clear: Boolean) {
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith("$pluginId/") }.forEach(::remove)
        }.apply()
        entries.values.filter { it.installed.manifest.plugin.id == pluginId }.forEach {
            it.job?.cancel()
            it.job = null
            it.dirty = true
            if (clear) it.value = null
            if (it.observers > 0) refresh(it)
        }
    }

    private fun refresh(entry: Entry) {
        if (!visible || !entry.dirty || entry.job?.isActive == true) return
        entry.job = scope.launch {
            // Coalesce a restore/sync's consecutive commits and provider connection callbacks.
            delay(120)
            if (!visible) return@launch
            val id = entry.installed.manifest.plugin.id
            val session = "widget-" + UUID.randomUUID()
            try {
                check(allowed(entry.installed, entry.contribution)) { "请先允许组件所需的功能权限" }
                val revision = withContext(Dispatchers.IO) { runtime.datasets().revisionForCache(id) }
                val result = suspendCancellableCoroutine<JSONObject> { continuation ->
                    val future = runtime.capabilities().invoke(entry.installed.manifest, id, session,
                        entry.contribution.dataSource, JSONObject(), false, 5_000)
                    continuation.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { value, failure ->
                        if (continuation.isActive) {
                            if (failure == null) continuation.resume(value ?: JSONObject())
                            else continuation.resumeWithException(failure.cause ?: failure)
                        }
                    }
                }
                ensureActive()
                // Persist only fields rendered by the card, not provider service lists or arbitrary payloads.
                val snapshot = JSONObject()
                listOf("title", "detail", "label", "value", "uid", "state", "connected").forEach {
                    if (result.has(it)) snapshot.put(it, result.get(it))
                }
                if (BuildConfig.DEBUG) Log.d("AtsWidget", "refreshed plugin=$id")
                entry.value = snapshot
                entry.error = null
                entry.dirty = false
                if (entry.persistent) preferences.edit().putString("$id/${entry.contribution.id}",
                    JSONObject().put("package", entry.installed.generationDirectory.absolutePath)
                        .put("data", revision).put("value", snapshot).toString()).apply()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                entry.error = error.message ?: "暂时无法刷新"
                // Never show a previous connection/permission as confirmed after a failed refresh.
                if (!entry.persistent) entry.value = null
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    runtime.storage().closeSession(session)
                    runtime.datasets().closeSession(session)
                }
            }
        }
    }
}
