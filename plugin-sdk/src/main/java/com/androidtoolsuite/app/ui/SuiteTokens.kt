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

@Immutable
data class SuiteSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
)

internal val LightSemanticColors = SuiteSemanticColors(
    success = Color(0xFF176B45),
    onSuccess = Color.White,
    successContainer = Color(0xFFA4F4C4),
    warning = Color(0xFF7A4B00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB3),
    danger = Color(0xFFBA1A1A),
    onDanger = Color.White,
    dangerContainer = Color(0xFFFFDAD6),
    info = Color(0xFF245D91),
    onInfo = Color.White,
    infoContainer = Color(0xFFD2E4FF),
)

internal val DarkSemanticColors = SuiteSemanticColors(
    success = Color(0xFF88D5A6),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF00522F),
    warning = Color(0xFFF3BD6F),
    onWarning = Color(0xFF432C00),
    warningContainer = Color(0xFF5D4200),
    danger = Color(0xFFFFB4AB),
    onDanger = Color(0xFF690005),
    dangerContainer = Color(0xFF93000A),
    info = Color(0xFFA3C9FF),
    onInfo = Color(0xFF00315C),
    infoContainer = Color(0xFF074875),
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
