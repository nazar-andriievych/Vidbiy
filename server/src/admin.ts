/**
 * Службові маршрути (`/stats`, `/log`, `/mock`) — лише для нас. Телефону вони не потрібні,
 * а назовні показували б внутрішню кухню проксі.
 *
 * Доступ — з паролем у заголовку `Authorization: Bearer <ADMIN_TOKEN>` або з локального
 * `wrangler dev` (адреса localhost / 127.0.0.1: у Cloudflare такий запит до воркера не дійде).
 * Пароль не заданий — у хмарі маршрути закриті для всіх.
 */
export function isAdmin(request: Request, token: string | undefined): boolean {
  const host = new URL(request.url).hostname;
  if (host === "localhost" || host === "127.0.0.1") return true;
  if (!token) return false;
  return sameText(request.headers.get("authorization") ?? "", `Bearer ${token}`);
}

/** Порівняння за сталий час: за часом відповіді не вгадати пароль по символу. */
function sameText(given: string, expected: string): boolean {
  if (given.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= given.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
}
