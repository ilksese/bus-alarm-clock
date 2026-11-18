package com.ilksese.busalarmclock.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** D 方向调色板：深石蓝背景、表面、次文、浅柠强调与分隔线。 */
internal val DeepBlue = Color(0xFF233B46)
internal val DeepSurface = Color(0xFF2F4853)
internal val SoftSurface = Color(0xFF354D54)
internal val BodyText = Color(0xFFF3F4E9)
internal val SubText = Color(0xFFBECBCC)
internal val Accent = Color(0xFFD8EEA1)
internal val Hairline = Color(0x26FFFFFF)

private val AppColors = darkColorScheme(
  background = DeepBlue,
  surface = DeepSurface,
  onBackground = BodyText,
  onSurface = BodyText,
  primary = Accent,
  onPrimary = DeepBlue,
  secondary = SoftSurface,
  onSecondary = Accent,
  surfaceVariant = SoftSurface,
  onSurfaceVariant = SubText,
  secondaryContainer = SoftSurface,
  onSecondaryContainer = Accent,
  outlineVariant = Hairline,
)

/** D 方向的深石蓝与浅柠色主题。 */
@Composable
fun BusAlarmClockTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = AppColors, content = content)
}
