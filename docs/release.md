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

## Випуск через CI (основний спосіб)

`.github/workflows/release.yml` запускається тегом `v*`. Ключ і Cloudflare-токен лежать у секретах
двох GitHub Environments, і кожне віддає їх лише після твого підтвердження:

| Environment | Секрети | Що відкриває |
|---|---|---|
| `release-sign` | `VIDBIY_KEYSTORE_B64` (файл `.p12` у base64), `VIDBIY_KEYSTORE_PASSWORD` | підпис APK |
| `release-publish` | `CLOUDFLARE_API_TOKEN` (лише «Edit Cloudflare Workers»); змінна `CLOUDFLARE_ACCOUNT_ID` | публікацію релізу й деплой воркера |

Обидва пускають лише теги `v*` і чекають на підтвердження власника репозиторію.

1. **Коміт випуску.** В одному коміті:
   - `appVersion` в `app/build.gradle.kts`;
   - `LATEST_VERSION_NAME` і `LATEST_VERSION_CODE` у `server/wrangler.jsonc`.

   Як підняти версію — див. «Номер версії» нижче. Push у `main`.
   Воркер з цього коміту ще ніхто не деплоїть, тож застосунки про версію поки не знають.
   Не запускай `npm run deploy` вручну, доки реліз не опубліковано.
2. **Тег з описом змін.** Опис стане текстом релізу:
   ```
   git tag -a v0.1.1 -m "Що змінилося — людською мовою"
   git push origin v0.1.1
   ```
3. **CI перевіряє.** Тести мають пройти, а тег має збігатися з `appVersion` і з `LATEST_VERSION_*`.
   Тег має стояти на коміті з `main`. Потім CI збирає непідписаний APK. Gradle ключа не бачить.
4. **⏸ Підтвердження 1** (Actions → запуск → «Review deployments» → `release-sign`).
   `apksigner` підписує APK і звіряє відбиток з наведеним вище. CI створює **чернетку** релізу з `vidbiy.apk`.
   Її не бачать ні сторінка завантаження, ні застосунки.
5. **Перевір APK з чернетки** (Releases → чернетка → `vidbiy.apk`):
   - встанови його на телефон поверх попередньої версії;
   - переконайся, що він оновлюється, дані на місці й будильник дзвонить.
6. **⏸ Підтвердження 2** (`release-publish`). Реліз стає публічним, після цього `wrangler deploy`.
   CI перевіряє, що `/v1/alerts` віддає новий `latest_version_code`. Застосунки побачать банер
   протягом години.

Щось не так на кроці 5 → Reject. Тоді:
- видали чернетку й тег (`git push origin :refs/tags/v0.1.1`, `git tag -d v0.1.1`);
- виправ;
- повтори з наступною patch-версією.

## Номер версії

- Підняти `appVersion` в `app/build.gradle.kts` (формат `MAJOR.MINOR.PATCH`): виправлення → patch,
  помітна для людини зміна → minor, major — лише коли застосунок зміниться докорінно.
  `versionCode` обчислюється з неї: `MAJOR*1000000 + MINOR*1000 + PATCH` (0.1.0 → `1000`, 1.2.3 → `1002003`).
  Android порівнює лише його й не встановить APK з тим самим або меншим `versionCode` поверх наявного.
  Minor і patch — до 999; інший формат збірка не прийме.
- Release не ставиться поверх debug-збірки (інший підпис): debug спершу видалити.

## Вручну, без CI (запасний спосіб)

### Випуск на GitHub

Сторінка завантаження — `site/` (GitHub Pages, https://nazar-andriievych.github.io/Vidbiy/; публікує
`.github/workflows/pages.yml` при кожній зміні `site/`). Кнопка на ній веде на
`releases/latest/download/vidbiy.apk`, тож файл у випуску завжди має називатися **`vidbiy.apk`**:

```
Copy-Item app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\vidbiy.apk
git push origin v0.1.0
gh release create v0.1.0 app\build\outputs\apk\release\vidbiy.apk --title "0.1.0" --notes "Що змінилося — людською мовою"
```

Після цього перевірити, що кнопка на сторінці завантажує нову версію.

### Після випуску: сказати застосункам

Застосунок дізнається про нову версію від проксі (`docs/proxy-api.md`, «Оновлення застосунку»).
Коли APK уже лежить там, звідки його завантажують:

1. У `server/wrangler.jsonc` → `vars`: `LATEST_VERSION_NAME` — `appVersion` з `app/build.gradle.kts`,
   `LATEST_VERSION_CODE` — обчислений з неї `versionCode`; `UPDATE_URL` — сторінка завантаження `https://nazar-andriievych.github.io/Vidbiy/` (лише `https://`).
2. `MIN_VERSION_CODE` — **лише** якщо в старих версіях небезпечний баг (будильник може не задзвонити
   або задзвонити під час тривоги). Нижчі версії одразу перестануть чекати тривог і дзвонитимуть
   у свій час, доки людину не оновлять.
3. `cd server; npm run deploy`, потім перевірити: `curl.exe -A vidbiy-check https://vidbiy-proxy.nazar-dev.workers.dev/v1/alerts`
   — у відповіді поле `update` з новими значеннями.

Порядок важливий: спершу APK, потім воркер. Інакше банер вестиме на сторінку, де нової версії ще немає.
