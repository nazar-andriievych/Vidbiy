import { DurableObject } from "cloudflare:workers";
import { fetchActiveAlerts } from "./alerts-in-ua";
import { AlertsCache } from "./cache";
import { MockUpstream, type MockScenario } from "./mock";
import type { AlertsResponse } from "./types";
import type { Env } from "./index";

/**
 * Єдина на весь світ точка, яка ходить до alerts.in.ua.
 *
 * Навіщо: кеш у пам'яті воркера живе окремо в кожному ізоляті, а Cloudflare
 * піднімає їх стільки, скільки вважає за потрібне. Кожен чесно тримав свої
 * 4 запити/хв — але разом вони пробивали жорсткий ліміт alerts.in.ua у 12/хв,
 * і ми ловили 429.
 *
 * Durable Object — це іменований об'єкт, якого в усьому світі існує рівно один
 * екземпляр. Усі воркери звертаються до нього, тож обмежувач частоти знову
 * стає одним на всіх: 4 запити/хв до апстріму, скільки б не було користувачів.
 *
 * Сховище (SQLite) нам не потрібне — від Durable Object потрібна лише
 * гарантія єдиності. Стан живе в пам'яті; якщо об'єкт вивантажать за
 * непотрібністю, наступний запит просто наповнить кеш заново.
 */
export class AlertsHub extends DurableObject<Env> {
  private readonly cache: AlertsCache;
  private readonly mock: MockUpstream | null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    if (env.MOCK === "1" || env.MOCK === "true") {
      this.mock = new MockUpstream();
      this.cache = new AlertsCache({ fetchAlerts: this.mock.fetch });
      return;
    }

    this.mock = null;
    const token = env.ALERTS_IN_UA_TOKEN;
    this.cache = new AlertsCache({
      fetchAlerts: token
        ? () => fetchActiveAlerts(token)
        : // Токен не заданий — це помилка розгортання, а не збій апстріму.
          // Віддаємо чесне «даних немає», щоб застосунок спрацював за fail-safe.
          async () => ({ ok: false, status: null, reason: "network" }),
    });
  }

  getAlerts(): Promise<AlertsResponse> {
    return this.cache.get();
  }

  setMock(scenario: MockScenario, uid?: string): { scenario: MockScenario; uid: string } {
    if (!this.mock) throw new Error("mock is disabled");
    this.mock.set(scenario, uid);
    this.cache.expire();
    return this.mock.state();
  }

  mockState(): { scenario: MockScenario; uid: string } {
    if (!this.mock) throw new Error("mock is disabled");
    return this.mock.state();
  }
}
