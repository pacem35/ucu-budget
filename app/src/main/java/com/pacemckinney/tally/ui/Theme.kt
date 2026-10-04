package com.pacemckinney.tally.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pacemckinney.tally.engine.Severity

/** Semantic colours that MaterialTheme doesn't have slots for. */
data class Tones(
    val income: Color,
    val spend: Color,
    val good: Color,
    val info: Color,
    val warn: Color,
    val alert: Color,
    val muted: Color,
    val chartPrev: Color,
    val savings: Color,
    val loan: Color,
) {
    fun of(s: Severity) = when (s) {
        Severity.GOOD -> good
        Severity.INFO -> info
        Severity.WARN -> warn
        Severity.ALERT -> alert
    }
}

val LocalTones = staticCompositionLocalOf {
    Tones(Color.Green, Color.Unspecified, Color.Green, Color.Blue, Color.Yellow, Color.Red, Color.Gray, Color.Gray, Color.Blue, Color.Magenta)
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7EE0A8),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF1B4D35),
    onPrimaryContainer = Color(0xFFB6F5CF),
    secondary = Color(0xFFB4CCBD),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDDE5DF),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDDE5DF),
    surfaceContainerLowest = Color(0xFF09100E),
    surfaceContainerLow = Color(0xFF151D1A),
    surfaceContainer = Color(0xFF19221F),
    surfaceContainerHigh = Color(0xFF232C29),
    surfaceContainerHighest = Color(0xFF2E3734),
    surfaceVariant = Color(0xFF2E3734),
    onSurfaceVariant = Color(0xFFA7B3AD),
    outline = Color(0xFF55615B),
    outlineVariant = Color(0xFF34403A),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF14724A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F5CF),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF4E6357),
    background = Color(0xFFF6FAF6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FAF6),
    onSurface = Color(0xFF171D1A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAF0EB),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFFDDE5DE),
    onSurfaceVariant = Color(0xFF414943),
    outline = Color(0xFF717973),
    outlineVariant = Color(0xFFC1C9C2),
)

private val DarkTones = Tones(
    income = Color(0xFF7EE0A8), spend = Color(0xFFDDE5DF), good = Color(0xFF7EE0A8),
    info = Color(0xFF8CC8F0), warn = Color(0xFFF2C46B), alert = Color(0xFFFF8A80),
    muted = Color(0xFF8A968F), chartPrev = Color(0xFF55615B),
    savings = Color(0xFF8CC8F0), loan = Color(0xFFD7A6F5),
)
private val LightTones = Tones(
    income = Color(0xFF14724A), spend = Color(0xFF171D1A), good = Color(0xFF14724A),
    info = Color(0xFF1F6FA8), warn = Color(0xFFA86A00), alert = Color(0xFFC0362C),
    muted = Color(0xFF6B756F), chartPrev = Color(0xFFB5BEB8),
    savings = Color(0xFF1F6FA8), loan = Color(0xFF8A4FB0),
)

private val Num = FontFamily.Monospace

val Typography.money: TextStyle
    get() = headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

@Composable
fun TallyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalTones provides if (dark) DarkTones else LightTones) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

/** Tabular figures so amounts line up in lists. */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")
