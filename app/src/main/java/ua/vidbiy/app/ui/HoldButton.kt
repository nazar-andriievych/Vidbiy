package ua.vidbiy.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Кнопка, що спрацьовує лише після утримання (design-spec 2, NFR-1): «Не дзвонити» на панелі
 * очікування, «Вимкнути». Випадковий дотик уві сні нічого не скасує.
 *
 * Поки палець на кнопці, її заливає прогрес; відпустив раніше — прогрес відкочується,
 * а під кнопкою на кілька секунд з'являється [hint] («Утримуйте секунду, щоб …»). Постійного
 * підпису немає: заповнення саме показує, що треба тримати (design-spec 2).
 * Якщо кнопка вузька, підказку показує власник через [onHintVisibleChange] ([showHint] = false).
 * Для TalkBack утримання незручне, тому дія доступна ще й як окрема accessibility-дія.
 */
@Composable
fun HoldButton(
    text: String,
    hint: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    accent: Boolean = false,
    holdMillis: Int = 1_000,
    height: Dp = Dimens.ButtonHeight,
    showHint: Boolean = true,
    onHintVisibleChange: (Boolean) -> Unit = {},
) {
    val border = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val content = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirmed)
    val fill = MaterialTheme.colorScheme.secondaryContainer
    // Лічильник коротких натисків: кожен новий перезапускає таймер підказки.
    var shortPresses by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(false) }
    val hintChanged by rememberUpdatedState(onHintVisibleChange)
    LaunchedEffect(shortPresses) {
        if (shortPresses == 0) return@LaunchedEffect
        hintVisible = true
        hintChanged(true)
        delay(HINT_MILLIS)
        hintVisible = false
        hintChanged(false)
    }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(CircleShape)
                .border(if (accent) 1.5.dp else 1.dp, border, CircleShape)
                .drawBehind {
                    drawRect(fill, size = Size(size.width * progress.value, size.height))
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            val job = scope.launch {
                                progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                confirm()
                            }
                            tryAwaitRelease()
                            if (progress.value < 1f) {
                                job.cancel()
                                shortPresses++
                                scope.launch { progress.animateTo(0f, tween(200)) }
                            }
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    stateDescription = hint
                    customActions = listOf(CustomAccessibilityAction(text) { confirm(); true })
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                FittingLabel(text, content)
            }
        }
        AnimatedVisibility(visible = showHint && hintVisible) {
            FieldHint(hint, Modifier.padding(top = 8.dp))
        }
    }
}

private const val HINT_MILLIS = 4_000L
