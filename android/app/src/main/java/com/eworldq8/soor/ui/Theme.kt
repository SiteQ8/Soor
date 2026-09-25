package com.eworldq8.soor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.eworldq8.soor.R
import com.eworldq8.soor.engine.Severity

// The Soor identity: Kuwait navy with a warm gold signal on a deep field, set in
// Readex Pro, the same typeface as the site and the iPhone app, with IBM Plex
// Mono for addresses and ports. Both fonts ship inside the app under the SIL
// Open Font License, whose text is in assets/licenses.

object Theme {
    val navy = Color(0xFF0033A0)
    val navyBright = Color(0xFF1747B8)
    val navyLite = Color(0xFF4C70BC)
    val signal = Color(0xFFE8A13A)
    val bg = Color(0xFF05060E)
    val bg2 = Color(0xFF0A1024)
    val panel = Color(0xFF0F1730)
    val panel2 = Color(0xFF16214A)
    val ink = Color(0xFFEAF0FB)
    val ink2 = Color(0xFFA7B4D4)
    val ink3 = Color(0xFF7584AD)
    val rule = Color(0xFF23305A)
    val ok = Color(0xFF52C48D)
    val crit = Color(0xFFFF6B5B)
    val high = Color(0xFFF0904A)
    val med = Color(0xFFE8C34A)
    val info = Color(0xFF6FA8E8)

    fun severity(s: Severity?): Color = when (s) {
        Severity.CRITICAL -> crit
        Severity.HIGH -> high
        Severity.MEDIUM -> med
        Severity.LOW, Severity.INFO -> info
        null -> ok
    }
}

val Readex = FontFamily(
    Font(R.font.readex_pro_regular, FontWeight.Normal),
    Font(R.font.readex_pro_medium, FontWeight.Medium),
    Font(R.font.readex_pro_semibold, FontWeight.SemiBold),
    Font(R.font.readex_pro_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private val base = Typography()
private val SoorTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Readex),
    displayMedium = base.displayMedium.copy(fontFamily = Readex),
    displaySmall = base.displaySmall.copy(fontFamily = Readex),
    headlineLarge = base.headlineLarge.copy(fontFamily = Readex),
    headlineMedium = base.headlineMedium.copy(fontFamily = Readex),
    headlineSmall = base.headlineSmall.copy(fontFamily = Readex),
    titleLarge = base.titleLarge.copy(fontFamily = Readex),
    titleMedium = base.titleMedium.copy(fontFamily = Readex),
    titleSmall = base.titleSmall.copy(fontFamily = Readex),
    bodyLarge = base.bodyLarge.copy(fontFamily = Readex),
    bodyMedium = base.bodyMedium.copy(fontFamily = Readex),
    bodySmall = base.bodySmall.copy(fontFamily = Readex),
    labelLarge = base.labelLarge.copy(fontFamily = Readex),
    labelMedium = base.labelMedium.copy(fontFamily = Readex),
    labelSmall = base.labelSmall.copy(fontFamily = Readex),
)

@Composable
fun SoorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Theme.navy, onPrimary = Color.White,
            secondary = Theme.signal, onSecondary = Theme.bg,
            background = Theme.bg, onBackground = Theme.ink,
            surface = Theme.panel, onSurface = Theme.ink,
            surfaceVariant = Theme.panel2, onSurfaceVariant = Theme.ink2,
            outline = Theme.rule,
        ),
        typography = SoorTypography,
        content = content,
    )
}
