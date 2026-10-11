package com.pixelplus.owner

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.pixelplus.owner.ui.theme.ResolvedAppearance

/** Draws the decisions made in `ui/theme` (pure, tested): the chosen palette or the phone's dynamic colours, and the font size. */
@Composable
internal fun OwnerTheme(appearance: ResolvedAppearance, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val s = appearance.scheme
    val scheme = when {
        appearance.useDynamic && appearance.dark -> dynamicDarkColorScheme(context)
        appearance.useDynamic -> dynamicLightColorScheme(context)
        appearance.dark -> darkColorScheme(
            primary = Color(s.primary), onPrimary = Color(s.onPrimary), background = Color(s.background), onBackground = Color(s.onBackground),
            surface = Color(s.surface), onSurface = Color(s.onSurface), error = Color(s.error), onError = Color(s.onError),
        )
        else -> lightColorScheme(
            primary = Color(s.primary), onPrimary = Color(s.onPrimary), background = Color(s.background), onBackground = Color(s.onBackground),
            surface = Color(s.surface), onSurface = Color(s.onSurface), error = Color(s.error), onError = Color(s.onError),
        )
    }
    val density = LocalDensity.current
    val family = if (appearance.bundledFont == "cairo") cairoFamily() else null
    CompositionLocalProvider(LocalDensity provides Density(density.density, appearance.fontScale)) {
        MaterialTheme(colorScheme = scheme, typography = if (family == null) Typography() else withFamily(Typography(), family)) {
            if (family == null) content() else ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = family), content)
        }
    }
}

/** Cairo is one variable font file; each weight is the same file with a different weight axis (Android 8 and up, the app's minimum). */
@OptIn(ExperimentalTextApi::class)
private fun cairoFamily(): FontFamily = FontFamily(
    listOf(400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold, 700 to FontWeight.Bold).map { (axis, weight) ->
        Font(R.font.cairo, weight, variationSettings = FontVariation.Settings(FontVariation.weight(axis)))
    },
)

private fun withFamily(t: Typography, f: FontFamily): Typography = Typography(
    displayLarge = t.displayLarge.copy(fontFamily = f), displayMedium = t.displayMedium.copy(fontFamily = f), displaySmall = t.displaySmall.copy(fontFamily = f),
    headlineLarge = t.headlineLarge.copy(fontFamily = f), headlineMedium = t.headlineMedium.copy(fontFamily = f), headlineSmall = t.headlineSmall.copy(fontFamily = f),
    titleLarge = t.titleLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f), titleSmall = t.titleSmall.copy(fontFamily = f),
    bodyLarge = t.bodyLarge.copy(fontFamily = f), bodyMedium = t.bodyMedium.copy(fontFamily = f), bodySmall = t.bodySmall.copy(fontFamily = f),
    labelLarge = t.labelLarge.copy(fontFamily = f), labelMedium = t.labelMedium.copy(fontFamily = f), labelSmall = t.labelSmall.copy(fontFamily = f),
)
