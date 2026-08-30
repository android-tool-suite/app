package com.androidtoolsuite.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// 浅色档的容器色刻意压得比 Material 生成器给的更暗、更不饱和：
// primaryContainer 会铺满主页顶部那张摘要卡，#9EF2E2 那种亮青在浅色背景上是整屏最刺眼的一块。
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3E4DC),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E7E2),
    onSecondaryContainer = Color(0xFF0B1F1B),
    tertiary = Color(0xFF446179),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCEE5FF),
    onTertiaryContainer = Color(0xFF001D32),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = Color(0xFF006B5F),
    inverseSurface = Color(0xFF2D3130),
    inverseOnSurface = Color(0xFFEFF1EF),
    inversePrimary = Color(0xFF82D5C6),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBFC9C5),
    scrim = Color.Black,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceDim = Color(0xFFD7DDD9),
    surfaceBright = Color(0xFFF7FAF8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F2),
    surfaceContainer = Color(0xFFEAF0ED),
    surfaceContainerHigh = Color(0xFFE3EAE6),
    surfaceContainerHighest = Color(0xFFDCE5E1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D5C6),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9EF2E2),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFD5E7E2),
    tertiary = Color(0xFFACCBE5),
    onTertiary = Color(0xFF153349),
    tertiaryContainer = Color(0xFF2D4960),
    onTertiaryContainer = Color(0xFFCEE5FF),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceTint = Color(0xFF82D5C6),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2D3130),
    inversePrimary = Color(0xFF006B5F),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    scrim = Color.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceDim = Color(0xFF0E1513),
    surfaceBright = Color(0xFF343B39),
    surfaceContainerLowest = Color(0xFF08100E),
    surfaceContainerLow = Color(0xFF151C1A),
    surfaceContainer = Color(0xFF1A2220),
    surfaceContainerHigh = Color(0xFF252D2A),
    surfaceContainerHighest = Color(0xFF303936),
)

/**
 * SDK 1.1.0 的固定语义色。
 *
 * 这几个值只有浅色一档，放在深色面板上对比度不足，已由成对的 [SuiteSemantic] 取代。
 * 仍然保留是因为它是 1.1.0 的公开 API，外部插件可能已经编译进去了——直接删会让那些插件
 * 在运行时抛 NoSuchFieldError。新代码不要再用。
 */
@Deprecated(
    message = "改用 SuiteSemantic.current，它有浅色与深色两套值。",
    replaceWith = ReplaceWith("SuiteSemantic.current"),
)
object SuiteColors {
    val Success = Color(0xFF1B6B45)
    val Warning = Color(0xFF8A4F00)
    val WarningContainer = Color(0xFFFFDDB3)
    val Info = Color(0xFF245D91)
}

enum class SuiteThemePreference { SYSTEM, LIGHT, DARK }
enum class SuiteColorPreference { BRAND, DYNAMIC }

object SuiteThemePreferences {
    var themePreference: SuiteThemePreference by mutableStateOf(SuiteThemePreference.SYSTEM)
        private set
    var colorPreference: SuiteColorPreference by mutableStateOf(SuiteColorPreference.BRAND)
        private set

    fun update(theme: SuiteThemePreference, color: SuiteColorPreference) {
        themePreference = theme
        colorPreference = color
    }
}

@Composable
fun SuiteTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme = resolveSuiteColorScheme(context, darkTheme, dynamicColor)
    CompositionLocalProvider(
        LocalSuiteSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
        LocalSuiteDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = MaterialTheme.shapes.copy(
                extraSmall = SuiteShapes.Chip,
                medium = SuiteShapes.Inner,
                large = SuiteShapes.Card,
                extraLarge = SuiteShapes.Dialog,
            ),
            typography = MaterialTheme.typography.copy(
                headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp),
                headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            ),
            content = content,
        )
    }
}

private fun resolveSuiteColorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
    darkTheme -> DarkColors
    else -> LightColors
}

/**
 * Web Tool 使用的同源 CSS token 快照。
 *
 * Android 插件运行时 从宿主虚拟源提供这段 CSS；Web Tool 只消费 token，不复制 Compose 色板，也不
 * 自行读取系统主题覆盖宿主设置。主题改变时宿主创建新快照并发送 `app.themeChanged`。
 */
object SuiteWebTheme {
    @JvmStatic
    fun css(context: Context): String = css(
        context,
        SuiteThemePreferences.themePreference,
        SuiteThemePreferences.colorPreference,
    )

