import { DurableObject } from "cloudflare:workers";
import { fetchActiveAlerts } from "./alerts-in-ua";
import { AlertsCache } from "./cache";
import { MockUpstream, type MockScenario, type MockState } from "./mock";
import type { AlertsResponse } from "./types";
import type { Env } from "./index";

const MOCK_STATE_KEY = "mock";

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
 * Кешу сховище не потрібне — від Durable Object потрібна лише гарантія єдиності,
 * а вивантажений кеш наступний запит просто наповнить заново.
 *
 * А от сценарій підробки (MOCK=1) зберігати доводиться: об'єкт засинає між запитами,
 * і оголошена вручну тривога зникала б разом із його пам'яттю — сама собою обертаючись
 * на відбій. Саме на це й натрапили під час першої перевірки на телефоні.
 */
export class AlertsHub extends DurableObject<Env> {
  private readonly cache: AlertsCache;
  private readonly mock: MockUpstream | null;
  private mockRestored: Promise<void> | null = null;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    if (env.MOCK === "1" || env.MOCK === "true") {
      const mock = new MockUpstream();
      this.mock = mock;
      this.cache = new AlertsCache({
        fetchAlerts: async () => {
          await this.restoreMock();
          return mock.fetch();
        },
      });
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

  async setMock(
    scenario: MockScenario,
    uid?: string,
  ): Promise<{ scenario: MockScenario; uid: string }> {
    if (!this.mock) throw new Error("mock is disabled");
    await this.restoreMock();
    this.mock.set(scenario, uid);
    await this.ctx.storage.put(MOCK_STATE_KEY, this.mock.snapshot());
    this.cache.expire();
    return this.mock.state();
  }

  async mockState(): Promise<{ scenario: MockScenario; uid: string }> {
    if (!this.mock) throw new Error("mock is disabled");
    await this.restoreMock();
    return this.mock.state();
  }

  /** Читаємо збережений сценарій один раз на життя об'єкта. */
  private restoreMock(): Promise<void> {
    if (!this.mock) return Promise.resolve();
    this.mockRestored ??= this.ctx.storage
      .get<MockState>(MOCK_STATE_KEY)
      .then((saved) => {
        if (saved) this.mock!.restore(saved);
      });
    return this.mockRestored;
  }
}
