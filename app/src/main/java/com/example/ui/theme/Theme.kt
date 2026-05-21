package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = NaturalAccentGreen,
    secondary = NaturalSageContainer,
    tertiary = NaturalLogGreen,
    background = NaturalDarkSoot,
    surface = NaturalDarkSoot,
    onPrimary = NaturalDarkSoot,
    onSecondary = NaturalDarkSoot,
    onBackground = NaturalBg,
    onSurface = NaturalBg,
    surfaceVariant = NaturalSageContainer,
    outline = NaturalBorder
  )

private val LightColorScheme =
  lightColorScheme(
    primary = NaturalPrimary,
    secondary = NaturalSageContainer,
    tertiary = NaturalAccentGreen,
    background = NaturalBg,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = NaturalDarkText,
    onTertiary = NaturalDarkText,
    onBackground = NaturalDarkText,
    onSurface = NaturalDarkText,
    surfaceVariant = NaturalSageContainer,
    outline = NaturalBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
