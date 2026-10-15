package io.github.kimgeonwoo718.roki.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ROKI's own indigo palette (fixed; no dynamic color).
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF3B3FC7),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE1E0FF),
        onPrimaryContainer = Color(0xFF13126B),
        secondary = Color(0xFF5C5D72),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE1E0F9),
        onSecondaryContainer = Color(0xFF191A2C),
        tertiary = Color(0xFF7A5368),
        background = Color(0xFFFBFAFF),
        onBackground = Color(0xFF1B1B21),
        surface = Color(0xFFFBFAFF),
        onSurface = Color(0xFF1B1B21),
        surfaceVariant = Color(0xFFE4E1EC),
        onSurfaceVariant = Color(0xFF46464F),
        surfaceContainer = Color(0xFFEFEDF4),
        surfaceContainerHigh = Color(0xFFE9E7EF),
        outline = Color(0xFF777680),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFC0C1FF),
        onPrimary = Color(0xFF20219A),
        primaryContainer = Color(0xFF3B3FC7),
        onPrimaryContainer = Color(0xFFE1E0FF),
        secondary = Color(0xFFC5C4DD),
        onSecondary = Color(0xFF2E2F42),
        secondaryContainer = Color(0xFF444559),
        onSecondaryContainer = Color(0xFFE1E0F9),
        tertiary = Color(0xFFEAB9D2),
        background = Color(0xFF121318),
        onBackground = Color(0xFFE4E1E9),
        surface = Color(0xFF121318),
        onSurface = Color(0xFFE4E1E9),
        surfaceVariant = Color(0xFF46464F),
        onSurfaceVariant = Color(0xFFC7C5D0),
        surfaceContainer = Color(0xFF1F1F25),
        surfaceContainerHigh = Color(0xFF29292F),
        outline = Color(0xFF91909A),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

@Composable
fun RokiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
