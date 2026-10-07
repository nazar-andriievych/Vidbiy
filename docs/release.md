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

- Збільшити `versionCode` (і `versionName`) в `app/build.gradle.kts`: Android не встановить
  APK з тим самим або меншим `versionCode` поверх наявного.
- Release не ставиться поверх debug-збірки (інший підпис): debug спершу видалити.
