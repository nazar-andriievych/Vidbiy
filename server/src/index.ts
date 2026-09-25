import { isMockScenario } from "./mock";
import { sha256Hex } from "./probe";
import { importPublicKey, parseWebhook, verifyWebhook, WEBHOOK_PUBLIC_KEY_PEM } from "./ukrainealarm";
import type { AlertsHub } from "./hub";
import type { AlertsResponse } from "./types";

export { AlertsHub } from "./hub";

export interface Env {
  /** Секрет: `wrangler secret put UKRAINEALARM_TOKEN`. Потрібен лише для початкового знімка. */
  UKRAINEALARM_TOKEN?: string;
  /** `"1"` вмикає підробку стану тривог і маршрут `/mock`. Лише для розробки. */
  MOCK?: string;
  /**
   * `"1"` — сухий прогін: запити до ukrainealarm лише рахуються й отримують вигадану
   * відповідь «тривог немає», назовні нічого не йде. Щоб побачити розклад запитів
   * на розгорнутому воркері, не ризикуючи ключем. Див. `budget.ts`.
   */
  FAKE_UPSTREAM?: string;
  /** `N` — кожну N-ту подію вебхука відповідаємо 503, щоб побачити, чи повторять. Див. `probe.ts`. */
  WEBHOOK_RETRY_PROBE?: string;
  /** Єдина точка, де живе стан тривог. Див. `hub.ts`. */
  ALERTS_HUB: DurableObjectNamespace<AlertsHub>;
}

/** Ім'я єдиного екземпляра: усі запити світу сходяться в нього. */
const HUB_NAME = "global";

/** Справжній вебхук — це кількасот байтів; більше приймати немає сенсу. */
const MAX_WEBHOOK_BYTES = 64 * 1024;

/** Ключ імпортуємо раз на ізолят: це розбір PEM, а не мережевий запит. */
let webhookKey: Promise<CryptoKey> | null = null;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/mock" && mockEnabled(env)) {
      return handleMock(url, env);
    }

    if (url.pathname === "/v2/webhook") {
      return request.method === "POST"
        ? handleWebhook(request, env)
        : json({ error: "method_not_allowed" }, 405);
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return json({ error: "method_not_allowed" }, 405);
    }

    switch (url.pathname) {
      case "/v2/alerts":
        return json(await getAlerts(env));
      case "/v2/stats":
        return json(await hub(env).stats());
      case "/health":
        return json({
          ok: true,
          mock: mockEnabled(env),
          configured: mockEnabled(env) || Boolean(env.UKRAINEALARM_TOKEN),
          fake_upstream: env.FAKE_UPSTREAM === "1",
        });
      default:
        return json({ error: "not_found" }, 404);
    }
  },
};

function hub(env: Env): DurableObjectStub<AlertsHub> {
  return env.ALERTS_HUB.get(env.ALERTS_HUB.idFromName(HUB_NAME));
}

async function getAlerts(env: Env): Promise<AlertsResponse> {
  try {
    return await hub(env).getAlerts();
  } catch {
    // Єдина точка виявилася недосяжною. Чесно кажемо «даних немає»,
    // щоб застосунок задзвонив за fail-safe, а не мовчав через нашу поломку.
    return { v: 2, active: null, heard_at: null, age_seconds: null };
  }
}

/**
 * Приймає подію від ukrainealarm.
 *
 * Адреса публічна, тож надіслати сюди «відбій» може будь-хто. Тому підпис перевіряємо
 * тут, ще до Durable Object: підробка не дістанеться до стану й не витрачатиме
 * безкоштовні ліміти сховища.
 */
async function handleWebhook(request: Request, env: Env): Promise<Response> {
  const raw = await request.text();
  if (raw.length > MAX_WEBHOOK_BYTES) return json({ error: "too_large" }, 413);

  webhookKey ??= importPublicKey(WEBHOOK_PUBLIC_KEY_PEM);
  const check = await verifyWebhook(
    request.headers,
    raw,
    await webhookKey,
    Math.floor(Date.now() / 1000),
  );
  if (!check.ok) {
    console.warn(
      `вебхук відхилено: ${check.reason} key-id=${request.headers.get("x-webhook-key-id") ?? "-"}`,
    );
    return json({ error: check.reason }, 401);
  }

  let body: unknown = null;
  try {
    body = JSON.parse(raw);
  } catch {
    // Нижче parseWebhook поверне null і ми відповімо 400.
  }
  const event = parseWebhook(body);
  if (!event) {
    console.warn(`вебхук із незрозумілим тілом: ${raw.slice(0, 200)}`);
    return json({ error: "malformed" }, 400);
  }

  try {
    const { outcome, refuse } = await hub(env).receive(event, Date.now(), await sha256Hex(raw));
    if (refuse) {
      // Подію застосовано; відмова навмисна — перевіряємо, чи повторять доставку.
      return json({ error: "retry_probe", outcome }, 503);
    }
    return json({ ok: true, outcome });
  } catch (error) {
    // Не 200: якщо ukrainealarm повторює доставку, хай спробує ще раз.
    console.error(`вебхук не збережено: ${String(error)}`);
    return json({ error: "unavailable" }, 503);
  }
}

function mockEnabled(env: Env): boolean {
  return env.MOCK === "1" || env.MOCK === "true";
}

async function handleMock(url: URL, env: Env): Promise<Response> {
  const scenario = url.searchParams.get("scenario");
  if (scenario === null) {
    return json(await hub(env).mockState());
  }
  if (!isMockScenario(scenario)) {
    return json({ error: "bad_scenario", expected: ["clear", "alert", "down"] }, 400);
  }
  return json(await hub(env).setMock(scenario, url.searchParams.get("uid") ?? undefined));
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      // Проміжний кеш повернув би стару відповідь зі старим age_seconds,
      // і вік даних перестав би відповідати дійсності.
      "cache-control": "no-store",
    },
  });
}
