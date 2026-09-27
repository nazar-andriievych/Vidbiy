# Проксі «Відбій»

Воркер на Cloudflare Workers між застосунком і ukrainealarm.
Приймає підписані вебхуки ukrainealarm, раз на хвилину бере повний знімок тривог,
тримає стан по всій країні й віддає його телефонам. Ключ API живе тут і ніколи не потрапляє в застосунок.

Контракт — у [`../docs/proxy-api.md`](../docs/proxy-api.md),
конспект API ukrainealarm — у [`../docs/ukrainealarm-api.md`](../docs/ukrainealarm-api.md).

| Файл | Що робить |
|---|---|
| `src/index.ts` | Маршрути; перевірка підпису вебхука ще до Durable Object |
| `src/ukrainealarm.ts` | Усе, що знає про ukrainealarm: підпис, формат подій, знімок, нумерація регіонів |
| `src/state.ts` | Чиста логіка стану: порядок подій, рівні, злиття зі знімком, свіжість (FR-30), статистика |
| `src/reload.ts` | Розклад знімків `/alerts`: раз на хвилину, з переходом на раз на 5 хв при відмовах |
| `src/compare.ts` | Порівняння стану лише з вебхуків зі знімками — чи губляться вебхуки |
| `src/check.ts` | Запасний варіант: звірка через `status` (зараз вимкнена) |
| `src/lab.ts` | Маршрут `/lab/call` для дослідів ліміту |
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
curl.exe http://127.0.0.1:8787/v1/alerts
```

> У PowerShell `curl` — це аліас до `Invoke-WebRequest`. Пиши `curl.exe`
> або `irm <url> | ConvertTo-Json`.

### Керування тривогою вручну

```bash
curl.exe "http://127.0.0.1:8787/mock?scenario=alert&uid=8"  # червона тривога у Волинській області
curl.exe "http://127.0.0.1:8787/mock?scenario=alert&uid=8&level=yellow&started_hours_ago=25"  # жовта, понад добу
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
curl.exe -A "vidbiy-dev/1" https://vidbiy-proxy.nazar-dev.workers.dev/stats
```

Запити до ukrainealarm лише рахуються (`upstream.recent`). Очікувано: 1 `alerts` на хвилину. Поки ввімкнений
цей режим, телефони чують «тривог немає» й будильник дзвонить як звичайний.

### Крок 2. Живий ключ

```powershell
npx wrangler secret put UKRAINEALARM_TOKEN    # спитає ключ і відправить прямо в Cloudflare
npm run deploy                                # без --var: сухий режим вимикається
curl.exe -A "vidbiy-dev/1" https://vidbiy-proxy.nazar-dev.workers.dev/v1/alerts
```

`upstream.mode` у `/stats` має стати `live`, а `alerts` у `/v1/alerts` — списком регіонів.

### Крок 3. Підписка на вебхук (одноразово, з власного терміналу)

```powershell
$s = Read-Host "ukrainealarm token" -AsSecureString
$env:UA_TOKEN = [System.Net.NetworkCredential]::new('', $s).Password
Invoke-RestMethod -Method Post -Uri https://api.ukrainealarm.com/api/v3/webhook `
  -Headers @{ Authorization = $env:UA_TOKEN } -ContentType "application/json" `
  -UserAgent "vidbiy-dev/1 (+https://github.com/nazar-andriievych/Vidbiy)" `
  -Body '{"webHookUrl":"https://vidbiy-proxy.nazar-dev.workers.dev/webhook"}'
Remove-Item Env:UA_TOKEN; Remove-Variable s
```

Змінити адресу — той самий запит із `-Method Patch`, відписатися — `-Method Delete`.
Поточна підписка оформлена на старе `/v2/webhook` (воркер поки приймає обидві адреси);
після переоформлення через `Patch` синонім у `src/index.ts` треба прибрати.

### Як зрозуміти, що вебхуки доходять

```bash
npx wrangler tail                 # живі запити до воркера
curl.exe -A "vidbiy-dev/1" https://vidbiy-proxy.nazar-dev.workers.dev/stats
```

У `wrangler tail` мають з'являтися `POST /webhook` (або `/v2/webhook`, поки не переоформили підписку). Якщо їх немає зовсім, а подія
точно була, — імовірно, бот-захист Cloudflare перед `*.workers.dev` не пускає відправника
ukrainealarm (ми вже бачили 403 на типовий `Python-urllib`, і запит тоді не доходить до воркера).
`401` у логах — підпис не зійшовся; поруч буде причина й `key-id`.

## Досліди ліміту

Щоб виміряти ліміт `/alerts` нашого ключа, воркер ставимо на паузу: сам він до
ukrainealarm не ходить, а вебхуки приймає як завжди. Потім той самий розклад
запитів проганяємо з ПК і з воркера (`tools/probe-ukrainealarm.mjs`, опис на
початку файлу) і порівнюємо.

```bash
# ключ маршруту /lab/call: випадковий, лежить у tmp/ (не в git), у чат не потрапляє
node -e "console.log(require('crypto').randomBytes(24).toString('hex'))" > ../tmp/lab-key.txt
npx wrangler secret put LAB_KEY < ../tmp/lab-key.txt
npx wrangler deploy --var UPSTREAM_PAUSED:1   # /health → "paused": true
```

1. Зачекати щонайменше 15 хв від останнього запиту в `/stats` → `upstream.recent`.
2. `node tools/probe-ukrainealarm.mjs --via direct` (з токеном у власному терміналі).
3. Зачекати 30 хв (або вдвічі довше, ніж тривало відновлення).
4. `node tools/probe-ukrainealarm.mjs --via worker`.

Після дослідів: `npm run deploy` (без `--var` пауза знімається) і
`npx wrangler secret delete LAB_KEY`.

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
- **Щохвилинний знімок.** Тиша на вебхуках — це або «нічого не сталося», або «канал зламався»,
  і розрізнити їх може лише повний список. Свіжість (`age_seconds`) рахується від останнього
  знімка; вебхуки продовжують її не далі ніж на 15 хв (FR-30). Понад 3 хв без підтвердження
  застосунок дзвонить за fail-safe.
- **Запобіжник на запити.** Ліміт ukrainealarm невідомий, тож скільки ми питаємо, обмежує
  не лише логіка, а й окремий лічильник, який не пустить більше 3 запитів на хвилину.
- **Помилки віддаються як `200`** зі станом у полях. Застосунку потрібні дані для рішення,
  а не помилка транспорту.
