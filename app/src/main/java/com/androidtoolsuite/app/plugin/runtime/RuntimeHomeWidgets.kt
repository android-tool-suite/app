package com.androidtoolsuite.app.plugin.runtime

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.androidtoolsuite.app.plugin.runtime.HostHomeWidget
import com.androidtoolsuite.app.plugin.api.HomeWidgetSize
import com.androidtoolsuite.app.plugin.runtime.HostServices
import com.androidtoolsuite.app.ui.LoadingState
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteSpacing
import com.androidtoolsuite.app.ui.SuiteStatusChip
import com.androidtoolsuite.app.ui.composePluginView
import com.androidtoolsuite.runtime.contract.GeneratedContract
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object RuntimeHomeWidgets {
    fun create(
        installed: PluginPackageStore.InstalledPlugin,
        actions: HostActions,
        revision: MutableIntState,
    ): List<HostHomeWidget> = installed.manifest.homeWidgetContributions.map { contribution ->
        RuntimeHomeWidget(installed, contribution, actions, revision)
    }
}

private class RuntimeHomeWidget(
    private val installed: PluginPackageStore.InstalledPlugin,
    private val contribution: RuntimePluginManifest.HomeWidgetContribution,
    private val actions: HostActions,
    private val revision: MutableIntState,
) : HostHomeWidget {
    override fun id(): String = contribution.id
    override fun title(): String = contribution.title
    override fun pluginId(): String = installed.manifest.plugin.id
    override fun supportedSizes(): List<HomeWidgetSize> = contribution.sizes.mapNotNull(::parseSize)

    override fun createView(activity: Activity, host: HostServices): View = composePluginView(activity) {
        RuntimeWidgetContent(installed, contribution, actions, revision.intValue)
    }
}

private sealed interface WidgetState {
    data object Loading : WidgetState
    data class Ready(val value: JSONObject) : WidgetState
    data class Failed(val message: String) : WidgetState
}

@Composable
private fun RuntimeWidgetContent(
    installed: PluginPackageStore.InstalledPlugin,
    contribution: RuntimePluginManifest.HomeWidgetContribution,
    actions: HostActions,
    revision: Int,
) {
    val sessionId = remember(installed.generationDirectory, contribution.id) { widgetSessionId() }
    var state by remember(installed.generationDirectory, contribution.id) {
        mutableStateOf<WidgetState>(WidgetState.Loading)
    }
    DisposableEffect(sessionId) {
        onDispose { actions.closeRuntimeSession(sessionId) }
    }
    LaunchedEffect(sessionId, revision) {
        state = try {
            val capability = GeneratedContract.capabilityForMethod(contribution.dataSource)
                ?: installed.manifest.capabilityContributions
                    .firstOrNull { provided -> contribution.dataSource in provided.methods }
                    ?.id
                ?: throw IllegalStateException("主页组件数据源无效")
            if (installed.manifest.capabilityRequirements.none { it.id == capability }) {
                throw IllegalStateException("主页组件未声明所需能力")
            }
            val result = suspendCancellableCoroutine<JSONObject> { continuation ->
                val future = actions.capabilityRouter().invoke(
                    installed.manifest,
                    installed.manifest.plugin.id,
                    sessionId,
                    contribution.dataSource,
                    JSONObject(),
                    false,
                    5_000,
                )
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (!continuation.isActive) return@whenComplete
                    if (error == null) continuation.resume(value ?: JSONObject())
                    else continuation.resumeWithException(unwrapWidgetError(error))
                }
            }
            WidgetState.Ready(result)
        } catch (error: Throwable) {
            WidgetState.Failed(error.message?.takeIf { it.isNotBlank() } ?: "暂时无法读取状态")
        }
    }

    val containerColor = if (installed.manifest.plugin.kind == "trusted-provider") {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    when (val current = state) {
        WidgetState.Loading -> SuiteCard(containerColor = containerColor) { LoadingState("正在读取状态…") }
        is WidgetState.Failed -> SuiteCard(containerColor = containerColor) {
            Text(contribution.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("暂时不可用", style = MaterialTheme.typography.titleMedium)
            Text(current.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is WidgetState.Ready -> RuntimeWidgetReady(contribution, current.value, containerColor)
    }
}

@Composable
private fun RuntimeWidgetReady(
    contribution: RuntimePluginManifest.HomeWidgetContribution,
    value: JSONObject,
    containerColor: Color,
) {
    val title = value.optString("title", contribution.title)
    val detail = value.optString("detail", value.optString("label", ""))
    val metric = value.opt("value")?.takeUnless { it == JSONObject.NULL }?.toString()
        ?: value.opt("uid")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
    val positive = value.optBoolean("connected", false) || value.optString("state") == "ready"
    SuiteCard(containerColor = containerColor) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.sm),
        ) {
            Text(
                contribution.title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!positive) {
                SuiteStatusChip("需处理", positive = false)
            }
        }
        when (contribution.template) {
            "metric" -> Column(verticalArrangement = Arrangement.spacedBy(SuiteSpacing.xs)) {
                Text(metric.ifBlank { "—" }, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun parseSize(raw: String): HomeWidgetSize? {
    val parts = raw.split('x')
    if (parts.size != 2) return null
    val width = parts[0].toIntOrNull() ?: return null
    val height = parts[1].toIntOrNull() ?: return null
    return runCatching { HomeWidgetSize(width, height) }.getOrNull()
}

private fun unwrapWidgetError(error: Throwable): Throwable {
    var current = error
    while (current is CompletionException && current.cause != null) current = current.cause!!
    return current
}

private fun widgetSessionId(): String {
    val bytes = ByteArray(18)
    SecureRandom().nextBytes(bytes)
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
}
