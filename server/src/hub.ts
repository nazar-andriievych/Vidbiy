import { DurableObject } from "cloudflare:workers";
import { runCheck } from "./check";
import { CallBudget, type BudgetState, type CallPath } from "./budget";
import { MockAlerts, type MockScenario, type MockState } from "./mock";
import { RetryProbe, type ProbeState } from "./probe";
import { AlertBoard, type Meta, type ReceiveOutcome, type StateStore } from "./state";
import {
  fetchSnapshot,
  fetchStatus,
  type ApiFailure,
  type SnapshotResult,
  type StatusResult,
} from "./ukrainealarm";
import type { AlertEvent, AlertsResponse, RegionState } from "./types";
import type { Env } from "./index";

const MOCK_STATE_KEY = "mock";
const META_KEY = "meta";
const BUDGET_KEY = "budget";
const PROBE_KEY = "probe";
const REGION_PREFIX = "r:";

/**
 * Як часто об'єкт прокидається сам, щоб вирішити, чи пора перевірити тишу.
 * Прокидання — це не запит до ukrainealarm: запит буде, лише якщо `needsCheck` скаже так.
 */
const ALARM_INTERVAL_MS = 30_000;

/** Скільки ключів Durable Object приймає за один `put`. */
const PUT_BATCH = 128;

/**
 * Єдина на весь світ точка, де живе стан тривог.
 *
 * Durable Object — це іменований об'єкт, якого в усьому світі існує рівно один
 * екземпляр. Вебхуки ukrainealarm і запити телефонів сходяться в нього, тож усі
 * бачать один і той самий стан, скільки б ізолятів воркера не підняв Cloudflare.
 * Стан лежить у сховищі об'єкта, бо об'єкт засинає між запитами.
 *
 * Звідки береться стан:
 * 1. **Знімок** — один раз на початку: вебхук надсилає лише зміни.
 * 2. **Вебхуки** — основне джерело, кожна зміна за секунди.
 * 3. **Перевірка на тиші** — якщо хвилину не було жодного підтвердження, об'єкт сам
 *    питає номер останньої зміни. Той самий — стан правильний, дані знову свіжі.
 *    Інший — ми щось пропустили, і завантажуємо повний список.
 *
 * Усі запити до ukrainealarm проходять через `call()` і запобіжник `CallBudget`.
 */
