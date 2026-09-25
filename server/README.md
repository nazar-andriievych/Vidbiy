# Проксі «Відбій»

Воркер на Cloudflare Workers між застосунком і ukrainealarm.
Приймає підписані вебхуки ukrainealarm, тримає з них стан тривог по всій країні
й віддає його телефонам. Ключ API живе тут і ніколи не потрапляє в застосунок.

Контракт — у [`../docs/proxy-api.md`](../docs/proxy-api.md),
конспект API ukrainealarm — у [`../docs/ukrainealarm-api.md`](../docs/ukrainealarm-api.md).

| Файл | Що робить |
|---|---|
| `src/index.ts` | Маршрути; перевірка підпису вебхука ще до Durable Object |
| `src/ukrainealarm.ts` | Усе, що знає про ukrainealarm: підпис, формат подій, знімок, нумерація регіонів |
| `src/state.ts` | Чиста логіка стану: порядок подій, злиття зі знімком, свіжість, статистика |
| `src/check.ts` | Одна звірка з API: `status`, і `alerts` лише якщо номер змінився |
| `src/budget.ts` | Запобіжник: не більше 3 запитів до API за хвилину й 3000 за добу |
| `src/probe.ts` | Перевірка, чи повторює ukrainealarm доставку вебхука |
| `src/hub.ts` | Durable Object: сховище, будильник `alarm()`, усі запити до API |
| `src/mock.ts` | Підробка стану для локальної розробки |

## Локальна розробка

Локально працюємо на підробці — справжній ключ на робочу машину не кладемо.

```bash
npm install
cp .dev.vars.example .dev.vars
npm run dev
```

```bash
curl.exe http://127.0.0.1:8787/health
curl.exe http://127.0.0.1:8787/v2/alerts
```

> У PowerShell `curl` — це аліас до `Invoke-WebRequest`. Пиши `curl.exe`
> або `irm <url> | ConvertTo-Json`.

### Керування тривогою вручну

```bash
curl.exe "http://127.0.0.1:8787/mock?scenario=alert&uid=8"  # тривога у Волинській області
curl.exe "http://127.0.0.1:8787/mock?scenario=clear"        # відбій
curl.exe "http://127.0.0.1:8787/mock?scenario=down"         # тривога є, але 10 хв тиші
curl.exe "http://127.0.0.1:8787/mock"                       # який сценарій зараз
```

`uid` — ID регіону з довідника (`app/src/main/assets/regions.json`). Тривога має бути
оголошена в регіоні, який **накриває** обраний у застосунку, інакше будильник задзвонить:
для громади підходять її власний ID, ID її району або її області.

Щоб телефон дістався до локального воркера, запускай `npm run dev -- --ip 0.0.0.0`
і став у застосунку адресу `http://<IP-комп'ютера>:8787`.

Справжні вебхуки локально не прийдуть: ukrainealarm шле їх лише на публічну адресу,
а підробити підпис без їхнього приватного ключа неможливо. Логіку вебхуків перевіряють
юніт-тести з власною тестовою парою ключів.

## Перевірки

```bash
npm test         # юніт-тести
npm run typecheck
```

## Розгортання — по кроках, від безпечного до живого

Одноразово: `npx wrangler login`. Адреса: `https://vidbiy-proxy.nazar-dev.workers.dev`

### Крок 1. Сухий прогін: ключа немає, назовні нічого не йде

```powershell
npx wrangler deploy --var FAKE_UPSTREAM:1
curl.exe -A "vidbiy-dev/1" https://vidbiy-proxy.nazar-dev.workers.dev/v2/stats
```

Запити до ukrainealarm лише рахуються (`upstream.recent`). Очікувано: 2 на старті
(`alerts/status` + `alerts`), далі 1 `alerts/status` на хвилину. Поки ввімкнений
цей режим, телефони чують «тривог немає» й будильник дзвонить як звичайний.

### Крок 2. Живий ключ

