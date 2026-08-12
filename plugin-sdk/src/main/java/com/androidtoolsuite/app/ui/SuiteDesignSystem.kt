package com.androidtoolsuite.app.ui

import android.app.Activity
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 浅色档的容器色刻意压得比 Material 生成器给的更暗、更不饱和：
// primaryContainer 会铺满主页顶部那张摘要卡，#9EF2E2 那种亮青在浅色背景上是整屏最刺眼的一块。
private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC3E4DC),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    secondaryContainer = Color(0xFFD5E7E2),
    tertiary = Color(0xFF446179),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDAE5E1),
    outline = Color(0xFF6F7976),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D5C6),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9EF2E2),
    secondary = Color(0xFFB1CCC5),
    secondaryContainer = Color(0xFF334B47),
    tertiary = Color(0xFFACCBE5),
    background = Color(0xFF0E1513),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF89938F),
    error = Color(0xFFFFB4AB),
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
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
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
