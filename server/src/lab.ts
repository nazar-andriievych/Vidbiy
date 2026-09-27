/**
 * Досліди лімітів ukrainealarm (див. «Досліди ліміту» в README).
 *
 * `/lab/call` робить один запит до ukrainealarm із воркера й віддає сиру відповідь.
 * Так той самий розклад запитів можна прогнати з ПК і з Cloudflare і порівняти,
 * чи ліміт залежить від адреси, з якої ми ходимо.
 */

export const LAB_PATHS = ["alerts", "alerts/status"] as const;
export type LabPath = (typeof LAB_PATHS)[number];

export type LabAccess = { ok: true; path: LabPath } | { ok: false; status: 400 | 403 | 404; error: string };

/**
 * Хто й що може спитати через `/lab/call`.
 *
 * Адреса публічна, а кожен запит витрачає ліміт нашого ключа. Тому без секрету
 * `LAB_KEY` маршруту для світу не існує, а без правильного заголовка — відмова.
 */
export function labAccess(labKey: string | undefined, headerKey: string | null, path: string | null): LabAccess {
  if (!labKey) return { ok: false, status: 404, error: "not_found" };
  if (headerKey === null || !sameText(headerKey, labKey)) return { ok: false, status: 403, error: "forbidden" };
  if (!LAB_PATHS.includes(path as LabPath)) return { ok: false, status: 400, error: "bad_path" };
  return { ok: true, path: path as LabPath };
}

/** Порівняння за сталий час: щоб ключ не можна було підбирати за часом відповіді. */
function sameText(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  let diff = left.length ^ right.length;
  for (let i = 0; i < Math.max(left.length, right.length); i++) diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return diff === 0;
}

/** `UPSTREAM_PAUSED=1`: воркер сам до ukrainealarm не ходить, вебхуки приймає як завжди. */
export function upstreamPaused(value: string | undefined): boolean {
  return value === "1" || value === "true";
}
