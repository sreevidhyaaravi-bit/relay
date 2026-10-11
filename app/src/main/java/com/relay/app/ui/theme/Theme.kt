package com.relay.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Navy / blue base with red reserved for SOS. Contrast checked against WCAG AA for body text.
object RelayColors {
    val Navy900 = Color(0xFF0B1F3A)
    val Navy800 = Color(0xFF102A4C)
    val Navy700 = Color(0xFF16365F)
    val Blue600 = Color(0xFF2A62C9)
    val Blue200 = Color(0xFF9EC5FF)
    val Blue50 = Color(0xFFEAF2FF)
    val Surface = Color(0xFFF5F7FB)
    val Outline = Color(0xFFC5CEDB)
    val TextMuted = Color(0xFF4A5568)

    val Sos = Color(0xFFC62828)
    val SosContainer = Color(0xFFFDECEC)
    val Success = Color(0xFF1B7F3B)
    val SuccessContainer = Color(0xFFE6F4EA)
    val Warning = Color(0xFF9A5B00)
    val WarningContainer = Color(0xFFFFF3E0)
    val Info = Color(0xFF2A62C9)
    val InfoContainer = Color(0xFFEAF2FF)
    val Neutral = Color(0xFF4A5568)
    val NeutralContainer = Color(0xFFEDF0F5)
    val Simulated = Color(0xFF6A3FB5)
    val SimulatedContainer = Color(0xFFF1EAFB)
}

/** Semantic colours for status pills, readable in light and dark themes. */
@Immutable
data class StatusColors(
    val sos: Color, val sosContainer: Color,
    val success: Color, val successContainer: Color,
    val warning: Color, val warningContainer: Color,
    val info: Color, val infoContainer: Color,
    val neutral: Color, val neutralContainer: Color,
    val simulated: Color, val simulatedContainer: Color,
)

private val LightStatus = StatusColors(
    RelayColors.Sos, RelayColors.SosContainer,
    RelayColors.Success, RelayColors.SuccessContainer,
    RelayColors.Warning, RelayColors.WarningContainer,
    RelayColors.Info, RelayColors.InfoContainer,
    RelayColors.Neutral, RelayColors.NeutralContainer,
    RelayColors.Simulated, RelayColors.SimulatedContainer,
)

private val DarkStatus = StatusColors(
    Color(0xFFFF8A80), Color(0xFF4A1414),
    Color(0xFF81C995), Color(0xFF123821),
    Color(0xFFFFCC80), Color(0xFF3D2A00),
    Color(0xFF9EC5FF), Color(0xFF13294B),
    Color(0xFFCBD5E1), Color(0xFF2A3445),
    Color(0xFFD0B8FF), Color(0xFF2E1F4D),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val LightScheme = lightColorScheme(
    primary = RelayColors.Navy800,
    onPrimary = Color.White,
    primaryContainer = RelayColors.Blue50,
    onPrimaryContainer = RelayColors.Navy900,
    secondary = RelayColors.Blue600,
    onSecondary = Color.White,
    background = RelayColors.Surface,
    onBackground = RelayColors.Navy900,
    surface = Color.White,
    onSurface = RelayColors.Navy900,
    surfaceVariant = RelayColors.NeutralContainer,
    onSurfaceVariant = RelayColors.TextMuted,
    outline = RelayColors.Outline,
    error = RelayColors.Sos,
    onError = Color.White,
    errorContainer = RelayColors.SosContainer,
    onErrorContainer = Color(0xFF5F1111),
)

private val DarkScheme = darkColorScheme(
    primary = RelayColors.Blue200,
    onPrimary = RelayColors.Navy900,
    primaryContainer = RelayColors.Navy700,
    onPrimaryContainer = Color.White,
    secondary = RelayColors.Blue200,
    onSecondary = RelayColors.Navy900,
    background = Color(0xFF07152A),
    onBackground = Color(0xFFE6ECF5),
    surface = RelayColors.Navy900,
    onSurface = Color(0xFFE6ECF5),
    surfaceVariant = RelayColors.Navy800,
    onSurfaceVariant = Color(0xFFB8C4D6),
    outline = Color(0xFF3A4B66),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A1414),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val RelayTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospace style for device ids and message ids. */
val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun RelayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = RelayTypography,
            content = content,
        )
    }
}
