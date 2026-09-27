import type { AlertEvent, Level, RegionState, StoredLevel } from "./types";

/**
 * Усе, що знає про api.ukrainealarm.com, живе тут: адреси, формат повідомлень, підпис,
 * нумерація регіонів. Решта сервера працює з нашими типами з `types.ts`.
 */

const API_BASE = "https://api.ukrainealarm.com/api/v3";

/** Хто ми такі: якщо ми комусь заважаємо, хай мають кого попросити, а не блокують мовчки. */
const USER_AGENT = "vidbiy-proxy/2 (+https://github.com/nazar-andriievych/Vidbiy)";

/** Нас цікавлять лише повітряні тривоги; артобстріли й решта типів не впливають на будильник. */
export const AIR = "AIR";

/**
 * Публічний ключ, яким ukrainealarm підписує вебхуки
 * (https://api.ukrainealarm.com/webhook-public.pem, взято 2026-09-24).
 * Це не секрет: перевірити підпис може будь-хто, підробити — лише власник приватного ключа.
 */
export const WEBHOOK_PUBLIC_KEY_PEM = `-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAweyCrYtdAlrOrw4/v7y7
4u8h/dXuxZrcg3MvFw2wMvWpY3cnx6JQBapFG7zbWDwKEa5rBYxciynFTJpc9Hjn
ifOEJ1DRlgFsJG8ANXFC1JCJijbvMHa8QuoVbm25S9Z27YYYgjVxtG8baJ7Uilnc
dYGjHm+I7hyS069wrr8dhKh5WuvbRQ09vIU1NMkFafpla10la6TrlgZpJNcsOZfh
JD6cpK1bnzSSoRzOgPvdZhvh+Wndxk2IyulYhdKh0/mAU7EzC54T0BB/gSbWIoLR
LA20/WF4xWz70gn2j5ywWSNMkf3BPIkeVIcw/36zCmx7CdoW6iAl3d4WBbHlNQXc
SQIDAQAB
-----END PUBLIC KEY-----`;

/**
 * Старіші повідомлення відкидаємо: так радить ukrainealarm, і так перехоплений
 * колись справжній вебхук не можна «програти» нам удруге.
 */
export const MAX_WEBHOOK_AGE_SECONDS = 5 * 60;

/**
 * ID регіонів у ukrainealarm і в довіднику застосунку (з таблиці alerts.in.ua) збігаються
 * для всіх регіонів, крім АР Крим. Звірено 2026-09-24: 1532 з 1533 спільних назв.
 */
const REGION_ID_MAP: Record<string, string> = { "9999": "29" };

/** «Тестовий регіон» ukrainealarm. Справжнім людям він не потрібен. */
const IGNORED_REGION_IDS = new Set(["0"]);

/** Переводить ID ukrainealarm у нумерацію застосунку; `null` — регіон ігноруємо. */
export function toAppRegionId(raw: unknown): string | null {
  if (typeof raw !== "string" && typeof raw !== "number") return null;
  const id = String(raw).trim();
  if (id === "" || IGNORED_REGION_IDS.has(id)) return null;
  return REGION_ID_MAP[id] ?? id;
}

// ---------------------------------------------------------------------------
// Підпис вебхука
// ---------------------------------------------------------------------------

export type SignatureCheck =
  | { ok: true }
  | { ok: false; reason: "missing_headers" | "bad_algorithm" | "stale_timestamp" | "bad_signature" };

/**
 * Перевіряє, що вебхук справді від ukrainealarm.
 *
 * Вони підписують RSA-SHA256 (PKCS#1 v1.5) рядок `{timestamp}.{сире тіло}` — тому тіло
 * треба брати рівно таким, як прийшло: після `JSON.parse` + `stringify` підпис не зійдеться.
 * Див. https://api.ukrainealarm.com/webhook-signature-validation.html
 */
export async function verifyWebhook(
  headers: Headers,
  rawBody: string,
  publicKey: CryptoKey,
  nowSeconds: number,
): Promise<SignatureCheck> {
  const signature = headers.get("x-webhook-signature");
  const timestamp = headers.get("x-webhook-timestamp");
  if (!signature || !timestamp) return { ok: false, reason: "missing_headers" };

  const algorithm = headers.get("x-webhook-signature-alg");
  if (algorithm && algorithm.toLowerCase() !== "rsa-sha256") {
    return { ok: false, reason: "bad_algorithm" };
  }

  const sentAt = Number(timestamp);
  if (!Number.isFinite(sentAt) || Math.abs(nowSeconds - sentAt) > MAX_WEBHOOK_AGE_SECONDS) {
    return { ok: false, reason: "stale_timestamp" };
  }

  let signatureBytes: Uint8Array;
  try {
    signatureBytes = base64ToBytes(signature);
  } catch {
    return { ok: false, reason: "bad_signature" };
  }

  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    publicKey,
    signatureBytes,
    new TextEncoder().encode(`${timestamp}.${rawBody}`),
  );
  return valid ? { ok: true } : { ok: false, reason: "bad_signature" };
}

