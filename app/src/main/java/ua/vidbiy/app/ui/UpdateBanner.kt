package ua.vidbiy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.vidbiy.app.BuildConfig
import ua.vidbiy.app.R
import ua.vidbiy.app.data.AppUpdate

/** Що сказати про оновлення на головному екрані. */
enum class UpdateNotice {
    NONE,

    /** Є новіша версія; банер можна закрити до наступного випуску. */
    AVAILABLE,

    /** Ця версія застаріла й тривог не чекає (docs/proxy-api.md): банер не закривається. */
    REQUIRED,
}

/**
 * [dismissedCode] — випуск, банер якого закрили. Застарілість закрити не можна: поки людина
 * не оновиться, будильники дзвонять без перевірки тривоги, і вона має про це знати.
 */
fun updateNotice(update: AppUpdate?, versionCode: Int, dismissedCode: Int): UpdateNotice = when {
    update == null -> UpdateNotice.NONE
    update.isRequiredFor(versionCode) -> UpdateNotice.REQUIRED
    update.isNewerThan(versionCode) && update.latestVersionCode > dismissedCode -> UpdateNotice.AVAILABLE
    else -> UpdateNotice.NONE
}

/**
 * Банер оновлення на головному екрані — тієї ж форми, що й «Будильник може не задзвонити».
 * Червоним його не робимо: червоний у застосунку означає ракетну загрозу. У макеті цього стану немає.
 */
@Composable
fun UpdateBanner(
    update: AppUpdate,
    notice: UpdateNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice == UpdateNotice.NONE) return
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (notice == UpdateNotice.REQUIRED) {
                        Text(stringResource(R.string.update_required_title), style = MaterialTheme.typography.titleSmall)
                        FieldHint(stringResource(R.string.update_required_text))
                    } else {
                        Text(
                            stringResource(R.string.update_banner_title, update.latestVersionName),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FieldHint(stringResource(R.string.update_banner_text, BuildConfig.VERSION_NAME))
                    }
                    if (update.url == null) FieldHint(stringResource(R.string.update_no_link))
                }
                if (notice == UpdateNotice.AVAILABLE) {
                    IconButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.update_dismiss),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            update.url?.let { url ->
                Button(onClick = { runCatching { uriHandler.openUri(url) } }) {
                    Text(stringResource(R.string.update_download), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Рядок «Версія» на вкладці «Налаштування»: видно завжди, навіть коли банер закрили.
 * Натиск відкриває сторінку завантаження, якщо є новіша версія й відома адреса.
 */
@Composable
fun VersionRow(update: AppUpdate?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val newer = update?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
    val url = newer?.url
    SettingsCard(modifier = modifier) {
        SettingRow(
            title = stringResource(R.string.version_title),
            value = when {
                newer != null -> stringResource(R.string.version_available, BuildConfig.VERSION_NAME, newer.latestVersionName)
                // «Актуальна» — лише коли проксі справді сказав, яка версія остання.
                update != null -> stringResource(R.string.version_current, BuildConfig.VERSION_NAME)
                else -> BuildConfig.VERSION_NAME
            },
            onClick = url?.let { { runCatching { uriHandler.openUri(it) } } },
        )
    }
}
