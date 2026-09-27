package ua.vidbiy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.data.WaitFor
import ua.vidbiy.app.ui.theme.Dimens
import ua.vidbiy.app.ui.theme.alertColors

/** Заголовок вкладки: «Будильники», «Мої місця», «Налаштування» (design-spec 1.2, headlineMedium). */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Картка-секція налаштувань: радіус 28, фон surfaceContainer, відступ 20. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(Dimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * Крапки рівня: «будь-яка» — червона й жовта, «лише червона» — одна червона.
 * Колір ніколи не єдиний носій змісту: поруч завжди має бути текст (design-spec 1.1).
 */
@Composable
fun LevelDots(waitFor: WaitFor, modifier: Modifier = Modifier, size: Int = 10) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Dot(MaterialTheme.alertColors.red, size)
        if (waitFor == WaitFor.RED_AND_YELLOW) Dot(MaterialTheme.alertColors.yellow, size)
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}
