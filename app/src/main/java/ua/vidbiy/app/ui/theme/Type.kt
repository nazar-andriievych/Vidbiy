package ua.vidbiy.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ua.vidbiy.app.R

// Шрифт Onest (OFL) — вшити в застосунок, а не тягнути через Downloadable Fonts:
// будильник має працювати без мережі. Статичні TTF — із завантаження на fonts.google.com,
// покласти в res/font як onest_regular.ttf, onest_medium.ttf, onest_semibold.ttf, onest_bold.ttf.
val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

// Шкала: 14 / 16 / 18 / 22 / 28 + окремі розміри для часу. 1 px у макеті = 1 dp/sp.
val VidbiyTypography = Typography(
    // Час: Onest 400, пропорційні цифри (НЕ tabular), щільний трекінг.
    displayLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 88.sp, lineHeight = 92.sp, letterSpacing = (-0.04).em),  // редагування, екран дзвінка
    displayMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 56.sp, lineHeight = 59.sp, letterSpacing = (-0.04).em), // картка будильника
    displaySmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 52.sp, lineHeight = 56.sp),                             // поля годин/хвилин у виборі часу

    headlineMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em), // заголовки вкладок
    titleLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),    // підекрани, діалоги, заголовки станів
    titleMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp), // заголовки карток-секцій
    titleSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),  // заголовки рядків списку

    bodyLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),    // вторинний текст, підказки

    labelLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),  // кнопки
    labelMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),   // підписи полів, підписи навігації
    labelSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),  // чипи рівня тривоги
)
