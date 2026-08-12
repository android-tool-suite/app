package com.androidtoolsuite.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object SuiteSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val ScreenPadding = 20.dp
    val ListGap = 12.dp
}

object SuiteShapes {
    val Card = RoundedCornerShape(24.dp)
    val Inner = RoundedCornerShape(16.dp)
    val Chip = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(28.dp)
}

/**
 * 语义色。每档都是 Material 3 的四元组：实心底色、实心底色上的前景、浅底容器、容器上的前景。
 *
 * `onWarning` 之类是给实心 [warning] 用的，浅色档下是白色；容器上的文字必须用
 * `onWarningContainer`，否则就是白字压浅黄底。
 */
@Immutable
data class SuiteSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

internal val LightSemanticColors = SuiteSemanticColors(
    success = Color(0xFF176B45),
    onSuccess = Color.White,
    successContainer = Color(0xFFB8ECCE),
    onSuccessContainer = Color(0xFF002512),
    warning = Color(0xFF7A4B00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB3),
    onWarningContainer = Color(0xFF281800),
    danger = Color(0xFFBA1A1A),
    onDanger = Color.White,
    dangerContainer = Color(0xFFFFDAD6),
    onDangerContainer = Color(0xFF410002),
    info = Color(0xFF245D91),
    onInfo = Color.White,
    infoContainer = Color(0xFFD2E4FF),
    onInfoContainer = Color(0xFF001C37),
)

internal val DarkSemanticColors = SuiteSemanticColors(
    success = Color(0xFF88D5A6),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF00522F),
    onSuccessContainer = Color(0xFFA4F2C3),
    warning = Color(0xFFF3BD6F),
    onWarning = Color(0xFF432C00),
    warningContainer = Color(0xFF5D4200),
    onWarningContainer = Color(0xFFFFDDB3),
    danger = Color(0xFFFFB4AB),
    onDanger = Color(0xFF690005),
    dangerContainer = Color(0xFF93000A),
    onDangerContainer = Color(0xFFFFDAD6),
    info = Color(0xFFA3C9FF),
    onInfo = Color(0xFF00315C),
    infoContainer = Color(0xFF074875),
    onInfoContainer = Color(0xFFD2E4FF),
)

internal val LocalSuiteSemanticColors = staticCompositionLocalOf { LightSemanticColors }

object SuiteSemantic {
    val current: SuiteSemanticColors
        @Composable get() = LocalSuiteSemanticColors.current
}

internal val LocalSuiteDarkTheme = staticCompositionLocalOf { false }

/**
 * Whether the enclosing [SuiteTheme] resolved to its dark variant.
 *
 * Plugins that own a domain palette (game rarity tiers, difficulty levels, brand accents the
 * suite has no opinion about) need this to pick between their own light and dark values.
 * Reading `isSystemInDarkTheme()` directly would be wrong, because the host can pin the theme
 * to light or dark regardless of the system setting.
 */
object SuiteTheming {
    val isDark: Boolean
        @Composable get() = LocalSuiteDarkTheme.current
}