export function importPublicKey(pem: string): Promise<CryptoKey> {
  const base64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return crypto.subtle.importKey(
    "spki",
    base64ToBytes(base64),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );
}

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

// ---------------------------------------------------------------------------
// Повідомлення вебхука
// ---------------------------------------------------------------------------

/**
 * Розбирає тіло вебхука. `null` — тіло зовсім не схоже на очікуване.
 *
 * Головне правило з їхньої документації: `status` описує лише одну зміну,
 * а `activeAlertLevels` — повний стан регіону після неї. `DEACTIVATE` може зняти
 * одну загрозу, поки інша триває, тож тривогу визначаємо лише за масивом.
 * Див. https://api.ukrainealarm.com/webhook-message-model.html
 */
export function parseWebhook(body: unknown): AlertEvent | null {
  if (typeof body !== "object" || body === null) return null;
  const message = body as Record<string, unknown>;

  const createdAt = parseTime(message.createdAt);
  const levels = message.activeAlertLevels;
  if (createdAt === null || typeof message.alarmType !== "string") return null;
  if (levels !== null && levels !== undefined && !Array.isArray(levels)) return null;
  if (message.regionId === undefined || message.regionId === null) return null;

  const parsed = parseLevels(levels, createdAt);
  return {
    regionId: toAppRegionId(message.regionId),
    alarmType: message.alarmType,
    status: typeof message.status === "string" ? message.status : "",
    active: parsed.length > 0,
    levels: parsed,
    createdAt,
  };
}

/**
 * `activeAlertLevels` → наші рівні. Непорожній масив завжди дає непорожній результат:
 * зіпсований чи незнайомий запис стає червоним рівнем, бо «тривога, рівень невідомий»
 * безпечніше трактувати як найсуворіший рівень, ніж загубити тривогу.
 */
export function parseLevels(raw: unknown, fallbackSince: number): StoredLevel[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((item) => {
    const entry = typeof item === "object" && item !== null ? (item as Record<string, unknown>) : {};
    const reason = typeof entry.reason === "string" && entry.reason.trim() !== "" ? entry.reason : null;
    return { level: toLevel(entry.alertLevel), since: parseTime(entry.createdAt) ?? fallbackSince, reason };
  });
}

function toLevel(value: unknown): Level {
  return typeof value === "string" && value.toLowerCase() === "yellow" ? "yellow" : "red";
}

// ---------------------------------------------------------------------------
// Прямі запити до API
// ---------------------------------------------------------------------------

export type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

export type ApiFailure = { ok: false; status: number | null; detail?: string };

export type StatusResult = { ok: true; index: string } | ApiFailure;

export type SnapshotResult = { ok: true; regions: Map<string, RegionState> } | ApiFailure;

/**
 * Номер останньої зміни (`/api/v3/alerts/status`). Найдешевший запит: кілька байтів,
 * а відповідає на питання «чи змінилося щось відтоді, як ми дивилися».
 */
export async function fetchStatus(token: string, fetchImpl: Fetcher = fetch): Promise<StatusResult> {
  const response = await get("alerts/status", token, fetchImpl);
  if (!response.ok) return response;

  // Число більше за 2^53: JSON.parse його спотворить (639258728888077549 стало б
  // ...077550), і ми вважали б, що стан змінився, хоча він той самий. Тому беремо цифри з тексту.
  const match = /"lastActionIndex"\s*:\s*"?(\d+)"?/.exec(response.body);
  return match
    ? { ok: true, index: match[1] }
    : { ok: false, status: response.status, detail: "malformed" };
}

/**
 * Повний список тривог. Вебхук надсилає лише зміни, тож з чогось треба почати —
 * і сюди ж повертаємося, коли зрозуміли, що якусь подію пропустили.
 */
export async function fetchSnapshot(token: string, fetchImpl: Fetcher = fetch): Promise<SnapshotResult> {
  const response = await get("alerts", token, fetchImpl);
  if (!response.ok) return response;

  let body: unknown = null;
  try {
    body = JSON.parse(response.body);
  } catch {
    // parseSnapshot нижче поверне null.
  }
  const regions = parseSnapshot(body);
  return regions
    ? { ok: true, regions }
    : { ok: false, status: response.status, detail: "malformed" };
}

