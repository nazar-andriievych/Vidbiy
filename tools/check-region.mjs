#!/usr/bin/env node
/**
 * Що зараз вирішив би будильник для регіону — без телефона.
 *
 *   node tools/check-region.mjs 702            # за UID з довідника
 *   node tools/check-region.mjs Буча           # за частиною назви
 *   node tools/check-region.mjs 702 --proxy http://127.0.0.1:8787
 *
 * Бере довідник app/src/main/assets/regions.json, будує ланцюжок «громада → район → область»
 * (як SelectedRegion.coveringUids у застосунку), питає проксі `/v1/alerts`
 * і друкує рішення для «Будь-яка» та «Лише червона», а потім події сервера по цих регіонах
 * за останні години (`/log`, потрібен службовий пароль у $env:VIDBIY_ADMIN_TOKEN — server/README.md). Логіка рішення повторює RingDecision.kt: якщо вони розійдуться,
 * вірити треба застосунку.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const MAX_DATA_AGE_SECONDS = 180;
const MAX_ALERT_AGE_MS = 24 * 60 * 60 * 1000;
// Без User-Agent бот-захист Cloudflare перед *.workers.dev може відповісти 403.
const USER_AGENT = "vidbiy-check-region/1";

const args = process.argv.slice(2);
const proxyIndex = args.indexOf("--proxy");
const proxy = (proxyIndex >= 0 ? args.splice(proxyIndex, 2)[1] : "https://vidbiy-proxy.nazar-dev.workers.dev").replace(/\/$/, "");
const query = args.join(" ").trim();
if (!query) {
  console.error("Вкажіть UID або частину назви регіону: node tools/check-region.mjs 702");
  process.exit(2);
}

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const catalog = JSON.parse(readFileSync(join(root, "app/src/main/assets/regions.json"), "utf8"));

/** Міста, які ukrainealarm веде окремо від району (FR-29): громада → область. Як SEPARATE_CITIES у Region.kt. */
const SEPARATE_CITIES = { 1293: "22", 564: "12" };

/** Усі регіони довідника з ланцюжком покриття. */
const entries = [];
for (const oblast of catalog.oblasts) {
  entries.push({ uid: oblast.uid, title: oblast.title, covering: [oblast.uid], chain: [oblast.title] });
  for (const raion of oblast.raions ?? []) {
    entries.push({ uid: raion.uid, title: raion.title, covering: [raion.uid, oblast.uid], chain: [raion.title, oblast.title] });
    for (const hromada of raion.hromadas ?? []) {
      entries.push({
        uid: hromada.uid,
        title: hromada.title,
        covering: SEPARATE_CITIES[hromada.uid] ? [hromada.uid, oblast.uid] : [hromada.uid, raion.uid, oblast.uid],
        chain: [hromada.title, raion.title, oblast.title],
      });
    }
  }
}

const matches = /^\d+$/.test(query)
  ? entries.filter((e) => e.uid === query)
  : entries.filter((e) => e.title.toLowerCase().includes(query.toLowerCase()));
if (matches.length === 0) {
  console.error(`Не знайдено регіон «${query}».`);
  process.exit(1);
}
if (matches.length > 1) {
  console.log(`Знайдено ${matches.length}, уточніть UID:`);
  for (const m of matches.slice(0, 30)) console.log(`  ${m.uid.padStart(5)}  ${m.chain.join(" · ")}`);
  process.exit(1);
}
const region = matches[0];
const titleOf = (uid) => entries.find((e) => e.uid === uid)?.title ?? `?${uid}`;

// Службовий пароль для /log (server/README.md, «Службовий пароль»). Лише зі змінної середовища.
const ADMIN_TOKEN = process.env.VIDBIY_ADMIN_TOKEN;

async function get(path) {
  const started = Date.now();
  const headers = { "User-Agent": USER_AGENT, Accept: "application/json" };
  if (ADMIN_TOKEN) headers.Authorization = `Bearer ${ADMIN_TOKEN}`;
  const response = await fetch(`${proxy}${path}`, { headers });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return { body: await response.json(), receivedAt: Date.now(), tookMs: Date.now() - started };
}

