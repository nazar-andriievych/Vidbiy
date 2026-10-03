package ua.vidbiy.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val VidbiyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // текстове поле (outlined)
    small = RoundedCornerShape(16.dp),       // меню, рядки списку, extended FAB
    medium = RoundedCornerShape(20.dp),      // банери, інфоблоки, підтвердження
    large = RoundedCornerShape(24.dp),       // картки будильників і місць
    extraLarge = RoundedCornerShape(28.dp),  // картки-секції (налаштування), діалоги
)

// Кнопки, чипи, сегменти й перемикач днів — повністю округлі (CircleShape / 50%).

object Dimens {
    val ScreenPadding = 16.dp        // горизонтальний відступ контенту
    val CardPadding = 20.dp          // внутрішній відступ картки
    val ListGap = 12.dp              // між картками
    val SectionGap = 20.dp           // між блоками всередині картки-секції
    val TouchTarget = 48.dp          // мінімум для натискних елементів
    val ButtonHeight = 56.dp         // основні кнопки внизу екрана
    val SnoozeButtonHeight = 64.dp   // «Відкласти» на екрані дзвінка
    val ChipHeight = 48.dp           // чипи вибору (дні, хвилини)
    val SegmentHeight = 52.dp        // перемикачі на 2–3 сегменти
    val StatusChipHeight = 36.dp     // чип рівня тривоги
    val IconCircle = 44.dp           // кружечок з іконкою в рядку (регіон, крайній час)
    val SettingRowHeight = 56.dp     // рядок налаштування «назва · значення»
}
