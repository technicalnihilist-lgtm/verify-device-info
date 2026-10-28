package com.verifydeviceinfo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.verifydeviceinfo.core.model.DiagnosticStatus
import com.verifydeviceinfo.core.model.ScoreBand

/**
 * Diagnostic-instrument palette: deep neutral surfaces so status colours carry all the
 * meaning, with a distinct light theme that keeps the same semantics.
 */
object DiagnosticColors {
    val Verified = Color(0xFF22C55E)
    val Pass = Color(0xFF34D399)
    val Warning = Color(0xFFF59E0B)
    val Failed = Color(0xFFEF4444)
    val Unverified = Color(0xFF94A3B8)
    val NotSupported = Color(0xFF64748B)
    val Permission = Color(0xFF818CF8)
    val Accent = Color(0xFF38BDF8)
    val AccentDeep = Color(0xFF0EA5E9)
    val SurfaceDark = Color(0xFF0B1220)
    val SurfaceDarkElevated = Color(0xFF141D2E)
    val SurfaceLight = Color(0xFFF7F9FC)
    val SurfaceLightElevated = Color(0xFFFFFFFF)
}

fun statusColor(status: DiagnosticStatus): Color = when (status) {
    DiagnosticStatus.VERIFIED -> DiagnosticColors.Verified
    DiagnosticStatus.PASS -> DiagnosticColors.Pass
    DiagnosticStatus.WARNING -> DiagnosticColors.Warning
    DiagnosticStatus.FAILED -> DiagnosticColors.Failed
    DiagnosticStatus.UNVERIFIED -> DiagnosticColors.Unverified
    DiagnosticStatus.NOT_SUPPORTED -> DiagnosticColors.NotSupported
    DiagnosticStatus.PERMISSION_REQUIRED -> DiagnosticColors.Permission
}

fun bandColor(band: ScoreBand): Color = when (band) {
    ScoreBand.GOOD -> DiagnosticColors.Verified
    ScoreBand.FAIR -> DiagnosticColors.Warning
    ScoreBand.POOR -> DiagnosticColors.Failed
    ScoreBand.UNKNOWN -> DiagnosticColors.Unverified
}

fun statusLabel(status: DiagnosticStatus): String = when (status) {
    DiagnosticStatus.VERIFIED -> "VERIFIED"
    DiagnosticStatus.PASS -> "PASS"
    DiagnosticStatus.WARNING -> "WARNING"
    DiagnosticStatus.FAILED -> "FAILED"
    DiagnosticStatus.UNVERIFIED -> "UNVERIFIED"
    DiagnosticStatus.NOT_SUPPORTED -> "NOT SUPPORTED"
    DiagnosticStatus.PERMISSION_REQUIRED -> "PERMISSION"
}

private val DarkScheme = darkColorScheme(
    primary = DiagnosticColors.Accent,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF0B2C42),
    onPrimaryContainer = Color(0xFFBFE7FF),
    secondary = Color(0xFF9FB3C8),
    onSecondary = Color(0xFF0B1220),
    background = DiagnosticColors.SurfaceDark,
    onBackground = Color(0xFFE6EDF7),
    surface = DiagnosticColors.SurfaceDark,
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = DiagnosticColors.SurfaceDarkElevated,
    onSurfaceVariant = Color(0xFFA9B8CC),
    outline = Color(0xFF2A3B52),
    error = DiagnosticColors.Failed
)

private val LightScheme = lightColorScheme(
    primary = DiagnosticColors.AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    background = DiagnosticColors.SurfaceLight,
    onBackground = Color(0xFF0F172A),
    surface = DiagnosticColors.SurfaceLightElevated,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEDF2F7),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = DiagnosticColors.Failed
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun VerifyDeviceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}

/** Standard horizontal page padding used by every screen. */
val ScreenPadding = 16.dp