const { body: alerts } = await get("/v1/alerts");

const now = Date.now();
console.log(`Регіон: ${region.uid} ${region.chain.join(" · ")}`);
console.log(`Покривають: ${region.covering.map((uid) => `${uid} (${titleOf(uid)})`).join(", ")}`);
console.log(`Проксі: ${proxy}  вік даних: ${alerts.age_seconds ?? "—"} с  підтверджено: ${alerts.confirmed_at ?? "—"}`);
console.log();

const byRegion = new Map((alerts.alerts ?? []).map((a) => [a.region, a.levels]));
const levels = region.covering.flatMap((uid) =>
  (byRegion.get(uid) ?? []).map((l) => ({ uid, level: l.level, since: Date.parse(l.since), reason: l.reason })),
);
if (levels.length === 0) {
  console.log("Над регіоном тривог немає.");
} else {
  for (const l of levels) {
    const hours = ((now - l.since) / 3_600_000).toFixed(1);
    const stale = now - l.since >= MAX_ALERT_AGE_MS ? "  (понад добу — не рахується)" : "";
    console.log(`  ${l.uid.padStart(5)} ${titleOf(l.uid)}: ${l.level} від ${new Date(l.since).toISOString()} (${hours} год)${stale}${l.reason ? ` — ${l.reason}` : ""}`);
  }
}
console.log();

function decide(waitFor) {
  if (alerts.alerts == null || alerts.age_seconds == null) return "RING_NO_DATA (дзвонить)";
  if (alerts.age_seconds > MAX_DATA_AGE_SECONDS) return "RING_STALE (дзвонить: дані старші за 3 хв)";
  const relevant = levels.filter((l) => waitFor === "RED_AND_YELLOW" || l.level === "red");
  if (relevant.length === 0) return "RING_CLEAR (дзвонить: тривоги потрібного рівня немає)";
  return relevant.some((l) => now - l.since < MAX_ALERT_AGE_MS)
    ? "KEEP_WAITING (мовчить)"
    : "RING_ALERT_TOO_LONG (дзвонить: тривога понад добу)";
}
console.log(`Будь-яка:      ${decide("RED_AND_YELLOW")}`);
console.log(`Лише червона:  ${decide("RED_ONLY")}`);

// Журнал подій сервера (/log): що змінювало стан цих регіонів за останні години.
const HOURS = 6;
const local = (ms) => new Date(ms).toLocaleString("uk-UA", { timeZone: "Europe/Kyiv", hour12: false });
try {
  const logs = await Promise.all(region.covering.map((uid) => get(`/log?hours=${HOURS}&region=${uid}`)));
  const events = [];
  for (const { body } of logs) {
    for (const entry of body) {
      if (entry.kind === "webhook") {
        const delay = Math.round((entry.receivedAt - entry.createdAt) / 1000);
        events.push([entry.receivedAt, `вебхук  ${entry.region.padStart(5)}  ${entry.from} → ${entry.to}  (${entry.outcome}, затримка ${delay} с)`]);
      } else {
        for (const c of entry.changes ?? []) events.push([entry.asOf, `знімок  ${c.region.padStart(5)}  ${c.from} → ${c.to}`]);
        for (const id of entry.kept ?? []) events.push([entry.asOf, `знімок  ${id.padStart(5)}  розійшовся з новішим вебхуком — лишено вебхук`]);
      }
    }
  }
  const unique = [...new Map(events.map((e) => [e.join("|"), e])).values()].sort(([a], [b]) => a - b);
  console.log();
  console.log(unique.length ? `Події на сервері за ${HOURS} год (час київський):` : `Змін по цих регіонах за ${HOURS} год не було.`);
  for (const [at, text] of unique.slice(-30)) console.log(`  ${local(at)}  ${text}`);
} catch (error) {
  const hint = ADMIN_TOKEN ? "" : " — потрібен $env:VIDBIY_ADMIN_TOKEN, див. server/README.md";
  console.warn(`(/log недоступний: ${error.message}${hint})`);
}
