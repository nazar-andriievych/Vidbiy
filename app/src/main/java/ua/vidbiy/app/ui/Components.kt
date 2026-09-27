package ua.vidbiy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R
import ua.vidbiy.app.data.Place
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
private fun Dot(color: Color, size: Int) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** Підпис над полем: 14/500, сірий (design-spec 1.2, labelMedium). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Пояснення під полем: 14/400, сірий. */
@Composable
fun FieldHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Круглий чип вибору (дні, хвилини): контур outline; вибраний — заповнений primary. */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.size(Dimens.ChipHeight - 2.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Рядок-значення (регіон, крайній час): кружечок 44 з іконкою, над значенням сірий підпис,
 * значення 16/600, праворуч [trailing] — шеврон, × або +.
 */
@Composable
fun ValueRow(
    icon: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = Dimens.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.IconCircle)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FieldLabel(label)
            Text(value, style = MaterialTheme.typography.titleSmall)
            if (detail != null) FieldHint(detail)
        }
        trailing()
    }
}

/** Інфоблок: іконка «i», текст 14, фон surfaceContainer, радіус 20. */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            FieldHint(text)
        }
    }
}

/**
 * Діалог назви місця — для нового місця (крок 2) і для перейменування (FR-26a):
 * до [Place.MAX_NAME_LENGTH] символів, порожня назва не зберігається.
 */
@Composable
fun PlaceNameDialog(
    title: String,
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.action_cancel),
    stepLabel: String? = null,
    regionLabel: String? = null,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val valid = name.isNotBlank()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (stepLabel != null) FieldLabel(stepLabel)
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (regionLabel != null) FieldHint(regionLabel)
            }
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(Place.MAX_NAME_LENGTH) },
                label = { Text(stringResource(R.string.place_name_label)) },
                supportingText = { Text(stringResource(R.string.place_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = valid) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
