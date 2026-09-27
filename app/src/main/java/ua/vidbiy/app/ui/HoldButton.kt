package ua.vidbiy.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Кнопка, що спрацьовує лише після утримання (design-spec 2, NFR-1): «Сьогодні не дзвони»,
 * «Вимкнути». Випадковий дотик уві сні нічого не скасує.
 *
 * Поки палець на кнопці, її заливає прогрес; відпустив раніше — прогрес відкочується.
 * Для TalkBack утримання незручне, тому дія доступна ще й як окрема accessibility-дія.
 */
@Composable
fun HoldButton(
    text: String,
    hint: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    holdMillis: Int = 1_000,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirmed)
    val fill = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.ButtonHeight)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
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
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
