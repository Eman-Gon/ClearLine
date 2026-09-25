package com.clearline.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ClearLineColors = lightColorScheme(
    primary = Color(0xFF2D5748), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EEE4), onPrimaryContainer = Color(0xFF263C35),
    secondary = Color(0xFF718474), secondaryContainer = Color(0xFFEDE9DD),
    background = Color(0xFFF5F4EF), onBackground = Color(0xFF263C35),
    surface = Color(0xFFFFFEFA), onSurface = Color(0xFF263C35),
    surfaceVariant = Color(0xFFE8EEE4), onSurfaceVariant = Color(0xFF536455),
    outline = Color(0xFFC8D2C2), error = Color(0xFF865539),
)
@Composable fun ClearLineTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClearLineColors, typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 39.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 33.sp),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 1.sp),
    ), content = content)
}
