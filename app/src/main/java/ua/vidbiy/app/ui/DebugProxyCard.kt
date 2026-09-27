package ua.vidbiy.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.R

/**
 * Адреса проксі для локальної розробки. Показується лише в debug-збірці:
 * із нею можна ганяти тривогу й відбій вручну через mock-режим воркера,
 * не чекаючи справжніх подій.
 */
@Composable
fun DebugProxyCard(url: String, onUrlChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // Поле тримає власний текст: збережене значення повертається зі сховища асинхронно,
    // і якби поле брало його напряму, швидке введення губило б і перемішувало символи.
    // null — ще не редагували, показуємо збережене.
    var edited by rememberSaveable { mutableStateOf<String?>(null) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.debug_proxy_title),
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = edited ?: url,
                onValueChange = {
                    edited = it
                    onUrlChange(it)
                },
                placeholder = { Text(stringResource(R.string.debug_proxy_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