export class AlertsHub extends DurableObject<Env> {
  private readonly board: AlertBoard;
  private readonly mock: MockAlerts | null;
  private mockRestored: Promise<void> | null = null;
  private extrasRestored: Promise<void> | null = null;
  private budget = new CallBudget();
  private probe = new RetryProbe();
  private checking: Promise<void> | null = null;
  private alarmArmed = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.board = new AlertBoard(storageFor(ctx.storage));
    this.mock = env.MOCK === "1" || env.MOCK === "true" ? new MockAlerts() : null;
  }

  async getAlerts(): Promise<AlertsResponse> {
    if (this.mock) {
      await this.restoreMock();
      return this.mock.response(Date.now());
    }
    await this.restore();
    await this.armAlarm();
    // Поки знімка немає, телефон, що спитав, може дочекатися першого завантаження.
    // Частоту стримують nextCheckAt і запобіжник, скільки б телефонів не питало.
    if (this.board.syncedAt === null) await this.check();
    return this.board.response(Date.now());
  }

  async receive(
    event: AlertEvent,
    receivedAt: number,
    bodyHash: string,
  ): Promise<{ outcome: ReceiveOutcome; refuse: boolean }> {
    await this.restore();
    const outcome = await this.board.receive(event, receivedAt);
    const { refuse } = this.probe.onDelivery(bodyHash, receivedAt, this.probeEvery());
    await this.ctx.storage.put(PROBE_KEY, this.probe.snapshot());
    await this.armAlarm();
    // Перший вебхук — теж привід завантажити знімок, якщо його ще немає.
    if (this.board.syncedAt === null) await this.check();
    return { outcome, refuse };
  }

  async stats() {
    await this.restore();
    await this.armAlarm();
    const now = Date.now();
    return {
      ...this.board.stats(now),
      upstream: {
        mode: this.env.FAKE_UPSTREAM === "1" ? "fake" : this.env.UKRAINEALARM_TOKEN ? "live" : "no_token",
        ...this.budget.stats(now),
      },
      retry_probe: this.probe.stats(this.probeEvery()),
    };
  }

  /** Вбудований будильник Durable Object: прокидається сам, навіть коли ніхто не питає. */
  async alarm(): Promise<void> {
    if (this.mock) return;
    await this.restore();
    try {
      await this.check();
    } finally {
      // Будильник одноразовий: наступний ставимо самі, навіть якщо перевірка впала.
      await this.ctx.storage.setAlarm(Date.now() + ALARM_INTERVAL_MS);
      this.alarmArmed = true;
    }
  }

  private async armAlarm(): Promise<void> {
    if (this.mock || this.alarmArmed) return;
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(Date.now() + ALARM_INTERVAL_MS);
    }
    this.alarmArmed = true;
  }

  /** Одна перевірка за раз, хоч скільки викликів прийшло одночасно. */
  private check(): Promise<void> {
    this.checking ??= this.checkOnce().finally(() => {
      this.checking = null;
    });
    return this.checking;
  }

  private async checkOnce(): Promise<void> {
    const outcome = await runCheck(this.board, {
      status: () => this.call("alerts/status", fetchStatus),
      snapshot: () => this.call("alerts", fetchSnapshot),
      now: Date.now,
    });
    if (outcome === "reloaded") console.log("стан перезавантажено з API");
  }

  /**
   * Єдиний шлях до ukrainealarm. Спершу запобіжник, потім — ключ.
   * Без ключа або з `FAKE_UPSTREAM=1` запит лише рахується, назовні нічого не йде.
   */
  private async call<T extends StatusResult | SnapshotResult>(
    path: CallPath,
    request: (token: string) => Promise<T>,
  ): Promise<T | ApiFailure> {
    const now = Date.now();
    if (!this.budget.take(now)) {
      this.budget.record(now, path, "over_budget");
      await this.saveBudget();
      console.error(`запобіжник не пустив запит ${path}: перевищено ліміт`);
      return { ok: false, status: null, detail: "over_budget" };
    }

    if (this.env.FAKE_UPSTREAM === "1") {
      this.budget.record(now, path, "ok");
      await this.saveBudget();
      return (path === "alerts/status"
        ? { ok: true, index: "fake" }
        : { ok: true, regions: new Map<string, RegionState>() }) as T;
    }

    const token = this.env.UKRAINEALARM_TOKEN;
    if (!token) {
      this.budget.record(now, path, "no_token");
      await this.saveBudget();
      return { ok: false, status: null, detail: "no_token" };
    }

    const result = await request(token);
    this.budget.record(Date.now(), path, result.ok ? "ok" : "failed", result.ok ? undefined : result.status);
    await this.saveBudget();
    if (!result.ok) {
      // ukrainealarm, за спостереженнями інших розробників, відповідає 401 і на
      // перевищення ліміту, а не лише на поганий ключ.
      console.warn(`${path}: status=${result.status ?? "-"} detail=${result.detail ?? "-"}`);
    }
    return result;
  }

  private saveBudget(): Promise<void> {
    return this.ctx.storage.put(BUDGET_KEY, this.budget.snapshot());
  }

  private probeEvery(): number {
    const every = Number(this.env.WEBHOOK_RETRY_PROBE ?? 0);
    return Number.isInteger(every) && every > 0 ? every : 0;
  }

  /** Стан, запобіжник і перевірку повторів читаємо зі сховища раз на життя об'єкта. */
  private restore(): Promise<void> {
    this.extrasRestored ??= Promise.all([
      this.board.restore(),
      this.ctx.storage.get<BudgetState>(BUDGET_KEY),
      this.ctx.storage.get<ProbeState>(PROBE_KEY),
    ]).then(([, budget, probe]) => {
      if (budget) this.budget = new CallBudget(budget);
      if (probe) this.probe = new RetryProbe(probe);
    });
    return this.extrasRestored;
  }

  async setMock(scenario: MockScenario, uid?: string): Promise<MockState> {
    if (!this.mock) throw new Error("mock is disabled");
    await this.restoreMock();
    this.mock.set(scenario, uid);
    await this.ctx.storage.put(MOCK_STATE_KEY, this.mock.state());
    return this.mock.state();
  }

  async mockState(): Promise<MockState> {
    if (!this.mock) throw new Error("mock is disabled");
    await this.restoreMock();
    return this.mock.state();
  }

  /** Читаємо збережений сценарій один раз на життя об'єкта. */
  private restoreMock(): Promise<void> {
    if (!this.mock) return Promise.resolve();
    this.mockRestored ??= this.ctx.storage.get<MockState>(MOCK_STATE_KEY).then((saved) => {
      if (saved) this.mock!.restore(saved);
    });
    return this.mockRestored;
  }
}

/**
 * Кожен регіон — окремий ключ: вебхук змінює один регіон, і переписувати через це
 * весь стан (до 1600 регіонів) було б марнотратно.
 */
function storageFor(storage: DurableObjectStorage): StateStore {
  return {
    async read() {
      const [entries, meta] = await Promise.all([
        storage.list<RegionState>({ prefix: REGION_PREFIX }),
        storage.get<Meta>(META_KEY),
      ]);
      const regions = new Map<string, RegionState>();
      for (const [key, state] of entries) regions.set(key.slice(REGION_PREFIX.length), state);
      return { regions, meta };
    },
    async writeRegions(changes) {
      const entries = [...changes].map(([id, state]) => [REGION_PREFIX + id, state] as const);
      for (let i = 0; i < entries.length; i += PUT_BATCH) {
        await storage.put(Object.fromEntries(entries.slice(i, i + PUT_BATCH)));
      }
    },
    writeMeta: (meta) => storage.put(META_KEY, meta),
  };
}