    @JvmStatic
    fun css(
        context: Context,
        themePreference: SuiteThemePreference,
        colorPreference: SuiteColorPreference,
    ): String {
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (themePreference) {
            SuiteThemePreference.SYSTEM -> systemDark
            SuiteThemePreference.LIGHT -> false
            SuiteThemePreference.DARK -> true
        }
        val colors = resolveSuiteColorScheme(
            context,
            dark,
            colorPreference == SuiteColorPreference.DYNAMIC,
        )
        val semantic = if (dark) DarkSemanticColors else LightSemanticColors
        return buildString {
            append(":root{color-scheme:")
            append(if (dark) "dark" else "light")
            append(';')
            cssColor("primary", colors.primary)
            cssColor("on-primary", colors.onPrimary)
            cssColor("primary-container", colors.primaryContainer)
            cssColor("on-primary-container", colors.onPrimaryContainer)
            cssColor("secondary-container", colors.secondaryContainer)
            cssColor("on-secondary-container", colors.onSecondaryContainer)
            cssColor("background", colors.background)
            cssColor("on-background", colors.onBackground)
            cssColor("surface", colors.surface)
            cssColor("on-surface", colors.onSurface)
            cssColor("surface-low", colors.surfaceContainerLow)
            cssColor("surface-container", colors.surfaceContainer)
            cssColor("surface-high", colors.surfaceContainerHigh)
            cssColor("surface-highest", colors.surfaceContainerHighest)
            cssColor("on-surface-variant", colors.onSurfaceVariant)
            cssColor("outline", colors.outline)
            cssColor("outline-variant", colors.outlineVariant)
            cssColor("error", colors.error)
            cssColor("on-error", colors.onError)
            cssColor("error-container", colors.errorContainer)
            cssColor("on-error-container", colors.onErrorContainer)
            cssColor("success", semantic.success)
            cssColor("on-success", semantic.onSuccess)
            cssColor("success-container", semantic.successContainer)
            cssColor("on-success-container", semantic.onSuccessContainer)
            cssColor("warning", semantic.warning)
            cssColor("on-warning", semantic.onWarning)
            cssColor("warning-container", semantic.warningContainer)
            cssColor("on-warning-container", semantic.onWarningContainer)
            cssColor("info", semantic.info)
            cssColor("on-info", semantic.onInfo)
            cssColor("info-container", semantic.infoContainer)
            cssColor("on-info-container", semantic.onInfoContainer)
            append("--ats-space-xs:4px;--ats-space-sm:8px;--ats-space-md:12px;")
            append("--ats-space-lg:16px;--ats-space-xl:20px;--ats-space-xxl:24px;--ats-space-xxxl:32px;")
            append("--ats-card-radius:24px;--ats-inner-radius:16px;--ats-chip-radius:12px;--ats-dialog-radius:28px;")
            append("--ats-content-max-width:720px;--ats-font-family:system-ui,-apple-system,'Noto Sans SC',sans-serif;")
            append("--ats-type-headline-large-size:32px;--ats-type-headline-large-line:40px;--ats-type-headline-large-weight:700;")
            append("--ats-type-headline-small-size:24px;--ats-type-headline-small-line:32px;--ats-type-headline-small-weight:600;")
            append("--ats-type-title-large-size:22px;--ats-type-title-large-line:28px;--ats-type-title-large-weight:600;")
            append("--ats-type-title-medium-size:16px;--ats-type-title-medium-line:24px;--ats-type-title-medium-weight:600;")
            append("--ats-type-body-large-size:16px;--ats-type-body-large-line:24px;--ats-type-body-large-weight:400;")
            append("--ats-type-body-medium-size:14px;--ats-type-body-medium-line:20px;--ats-type-body-medium-weight:400;")
            append("--ats-type-body-small-size:12px;--ats-type-body-small-line:16px;--ats-type-body-small-weight:400;")
            append("--ats-type-label-large-size:14px;--ats-type-label-large-line:20px;--ats-type-label-large-weight:600;")
            append("--ats-type-label-medium-size:12px;--ats-type-label-medium-line:16px;--ats-type-label-medium-weight:500;")
            append('}')
        }
    }

    private fun StringBuilder.cssColor(name: String, color: Color) {
        append("--ats-")
        append(name)
        append(':')
        append(String.format(Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF))
        append(';')
    }
}

@Composable
fun SuiteTheme(
    themePreference: SuiteThemePreference,
    colorPreference: SuiteColorPreference,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        SuiteThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        SuiteThemePreference.LIGHT -> false
        SuiteThemePreference.DARK -> true
    }
    SuiteTheme(darkTheme, colorPreference == SuiteColorPreference.DYNAMIC, content)
}

@Composable
fun SuiteCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = SuiteShapes.Card,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) Row(content = action)
    }
}

@Composable
fun Notice(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (warning) SuiteSemantic.current.warningContainer else MaterialTheme.colorScheme.secondaryContainer
    val onColor = if (warning) SuiteSemantic.current.onWarningContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier.fillMaxWidth(), color = color, shape = SuiteShapes.Inner) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = onColor)
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Creates a correctly-owned Compose root for Java-based plugin entry points. */
fun composePluginView(activity: Activity, content: @Composable () -> Unit): View = ComposeView(activity).apply {
    setContent {
        SuiteTheme(
            themePreference = SuiteThemePreferences.themePreference,
            colorPreference = SuiteThemePreferences.colorPreference,
            content = content,
        )
    }
}
