package ua.vidbiy.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Токени з дизайну «Відбій — Android». Назви в коментарях — як у .dc.html (pal()).

// ── Світла тема ─────────────────────────────────────────────
val LightBackground = Color(0xFFF7F5F0)          // bg
val LightSurface1 = Color(0xFFEFECE5)            // s1 — картки, нижня навігація
val LightSurface2 = Color(0xFFE6E2DA)            // s2 — діалоги, меню, кружечки іконок
val LightSurface3 = Color(0xFFDAD6CC)            // s3 — трек вимкненого перемикача
val LightOnSurface = Color(0xFF1B1C1F)           // text
val LightOnSurfaceVariant = Color(0xFF50525A)    // text2 — вторинний текст, контури кнопок
val LightDivider = Color(0xFFD3CFC5)             // outline — роздільники
val LightPrimary = Color(0xFF35598F)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD8E3F7)    // pc — тональні кнопки, вибрані сегменти
val LightOnPrimaryContainer = Color(0xFF0F2A4E)  // onPc

// ── Темна тема ──────────────────────────────────────────────
val DarkBackground = Color(0xFF101318)
val DarkSurface1 = Color(0xFF1A1E25)
val DarkSurface2 = Color(0xFF232830)
val DarkSurface3 = Color(0xFF2E343E)
val DarkOnSurface = Color(0xFFE5E3DE)
val DarkOnSurfaceVariant = Color(0xFFAAADB4)
val DarkDivider = Color(0xFF2C323B)
val DarkPrimary = Color(0xFFA9C6F3)
val DarkOnPrimary = Color(0xFF0D2A4F)
val DarkPrimaryContainer = Color(0xFF284470)
val DarkOnPrimaryContainer = Color(0xFFD8E3F7)

// ── Кольори тривог (поза Material ColorScheme) ─────────────
@Immutable
data class AlertColors(
    val red: Color,              // крапка «червона»
    val redContainer: Color,     // фон банера/чипа червоної тривоги
    val onRedContainer: Color,
    val redText: Color,          // текст рівня на нейтральному фоні (сповіщення)
    val yellow: Color,           // крапка «жовта»
    val yellowContainer: Color,  // фон банера/чипа жовтої тривоги, попередження «немає зв'язку»
    val onYellowContainer: Color,
    val yellowText: Color,
    val warningText: Color,      // «Оновлено 2 хв тому» у стані «дані старіють»
)

val LightAlertColors = AlertColors(
    red = Color(0xFFC23A2E),
    redContainer = Color(0xFFF9DCD7),
    onRedContainer = Color(0xFF7A1C15),
    redText = Color(0xFF9E2A20),
    yellow = Color(0xFFA67C00),
    yellowContainer = Color(0xFFFBEAB0),
    onYellowContainer = Color(0xFF4C3900),
    yellowText = Color(0xFF6B5000),
    warningText = Color(0xFF7A5A00),
)

val DarkAlertColors = AlertColors(
    red = Color(0xFFFF8A7E),
    redContainer = Color(0xFF571B16),
    onRedContainer = Color(0xFFFFB4A9),
    redText = Color(0xFFFFB4A9),
    yellow = Color(0xFFEFC845),
    yellowContainer = Color(0xFF433500),
    onYellowContainer = Color(0xFFF8DE86),
    yellowText = Color(0xFFF8DE86),
    warningText = Color(0xFFEFC845),
)

val LocalAlertColors = staticCompositionLocalOf { LightAlertColors }
