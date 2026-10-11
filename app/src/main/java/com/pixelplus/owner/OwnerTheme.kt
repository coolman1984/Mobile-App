package com.pixelplus.owner

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    CompositionLocalProvider(LocalDensity provides Density(density.density, appearance.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