/** Сира відповідь для дослідів ліміту: нас цікавлять код і заголовки, а не дані. */
export interface RawCall {
  status: number | null;
  took_ms: number;
  bytes: number;
  headers: Record<string, string>;
  /** Початок тіла — лише коли відповідь не 200, бо 200 — це сотні кілобайтів тривог. */
  body_head?: string;
  error?: string;
}

/** Один запит до ukrainealarm без розбору — для `/lab/call`. */
export async function fetchRaw(path: string, token: string, fetchImpl: Fetcher = fetch): Promise<RawCall> {
  const startedAt = Date.now();
  try {
    const response = await fetchImpl(`${API_BASE}/${path}`, {
      headers: { Authorization: token, Accept: "application/json", "User-Agent": USER_AGENT },
    });
    const body = await response.text().catch(() => "");
    return {
      status: response.status,
      took_ms: Date.now() - startedAt,
      bytes: body.length,
      headers: Object.fromEntries(response.headers),
      ...(response.status === 200 ? {} : { body_head: body.slice(0, 300) }),
    };
  } catch (error) {
    return { status: null, took_ms: Date.now() - startedAt, bytes: 0, headers: {}, error: String(error) };
  }
}

async function get(
  path: string,
  token: string,
  fetchImpl: Fetcher,
): Promise<{ ok: true; status: number; body: string } | ApiFailure> {
  let response: Response;
  try {
    response = await fetchImpl(`${API_BASE}/${path}`, {
      headers: {
        // Ключ живе лише тут, на сервері, — у застосунок він не потрапляє.
        Authorization: token,
        Accept: "application/json",
        "User-Agent": USER_AGENT,
      },
    });
  } catch {
    return { ok: false, status: null };
  }

  const body = await response.text().catch(() => "");
  if (!response.ok) return { ok: false, status: response.status, detail: body.slice(0, 200) };
  return { ok: true, status: response.status, body };
}

/**
 * Дістає з `/api/v3/alerts` регіони з активною повітряною тривогою та її рівнями.
 * У відповіді є лише регіони, де щось триває; решта — чисті.
 *
 * Зіпсований окремий запис пропускаємо, а не валимо все: пропущена тривога означає,
 * що будильник задзвонить, — безпечний бік помилки.
 */
export function parseSnapshot(body: unknown): Map<string, RegionState> | null {
  if (!Array.isArray(body)) return null;

  const regions = new Map<string, RegionState>();
  for (const item of body) {
    if (typeof item !== "object" || item === null) continue;
    const region = item as Record<string, unknown>;
    if (!Array.isArray(region.activeAlerts)) continue;

    for (const raw of region.activeAlerts) {
      if (typeof raw !== "object" || raw === null) continue;
      const alert = raw as Record<string, unknown>;
      if (alert.type !== AIR) continue;

      const id = toAppRegionId(alert.regionId ?? region.regionId);
      if (id === null) continue;
      const changedAt = parseTime(alert.lastUpdate) ?? parseTime(region.lastUpdate) ?? 0;
      const levels = parseLevels(alert.activeAlertLevels, changedAt);
      // Тривога є, а рівнів у записі немає — рахуємо червоною, а не чистою.
      regions.set(id, {
        active: true,
        changedAt,
        levels: levels.length > 0 ? levels : [{ level: "red", since: changedAt, reason: null }],
      });
    }
  }
  return regions;
}

function parseTime(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const time = Date.parse(value);
  return Number.isNaN(time) ? null : time;
}

// ---------------------------------------------------------------------------
// Номер останньої зміни
// ---------------------------------------------------------------------------

/** .NET `DateTime.Ticks` (100 нс від 0001-01-01) у момент 1970-01-01 UTC. */
const TICKS_AT_UNIX_EPOCH = 621_355_968_000_000_000n;

/**
 * Час останньої зміни, зашитий у `lastActionIndex`, мс.
 *
 * ukrainealarm цього не документує, але номер — це .NET `DateTime.Ticks` моменту зміни
 * за UTC: номер, отриманий 2026-09-24 о 23:21:01, розшифровується як 23:20:58.
 * Якщо вони колись змінять формат, функція поверне `null` (число поза розумними межами),
 * і проксі просто повернеться до повного завантаження списку — частіше, але правильно.
 */
export function indexToTime(index: string): number | null {
  if (!/^\d{17,20}$/.test(index)) return null;
  const ms = Number((BigInt(index) - TICKS_AT_UNIX_EPOCH) / 10_000n);
  return ms > Date.UTC(2022, 0, 1) && ms < Date.UTC(2100, 0, 1) ? ms : null;
}
