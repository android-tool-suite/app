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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E2),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    secondaryContainer = Color(0xFFCDE8E1),
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

object SuiteColors {
    val Success = Color(0xFF1B6B45)
    val Warning = Color(0xFF8A4F00)
    val WarningContainer = Color(0xFFFFDDB3)
    val Info = Color(0xFF245D91)
}

@Composable
fun SuiteTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = scheme,
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

@Composable
fun SuiteCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
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
    val color = if (warning) SuiteColors.WarningContainer else MaterialTheme.colorScheme.secondaryContainer
    val onColor = if (warning) Color(0xFF2B1700) else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(18.dp)) {
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
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Creates a correctly-owned Compose root for Java-based plugin entry points. */
fun composePluginView(activity: Activity, content: @Composable () -> Unit): View = ComposeView(activity).apply {
    setContent { SuiteTheme { content() } }
}
