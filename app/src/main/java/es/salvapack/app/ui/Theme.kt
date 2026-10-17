package es.salvapack.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Forest = Color(0xFF284B37)
val Cream = Color(0xFFF8F8F0)
val Sage = Color(0xFFE3EACF)
val Ink = Color(0xFF203529)
val Muted = Color(0xFF677166)
val Amber = Color(0xFF805216)
val PaleAmber = Color(0xFFFFEECF)
val Danger = Color(0xFFA3352B)
val PaleDanger = Color(0xFFFBE6DF)

@Composable
fun SalvaPackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest, onPrimary = Color.White,
            primaryContainer = Sage, onPrimaryContainer = Ink,
            secondary = Color(0xFF596744), secondaryContainer = Sage,
            background = Cream, onBackground = Ink,
            surface = Cream, onSurface = Ink,
            surfaceVariant = Color(0xFFEBEEE2), onSurfaceVariant = Muted,
            outline = Color(0xFF7E8978), outlineVariant = Color(0xFFDCE2D3),
            error = Danger, errorContainer = PaleDanger
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 39.sp, letterSpacing = (-1).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
        ), content = content
    )
}
