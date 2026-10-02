package ua.vidbiy.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import ua.vidbiy.app.R
import ua.vidbiy.app.alarm.AlarmScheduler
import ua.vidbiy.app.ui.theme.Dimens

/**
 * Чотири системні дозволи, без яких будильник може не задзвонити (NFR-2, FR-33).
 * Перевіряємо їх щоразу, коли застосунок повертається на екран: користувач міг
 * змінити їх у системних налаштуваннях, нічого нам не сказавши.
 */
enum class Permission(val icon: Int, val title: Int, val text: Int, val missing: Int) {
    NOTIFICATIONS(
        R.drawable.ic_notifications,
        R.string.perm_notifications_title,
        R.string.perm_notifications_text,
        R.string.perm_notifications_missing,
    ),

    /**
     * Точні будильники. Застосунок-будильник отримує їх автоматично через USE_EXACT_ALARM
     * (Android 13+), тож зазвичай тут уже «Надано»; картка лишається на випадок, якщо
     * виробник цього не зробив (відкрите питання: перевірити на Samsung).
     */
    EXACT_ALARMS(
        R.drawable.ic_alarm,
        R.string.perm_exact_title,
        R.string.perm_exact_text,
        R.string.perm_exact_missing,
    ),

    /** Показ поверх блокування (USE_FULL_SCREEN_INTENT). З Android 14 його можна відкликати. */
    FULL_SCREEN(
        R.drawable.ic_lock,
        R.string.perm_full_screen_title,
        R.string.perm_full_screen_text,
        R.string.perm_full_screen_missing,
    ),
    BATTERY(
        R.drawable.ic_battery_full,
        R.string.perm_battery_title,
        R.string.perm_battery_text,
        R.string.perm_battery_missing,
    ),
}

fun isGranted(context: Context, permission: Permission): Boolean = when (permission) {
    Permission.NOTIFICATIONS -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    Permission.EXACT_ALARMS -> AlarmScheduler(context).canScheduleExact()
    Permission.FULL_SCREEN ->
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    Permission.BATTERY -> context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

/** Які дозволи бракують; оновлюється щоразу, коли екран повертається на передній план. */
@Composable
fun rememberMissingPermissions(): List<Permission> {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(emptyList<Permission>()) }
    LifecycleResumeEffect(Unit) {
        missing = Permission.entries.filterNot { isGranted(context, it) }
        onPauseOrDispose { }
    }
    return missing
}

/** Системний екран, де людина може надати дозвіл. */
private fun settingsIntent(context: Context, permission: Permission): Intent? {
    val pkg = Uri.fromParts("package", context.packageName, null)
    return when (permission) {
        Permission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        Permission.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
        } else {
            null
        }
        Permission.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
        } else {
            null
        }
        // Список застосунків в оптимізації батареї: прямий запит «дозволити» потребує
        // окремого дозволу, який магазини застосунків дозволяють неохоче.
        Permission.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}

/**
 * Банер «Будильник може не задзвонити» (design-spec 2): контур, причина, кнопка
 * «Надати дозвіл» → екран дозволів. Показується, якщо бракує хоч одного дозволу.
 */
@Composable
fun PermissionsBanner(missing: List<Permission>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val first = missing.firstOrNull() ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.perm_banner_title), style = MaterialTheme.typography.titleSmall)
                FieldHint(
                    if (missing.size > 1) {
                        stringResource(R.string.perm_banner_more, stringResource(first.missing), missing.size - 1)
                    } else {
                        stringResource(first.missing)
                    }
                )
            }
            Button(onClick = onOpen) {
                Text(stringResource(R.string.perm_banner_action), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Банер «Не вдалося прочитати будильники»: частину збережених будильників не вдалося розібрати
 * (наприклад, після оновлення), і їх викинуто. Тієї ж форми, що й банер дозволів, але без кнопки:
 * зникає сам, коли користувач додасть чи видалить будильник. У макеті цього стану немає.
 */
@Composable
fun UnreadableAlarmsBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.unreadable_banner_title), style = MaterialTheme.typography.titleSmall)
            FieldHint(stringResource(R.string.unreadable_banner_text))
        }
    }
}

/** Рядок «Дозволи» на вкладці «Налаштування» (design-spec 3.6). */
@Composable
fun PermissionsRow(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(Dimens.CardPadding), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
                FieldHint(stringResource(R.string.perm_row_hint))
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Екран «Дозволи» (design-spec 3.7, `08-permissions`): картка на кожен дозвіл з
 * «Дозволити» або «✓ Надано», примітка про Samsung, внизу «Готово» / «Пізніше».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val missing = rememberMissingPermissions()

    // Сповіщення на Android 13+ — звичайний запит у діалозі. Якщо людина вже відмовила
    // й система діалог більше не показує, відкриваємо налаштування сповіщень.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) settingsIntent(context, Permission.NOTIFICATIONS)?.let(context::startActivity)
    }
    fun request(permission: Permission) {
        val needsRuntime = permission == Permission.NOTIFICATIONS &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsRuntime) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            settingsIntent(context, permission)?.let { runCatching { context.startActivity(it) } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val allGranted = missing.isEmpty()
                    val label = stringResource(if (allGranted) R.string.action_done else R.string.perm_later)
                    val buttonModifier = Modifier
                        .fillMaxWidth()
                        .padding(Dimens.ScreenPadding)
                        .heightIn(min = Dimens.ButtonHeight)
                    if (allGranted) {
                        Button(onClick = onBack, modifier = buttonModifier) { Text(label, style = MaterialTheme.typography.labelLarge) }
                    } else {
                        OutlinedButton(onClick = onBack, modifier = buttonModifier) { Text(label, style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(Dimens.ListGap),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.perm_headline), style = MaterialTheme.typography.titleLarge)
                FieldHint(stringResource(R.string.perm_intro))
            }
            for (permission in Permission.entries) {
                PermissionCard(permission, granted = permission !in missing, onRequest = { request(permission) })
            }
            InfoNote(stringResource(R.string.perm_samsung_note))
        }
    }
}

@Composable
private fun PermissionCard(permission: Permission, granted: Boolean, onRequest: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(Dimens.IconCircle)
                    .background(
                        if (granted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(permission.icon), contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(permission.title), style = MaterialTheme.typography.titleSmall)
                    FieldHint(stringResource(permission.text))
                }
                if (granted) {
                    Row(
                        modifier = Modifier.heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.perm_granted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Button(onClick = onRequest) {
                        Text(stringResource(R.string.perm_allow), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