```powershell
npx wrangler secret put UKRAINEALARM_TOKEN    # спитає ключ і відправить прямо в Cloudflare
npm run deploy                                # без --var: сухий режим вимикається
curl.exe -A "vidbiy-dev/1" https://vidbiy-proxy.nazar-dev.workers.dev/v2/alerts
```

`upstream.mode` у `/v2/stats` має стати `live`, а `active` у `/v2/alerts` — списком регіонів.

### Крок 3. Підписка на вебхук (одноразово, з власного терміналу)

```powershell
$s = Read-Host "ukrainealarm token" -AsSecureString
$env:UA_TOKEN = [System.Net.NetworkCredential]::new('', $s).Password
Invoke-RestMethod -Method Post -Uri https://api.ukrainealarm.com/api/v3/webhook `
  -Headers @{ Authorization = $env:UA_TOKEN } -ContentType "application/json" `
  -UserAgent "vidbiy-dev/1 (+https://github.com/nazar-andriievych/Vidbiy)" `
  -Body '{"webHookUrl":"https://vidbiy-proxy.nazar-dev.workers.dev/v2/webhook"}'
Remove-Item Env:UA_TOKEN; Remove-Variable s
```

Змінити адресу — той самий запит із `-Method Patch`, відписатися — `-Method Delete`.

### Як зрозуміти, що вебхуки доходять

```bash
npx wrangler tail                 # живі запити до воркера
curl.exe https://vidbiy-proxy.nazar-dev.workers.dev/v2/stats
```

У `wrangler tail` мають з'являтися `POST /v2/webhook`. Якщо їх немає зовсім, а подія
точно була, — імовірно, бот-захист Cloudflare перед `*.workers.dev` не пускає відправника
ukrainealarm (ми вже бачили 403 на типовий `Python-urllib`, і запит тоді не доходить до воркера).
`401` у логах — підпис не зійшовся; поруч буде причина й `key-id`.

## Секрети

Ключ ukrainealarm **ніколи не потрапляє ні в git, ні в застосунок, ні на робочу машину**.
Він існує у двох місцях: у менеджері паролів і в секретах Cloudflare.
Секрети Cloudflare односторонні: `wrangler secret put` записує, прочитати назад не може ніхто.

Публічний ключ, яким ukrainealarm підписує вебхуки, — не секрет і лежить прямо в коді.

## Чому саме так

- **Вебхуки, а не опитування.** alerts.in.ua рахував ліміт за IP, а запити з Workers до
  сайтів за Cloudflare приходять зі спільних внутрішніх адрес — ліміт ділився з чужими
  воркерами, і ми ловили 429. Вебхук обертає напрямок: зміни приходять до нас самі.
- **Стан у Durable Object.** Він існує в одному екземплярі на весь світ, тож вебхуки й
  телефони бачать одне й те саме. Стан лежить у сховищі, бо об'єкт засинає між запитами.
- **Підпис перевіряється до Durable Object.** Адреса вебхука публічна; підробка не має
  дістатися до стану й витрачати безкоштовні ліміти сховища.
- **Порядок подій — за `createdAt` у кожному регіоні.** Запізніла стара подія не затирає
  новішу; так само гасяться дублікати й повтори.
- **Перевірка на тиші.** Тиша на вебхуках — це або «нічого не сталося», або «канал зламався».
  Тож хвилина без подій — привід самим спитати номер останньої зміни. Той самий — стан
  правильний; інший — перезавантажуємо список. Свіжість (`age_seconds`) рахується від
  останнього підтвердження; понад 3 хв без них застосунок дзвонить за fail-safe.
- **Запобіжник на запити.** Ліміт ukrainealarm невідомий, тож скільки ми питаємо, обмежує
  не лише логіка, а й окремий лічильник, який не пустить більше 3 запитів на хвилину.
- **Помилки віддаються як `200`** зі станом у полях. Застосунку потрібні дані для рішення,
  а не помилка транспорту.
