# PreToolUse-хук: не дає Claude читати особисті дані з телефона автора й відкривати
# сторонні застосунки через adb. Правила словами — CLAUDE.md, «Робота з телефоном автора».
# Вихід 2 блокує виклик інструмента; текст зі stderr бачить Claude.

[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raw = [Console]::In.ReadToEnd()
try {
    $command = ($raw | ConvertFrom-Json).tool_input.command
} catch {
    exit 0
}
if (-not $command) { exit 0 }

# Лише команди, що справді запускають adb.
if ($command -notmatch '(?i)\badb(\.exe)?\b') { exit 0 }

$rules = @(
    @{ Pattern = 'content\s+(query|read|call|insert|update|delete)'; Why = 'content-провайдери (контакти, SMS, медіа)' },
    @{ Pattern = '\bpull\b'; Why = 'adb pull — копіювання файлів з телефона' },
    @{ Pattern = '/sdcard|/storage/|/data/media'; Why = 'особисті файли й фото' },
    @{ Pattern = 'bugreport'; Why = 'bugreport містить дані всіх застосунків' },
    @{ Pattern = '\badb(\.exe)?"?\s+(-s\s+\S+\s+)?backup\b'; Why = 'adb backup' },
    @{ Pattern = '\bpm\s+list\s+packages'; Why = 'список застосунків' },
    # Наші пакети: ua.vidbiy.app (release) і ua.vidbiy.app.debug — і більше нічого.
    @{ Pattern = 'run-as\s+(?!ua\.vidbiy\.app(\.debug)?(?![\w.]))'; Why = 'дані чужого застосунку' },
    @{ Pattern = 'dumpsys\s+(account|location|telephony|contact|sms|clipboard|usagestats)'; Why = 'акаунти, геолокація, телефонія, буфер обміну' },
    @{ Pattern = 'statusbar\s+expand'; Why = 'шторка показує чужі сповіщення' },
    @{ Pattern = '\bmonkey\b'; Why = 'monkey запускає сторонні застосунки' },
    @{ Pattern = 'KEYCODE_APP_SWITCH|keyevent\s+187\b'; Why = 'список нещодавніх застосунків' }
)

foreach ($rule in $rules) {
    if ($command -match "(?i)$($rule.Pattern)") {
        [Console]::Error.WriteLine("adb-guard: заблоковано — $($rule.Why). Див. CLAUDE.md, «Робота з телефоном автора».")
        exit 2
    }
}

if ($command -match '(?i)dumpsys\s+notification' -and $command -notmatch '(?i)vidbiy') {
    [Console]::Error.WriteLine('adb-guard: заблоковано — повний dumpsys notification містить чужі сповіщення. Використай tools/vidbiy-notifications.ps1.')
    exit 2
}
if ($command -match '(?i)\blogcat\b' -and $command -notmatch '(?i)Vidbiy|--pid|logcat\s+-c\b') {
    [Console]::Error.WriteLine('adb-guard: заблоковано — повний logcat. Лише теги VidbiyWait/VidbiyAlarm або --pid нашого процесу.')
    exit 2
}
if ($command -match '(?i)\bam\s+start(-activity|-foreground-service|-service)?\b' -and $command -notmatch 'ua\.vidbiy\.app') {
    [Console]::Error.WriteLine('adb-guard: заблоковано — am start дозволено лише для ua.vidbiy.app / ua.vidbiy.app.debug.')
    exit 2
}

exit 0
