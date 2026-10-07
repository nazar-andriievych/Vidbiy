import type { AlertsResponse, AppUpdate } from "./types";

/** Змінні воркера, з яких складається [AppUpdate]. Задаються у `wrangler.jsonc` → `vars`. */
export interface UpdateVars {
  /** `versionCode` останнього випуску. Без нього про оновлення нічого не кажемо. */
  LATEST_VERSION_CODE?: string;
  /** `versionName` останнього випуску — для тексту «Доступна версія 1.1». */
  LATEST_VERSION_NAME?: string;
  /** Версії, нижчі за цю, не чекають тривог і дзвонять як звичайний будильник. */
  MIN_VERSION_CODE?: string;
  /** Звідки завантажити. Порожньо — канал ще не обрано, застосунок покаже банер без кнопки. */
  UPDATE_URL?: string;
}

/**
 * Додає до відповіді відомості про останній випуск. Окремого маршруту для цього немає:
 * застосунок і так питає `/v1/alerts`, тож оновлення не коштує жодного зайвого запиту
 * й нічого нового серверу про телефон не розповідає (NFR-4). Іде й у відповідь «даних немає»:
 * застарілій версії варто сказати про це, навіть коли стан тривог недосяжний.
 */
export function withUpdate(response: AlertsResponse, vars: UpdateVars): AlertsResponse {
  return { ...response, update: appUpdate(vars) };
}

/**
 * Що відповісти застосунку про оновлення. Помилка в налаштуваннях не має зламати
 * відповідь про тривоги, тож усе кривеньке просто відкидається:
 *
 * - немає чи кривий `LATEST_VERSION_CODE` — `null`, застосунок нічого не показує;
 * - `MIN_VERSION_CODE` вищий за останній випуск — обрізається до нього: вимагати версію,
 *   якої ще немає, означало б вимкнути очікування тривог у всіх, кому нема на що оновитися;
 * - адреса не `https://` — без адреси.
 */
export function appUpdate(vars: UpdateVars): AppUpdate | null {
  const latest = positiveInt(vars.LATEST_VERSION_CODE);
  if (latest === null) return null;
  const min = Math.min(positiveInt(vars.MIN_VERSION_CODE) ?? 0, latest);
  const name = vars.LATEST_VERSION_NAME?.trim();
  const url = vars.UPDATE_URL?.trim();
  return {
    latest_version_code: latest,
    latest_version_name: name ? name : String(latest),
    min_version_code: min,
    url: url && url.startsWith("https://") ? url : null,
  };
}

function positiveInt(raw: string | undefined): number | null {
  if (raw === undefined || !/^\s*\d+\s*$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}
