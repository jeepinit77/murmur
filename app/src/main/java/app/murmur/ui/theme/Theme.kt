package app.murmur.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.container

/** Whether the phone can use colors from the wallpaper. */
val wallpaperColorsAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** Whether the app is showing its dark theme, given the Theme setting. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun MurmurTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = context.container.settings
    val mode by settings.themeMode.collectAsStateWithLifecycle()
    val palette by settings.palette.collectAsStateWithLifecycle()
    val black by settings.blackBackground.collectAsStateWithLifecycle()
    val dark = mode.isDark()
    val scheme = remember(palette, dark, black) {
        if (palette == Palette.WALLPAPER && wallpaperColorsAvailable) {
            if (dark) dynamicDarkColorScheme(context).let { if (black) it.withBlackBackground() else it }
            else dynamicLightColorScheme(context)
        } else {
            schemeFor(if (palette == Palette.WALLPAPER) Palette.VIOLET else palette, dark, black)
        }
    }
    // Status and navigation bar icons follow the app's theme, which can differ from the system's.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = MurmurTypography, shapes = MurmurShapes, content = content)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
