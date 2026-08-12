package com.androidtoolsuite.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun LoadingState(label: String = "正在加载…", modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(SuiteSpacing.xxl),
        horizontalArrangement = Arrangement.spacedBy(SuiteSpacing.md, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ErrorState(
    title: String,
    body: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = SuiteShapes.Card, color = SuiteSemantic.current.dangerContainer) {
        Column(Modifier.padding(SuiteSpacing.xl), verticalArrangement = Arrangement.spacedBy(SuiteSpacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SuiteSemantic.current.onDangerContainer)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = SuiteSemantic.current.onDangerContainer)
            if (onRetry != null) Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
fun DismissibleNotice(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val colors = SuiteSemantic.current
    val background: Color = if (warning) colors.warningContainer else MaterialTheme.colorScheme.secondaryContainer
    val foreground: Color = if (warning) colors.onWarningContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(modifier.fillMaxWidth(), color = background, shape = SuiteShapes.Inner) {
        Row(
            Modifier.padding(start = SuiteSpacing.lg, top = SuiteSpacing.sm, bottom = SuiteSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), color = foreground, style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭", tint = foreground) }
        }
    }
}

@Composable
fun SuiteStatusChip(text: String, modifier: Modifier = Modifier, positive: Boolean = true) {
    val colors = SuiteSemantic.current
    Surface(
        modifier = modifier,
        shape = SuiteShapes.Chip,
        color = if (positive) colors.successContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = SuiteSpacing.md, vertical = SuiteSpacing.xs),
            style = MaterialTheme.typography.labelMedium,
            color = if (positive) colors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
