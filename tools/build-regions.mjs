// Збирає довідник регіонів для застосунку зі списку регіонів ukrainealarm — того самого джерела,
// звідки сервер бере тривоги, тож номери (UID) збігаються з тими, на які оголошують тривоги.
//
//   $env:UA_TOKEN="…"; node tools/build-regions.mjs   # завантажити свіжий /api/v3/regions у tmp/ і зібрати
//   node tools/build-regions.mjs                      # зібрати з уже завантаженого tmp/ukrainealarm-regions.json
//
// Результат: app/src/main/assets/regions.json — ієрархія «область → район → громада».
// Після оновлення переглянути `git diff app/src/main/assets/regions.json`: зниклий UID означає,
// що збережені в людей будильники з ним більше не побачать тривоги саме цього регіону.
// Токен — лише в змінній середовища, нікуди не зберігається.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dumpPath = join(root, "tmp", "ukrainealarm-regions.json");
const outputPath = join(root, "app", "src", "main", "assets", "regions.json");

// Як REGION_ID_MAP у server/src/ukrainealarm.ts: АР Крим у ukrainealarm — 9999, у нас — 29.
const ID_MAP = { 9999: "29" };

// Тестовий регіон ukrainealarm: сервер його ігнорує, людям він не потрібен.
const SKIPPED = new Set(["0"]);

// Харків і Запоріжжя в ukrainealarm — окремі регіони верхнього рівня зі своїм статусом, а людям
// звичніше шукати їх серед громад свого району. Правило покриття для них — FR-29 (Region.kt, SEPARATE_CITIES).
const CITY_IN_RAION = { 1293: "124", 564: "149" };

// Області без районів: тривогу оголошують на весь регіон.
const CHILDLESS = new Set(["29", "31"]);

// ukrainealarm досі називає ці регіони назвами до перейменування. Показуємо чинні — інакше людина,
// що шукає «Шептицький», його не знайде. Номер той самий, тож на тривоги це не впливає.
// Якщо ukrainealarm перейде на нову назву, скрипт про це скаже — рядок тоді можна прибрати.
const CURRENT_NAMES = {
  38: "Володимирський район", // Володимир-Волинський
  255: "м. Володимир та Володимирська територіальна громада",
  127: "Берестинський район", // Красноградський
  1324: "Берестинська територіальна громада",
  84: "Сіверськодонецький район", // Сєвєродонецький
  820: "м. Сіверськодонецьк та Сіверськодонецька територіальна громада",
  327: "м. Самар та Самарівська територіальна громада", // Новомосковськ
  315: "м. Шахтарськ та Шахтарська територіальна громада", // Першотравенськ
  471: "м. Звягель та Звягельська територіальна громада", // Новоград-Волинський
  832: "м. Шептицький та Шептицька територіальна громада", // Червоноград
  971: "м. Південне та Південна територіальна громада", // Южне
  1299: "м. Златопіль та Златопільська територіальна громада", // Первомайський
};

const collator = new Intl.Collator("uk");

async function loadRegions() {
  const token = process.env.UA_TOKEN;
  if (token) {
    const response = await fetch("https://api.ukrainealarm.com/api/v3/regions", {
      headers: { Authorization: token, "User-Agent": "vidbiy-build-regions/1" },
    });
    if (!response.ok) throw new Error(`ukrainealarm /regions: HTTP ${response.status}`);
    const text = await response.text();
    mkdirSync(dirname(dumpPath), { recursive: true });
    writeFileSync(dumpPath, text, "utf8");
    console.log(`Завантажено свіжий список регіонів у ${dumpPath}`);
    return JSON.parse(text);
  }
  try {
    return JSON.parse(readFileSync(dumpPath, "utf8"));
  } catch {
    throw new Error(`Немає ${dumpPath}. Задайте $env:UA_TOKEN, щоб завантажити список з ukrainealarm.`);
  }
}

const usedNames = new Set();

function title(region) {
  const current = CURRENT_NAMES[region.regionId];
  if (current === undefined) return region.regionName.trim();
  usedNames.add(region.regionId);
  if (current === region.regionName.trim()) {
    console.warn(`ukrainealarm уже називає ${region.regionId} «${current}» — рядок у CURRENT_NAMES можна прибрати`);
  }
  return current;
}

function children(region) {
  return region.regionChildIds ?? [];
}

function buildTree(states) {
  const cities = new Map();
  const oblasts = [];

  for (const state of states) {
    const id = state.regionId;
    if (SKIPPED.has(id)) continue;
    if (id in CITY_IN_RAION) {
      cities.set(CITY_IN_RAION[id], { uid: id, title: title(state) });
      continue;
    }
    oblasts.push({
      uid: ID_MAP[id] ?? id,
      title: title(state),
      raions: children(state).map((district) => ({
        uid: district.regionId,
        title: title(district),
        hromadas: children(district).map((community) => ({ uid: community.regionId, title: title(community) })),
      })),
    });
  }

  for (const [raionUid, city] of cities) {
    const raion = oblasts.flatMap((o) => o.raions).find((r) => r.uid === raionUid);
    if (!raion) throw new Error(`Район ${raionUid} для ${city.title} (${city.uid}) не знайдено`);
    raion.hromadas.push(city);
  }
  return oblasts;
}

function check(oblasts) {
  const uids = new Set();
  let raions = 0;
  let hromadas = 0;
  const add = (uid) => {
    if (uids.has(uid)) throw new Error(`UID ${uid} трапляється двічі`);
    uids.add(uid);
  };

  for (const oblast of oblasts) {
    add(oblast.uid);
    if (oblast.raions.length === 0 && !CHILDLESS.has(oblast.uid)) {
      throw new Error(`Область ${oblast.title} лишилася без районів`);
    }
    for (const raion of oblast.raions) {
      add(raion.uid);
      raions++;
      if (raion.hromadas.length === 0) throw new Error(`Район ${raion.title} лишився без громад`);
      for (const hromada of raion.hromadas) {
        add(hromada.uid);
        hromadas++;
      }
    }
  }
  for (const id of Object.keys(CURRENT_NAMES)) {
    if (!usedNames.has(id)) console.warn(`Регіону ${id} з CURRENT_NAMES у списку ukrainealarm немає`);
  }
  return { oblasts: oblasts.length, raions, hromadas };
}

function sortTree(oblasts) {
  oblasts.sort((a, b) => collator.compare(a.title, b.title));
  for (const oblast of oblasts) {
    oblast.raions.sort((a, b) => collator.compare(a.title, b.title));
    for (const raion of oblast.raions) {
      raion.hromadas.sort((a, b) => collator.compare(a.title, b.title));
    }
  }
  return oblasts;
}

const { states } = await loadRegions();
const oblasts = sortTree(buildTree(states));
const counts = check(oblasts);

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(
  outputPath,
  JSON.stringify({ generated_at: new Date().toISOString().slice(0, 10), oblasts }, null, 1) + "\n",
  "utf8",
);

console.log(
  `Записано ${outputPath}: областей ${counts.oblasts}, районів ${counts.raions}, громад ${counts.hromadas}`,
);
