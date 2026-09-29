package ua.vidbiy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Скільки рядків барабана видно одночасно (у діалозі); вибраний — посередині. */
const val WHEEL_VISIBLE_ROWS = 5
val WheelRowHeight: Dp = 48.dp

/**
 * Барабан чисел 0 until [count] (години — 24, хвилини — 60), що прокручується по колу,
 * як у годиннику Samsung. Після прокрутки сам зупиняється на найближчому числі.
 *
 * «Коло» — це просто дуже довгий список, де число = індекс mod [count], а старт — посередині:
 * докрутити до краю ніхто не встигне. Вибране число — те, чий рядок у центрі; [onValueChange]
 * отримує його під час прокрутки, тож «Готово» посеред руху бере те, що видно в центрі.
 *
 * Для TalkBack увесь барабан — один елемент-повзунок: читає «[description], 07», а свайпи
 * вгору/вниз змінюють значення (setProgress). Тисячі рядків списку від нього сховані.
 */
@Composable
fun NumberWheel(
    count: Int,
    value: Int,
    onValueChange: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    width: Dp = 88.dp,
    visibleRows: Int = WHEEL_VISIBLE_ROWS,
    rowHeight: Dp = WheelRowHeight,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = wheelTopIndex(value, count, visibleRows))
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val center by remember(state) { derivedStateOf { state.centerIndex() } }

    LaunchedEffect(state, count) {
        snapshotFlow { center }
            .distinctUntilChanged()
            .drop(1) // початкове положення — не зміна
            .collect { index ->
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                currentOnValueChange(wheelValue(index, count))
            }
    }

    Box(
        modifier = modifier
            .width(width)
            .height(rowHeight * visibleRows)
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = formatTwoDigits(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..(count - 1).toFloat(), steps = count - 2)
                setProgress { target ->
                    val delta = target.roundToInt().coerceIn(0, count - 1) - value
                    scope.launch { state.scrollToItem(state.firstVisibleItemIndex + delta) }
                    true
                }
            },
    ) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Center),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count * WHEEL_LOOPS) { index ->
                val distance = abs(index - center)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .clickable(enabled = distance > 0) {
                            scope.launch { state.animateScrollToItem(index - visibleRows / 2) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatTwoDigits(wheelValue(index, count)),
                        style = textStyle,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = when (distance) {
                            0 -> MaterialTheme.colorScheme.onSurface
                            // Сусіди помітно блідіші, як у Samsung: вибране число має читатися одразу.
                            1 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        },
                    )
                }
            }
        }
    }
}

/** Скільки разів повторюються числа: 24 × 400 рядків — прокрутити до краю неможливо. */
private const val WHEEL_LOOPS = 400

/** Число, що стоїть у рядку [index]. */
fun wheelValue(index: Int, count: Int): Int = index.mod(count)

/** Індекс верхнього видимого рядка, щоб [value] опинилося в центрі, приблизно посередині списку. */
fun wheelTopIndex(value: Int, count: Int, visibleRows: Int = WHEEL_VISIBLE_ROWS): Int =
    count * (WHEEL_LOOPS / 2) + value - visibleRows / 2

/** Індекс рядка, чий центр найближчий до центру барабана. */
private fun LazyListState.centerIndex(): Int {
    val info = layoutInfo
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }?.index
        ?: firstVisibleItemIndex
}

private fun formatTwoDigits(value: Int): String = value.toString().padStart(2, '0')
