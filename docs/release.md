# Release-збірка й підпис

## Ключ

- Файл: `%USERPROFILE%\.android-keys\vidbiy-release.p12` (PKCS12, alias `vidbiy`, RSA 4096, дійсний 100 років, `CN=Vidbiy`).
- Пароль — у менеджері паролів; Gradle бере його з `%USERPROFILE%\.gradle\gradle.properties`
  (`vidbiy.signing.storeFile`, `vidbiy.signing.password`). У репозиторій не потрапляє ні те, ні інше.
- Резервні копії файлу — окремо від пароля (ПК, флешка, хмара не в тому ж акаунті, що пароль).
- Ключ не можна ні замінити, ні загубити: Android приймає оновлення лише з тим самим підписом.

Відбиток сертифіката (публічний, для звірки APK і реєстрації пакета в Google):

```
SHA-256: 75:58:AD:63:74:C2:52:1F:F3:EF:34:DA:4B:49:33:50:D8:9C:CB:EA:63:E4:01:FC:DE:4D:CF:E2:6B:22:7E:EA
```

## Збірка

```
.\gradlew.bat assembleRelease
```

APK — `app/build/outputs/apk/release/app-release.apk`. Без ключа виходить `app-release-unsigned.apk`,
який не встановлюється.

Перевірити підпис (`<версія>` — найновіша тека в `%LOCALAPPDATA%\Android\Sdk\build-tools`):

```
%LOCALAPPDATA%\Android\Sdk\build-tools\<версія>\apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk
```

SHA-256 у виводі має збігатися з відбитком вище.

## Перед кожним релізом

- Підняти `appVersion` в `app/build.gradle.kts` (формат `MAJOR.MINOR.PATCH`): виправлення → patch,
  помітна для людини зміна → minor, major — лише коли застосунок зміниться докорінно.
  `versionCode` обчислюється з неї: `MAJOR*1000000 + MINOR*1000 + PATCH` (0.1.0 → `1000`, 1.2.3 → `1002003`).
  Android порівнює лише його й не встановить APK з тим самим або меншим `versionCode` поверх наявного.
  Minor і patch — до 999; інший формат збірка не прийме.
- Позначити коміт випуску тегом: `git tag v0.1.0` — щоб за версією з телефона знайти код.
- Release не ставиться поверх debug-збірки (інший підпис): debug спершу видалити.

## Після випуску: сказати застосункам

Застосунок дізнається про нову версію від проксі (`docs/proxy-api.md`, «Оновлення застосунку»).
Коли APK уже лежить там, звідки його завантажують:

1. У `server/wrangler.jsonc` → `vars`: `LATEST_VERSION_NAME` — `appVersion` з `app/build.gradle.kts`,
   `LATEST_VERSION_CODE` — обчислений з неї `versionCode`; `UPDATE_URL` — сторінка завантаження (лише `https://`).
2. `MIN_VERSION_CODE` — **лише** якщо в старих версіях небезпечний баг (будильник може не задзвонити
   або задзвонити під час тривоги). Нижчі версії одразу перестануть чекати тривог і дзвонитимуть
   у свій час, доки людину не оновлять.
3. `cd server; npm run deploy`, потім перевірити: `curl.exe -A vidbiy-check https://vidbiy-proxy.nazar-dev.workers.dev/v1/alerts`
   — у відповіді поле `update` з новими значеннями.

Порядок важливий: спершу APK, потім воркер. Інакше банер вестиме на сторінку, де нової версії ще немає.
