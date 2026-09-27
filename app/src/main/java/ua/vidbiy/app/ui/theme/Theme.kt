package ua.vidbiy.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Потрібен androidx.compose.material3 ≥ 1.2 (ролі surfaceContainer*).
// Dynamic color (Material You) навмисно вимкнений: кольори тривог мають бути передбачуваними.

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightPrimary,
    onSecondary = LightOnPrimary,
    secondaryContainer = LightPrimaryContainer,       // індикатор NavigationBar, вибрані сегменти
    onSecondaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface1,
    surfaceContainer = LightSurface1,                 // картки, NavigationBar
    surfaceContainerHigh = LightSurface2,             // діалоги, меню
    surfaceContainerHighest = LightSurface3,          // трек вимкненого Switch
    outline = LightOnSurfaceVariant,                  // контури outlined-кнопок і чипів
    outlineVariant = LightDivider,                    // роздільники
    error = LightAlertColors.red,
    errorContainer = LightAlertColors.redContainer,
    onErrorContainer = LightAlertColors.onRedContainer,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkPrimary,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkPrimaryContainer,
    onSecondaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface1,
    surfaceContainer = DarkSurface1,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    outline = DarkOnSurfaceVariant,
    outlineVariant = DarkDivider,
    error = DarkAlertColors.red,
    errorContainer = DarkAlertColors.redContainer,
    onErrorContainer = DarkAlertColors.onRedContainer,
)

/** Налаштування «Тема» на екрані «Налаштування». За замовчуванням — System. */
enum class ThemeMode { System, Light, Dark }

@Composable
fun VidbiyTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // Значки статус-бару й навігації мають бути темними на світлій темі й світлими на темній.
    // enableEdgeToEdge() орієнтується на тему системи, тож коли в застосунку обрано іншу — поправляємо.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalAlertColors provides if (dark) DarkAlertColors else LightAlertColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = VidbiyTypography,
            shapes = VidbiyShapes,
            content = content,
        )
    }
}

/** Доступ: MaterialTheme.alertColors.redContainer */
val MaterialTheme.alertColors: AlertColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAlertColors.current
