import { isMockScenario } from "./mock";
import type { AlertsHub } from "./hub";
import type { AlertsResponse } from "./types";

export { AlertsHub } from "./hub";

export interface Env {
  /** Секрет: `wrangler secret put ALERTS_IN_UA_TOKEN`. */
  ALERTS_IN_UA_TOKEN?: string;
  /** `"1"` вмикає підробку апстріму та маршрут `/mock`. Лише для розробки. */
  MOCK?: string;
  /** Єдина точка, яка ходить до alerts.in.ua. Див. `hub.ts`. */
  ALERTS_HUB: DurableObjectNamespace<AlertsHub>;
}

/** Ім'я єдиного екземпляра: усі запити світу сходяться в нього. */
const HUB_NAME = "global";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/mock" && mockEnabled(env)) {
      return handleMock(url, env);
    }

    if (request.method !== "GET" && request.method !== "HEAD") {
      return json({ error: "method_not_allowed" }, 405);
    }

    switch (url.pathname) {
      case "/v1/alerts":
        return json(await getAlerts(env));
      case "/health":
        return json({
          ok: true,
          mock: mockEnabled(env),
          configured: mockEnabled(env) || Boolean(env.ALERTS_IN_UA_TOKEN),
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
    return {
      v: 1,
      upstream_ok: false,
      upstream_status: null,
      fetched_at: null,
      age_seconds: null,
      alerts: null,
    };
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
