import { DurableObject } from "cloudflare:workers";
import { CallBudget, type BudgetState, type CallPath } from "./budget";
import { MockAlerts, type MockOptions, type MockScenario, type MockState } from "./mock";
import { ReloadSchedule, type ReloadState } from "./reload";
import { AlertBoard, type Meta, type ReceiveOutcome, type StateStore } from "./state";
import { EventLog, type LogEntry, type LogStore } from "./eventlog";
import { fetchSnapshot, type ApiFailure, type SnapshotResult } from "./ukrainealarm";
import type { AlertEvent, AlertsResponse, RegionState } from "./types";
import type { Env } from "./index";

const MOCK_STATE_KEY = "mock";
const META_KEY = "meta";
const BUDGET_KEY = "budget";
const RELOAD_KEY = "reload";
/** Ключі прибраних дослідів (2026-09-27): стираємо, якщо ще лежать. */
const LEGACY_KEYS = ["probe", "compare"];
const REGION_PREFIX = "r:";
const LOG_PREFIX = "log:";

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
 * 1. **Вебхуки** — кожна зміна за секунди.
 * 2. **Знімок `/alerts`** — щохвилини (див. `reload.ts`): повна картина, виправляє
 *    загублені вебхуки і підтверджує свіжість. Вебхуки продовжують свіжість
 *    не довше ніж на 15 хв після останнього знімка (FR-30, `state.ts`).
 *
 * Усі запити до ukrainealarm проходять через `call()` і запобіжник `CallBudget`.
 */
export class AlertsHub extends DurableObject<Env> {
  private readonly board: AlertBoard;
  private readonly mock: MockAlerts | null;
  private mockRestored: Promise<void> | null = null;
  private extrasRestored: Promise<void> | null = null;
  private budget = new CallBudget();
  private reload = ReloadSchedule.restore(undefined);
  private checking: Promise<void> | null = null;
  private alarmArmed = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.board = new AlertBoard(storageFor(ctx.storage), new EventLog(logStorageFor(ctx.storage)));
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
    // Частоту стримують розклад знімків і запобіжник, скільки б телефонів не питало.
    if (this.board.syncedAt === null) await this.check();
    return this.board.response(Date.now());
  }

  async receive(event: AlertEvent, receivedAt: number): Promise<ReceiveOutcome> {
    await this.restore();
    const outcome = await this.board.receive(event, receivedAt);
    await this.armAlarm();
    // Перший вебхук — теж привід завантажити знімок, якщо його ще немає.
    if (this.board.syncedAt === null) await this.check();
    return outcome;
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
      reload: this.reload.stats(now),
    };
  }

  /** Журнал подій (`/log`): за останні `hours` год, за потреби — лише один регіон. */
  async log(hours: number, region?: string): Promise<LogEntry[]> {
    await this.restore();
    await this.armAlarm();
    return this.board.readLog(Date.now(), hours, region);
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
    const asOf = Date.now();
    if (!this.reload.due(asOf)) return;

    const started = Date.now();
    const snapshot = await this.call("alerts", fetchSnapshot);
    const tookMs = Date.now() - started;
    const wasFast = this.reload.fast(asOf);
    this.reload.record(snapshot.ok, asOf);
    if (wasFast && !this.reload.fast(asOf)) {
      console.error(`щохвилинний /alerts вимкнено на годину: ${this.reload.snapshot().tripped?.reason}`);
    }
    if (snapshot.ok) await this.board.loadSnapshot(snapshot.regions, asOf, tookMs);
    else await this.board.snapshotFailed(asOf, tookMs, snapshot.status, snapshot.detail);
    await this.ctx.storage.put(RELOAD_KEY, this.reload.snapshot());
  }

  /**
   * Єдиний шлях до ukrainealarm. Спершу запобіжник, потім — ключ.
   * Без ключа або з `FAKE_UPSTREAM=1` запит лише рахується, назовні нічого не йде.
   */
  private async call<T extends SnapshotResult>(
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
      return { ok: true, regions: new Map<string, RegionState>() } as T;
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

  /** Стан, запобіжник і розклад знімків читаємо зі сховища раз на життя об'єкта. */
  private restore(): Promise<void> {
    this.extrasRestored ??= Promise.all([
      this.board.restore(),
      this.ctx.storage.get<BudgetState>(BUDGET_KEY),
      this.ctx.storage.get<ReloadState>(RELOAD_KEY),
      this.ctx.storage.get(LEGACY_KEYS),
    ]).then(async ([, budget, reload, legacy]) => {
      if (budget) this.budget = new CallBudget(budget);
      this.reload = ReloadSchedule.restore(reload);
      if (legacy.size > 0) await this.ctx.storage.delete([...legacy.keys()]);
    });
    return this.extrasRestored;
  }

  async setMock(scenario: MockScenario, options: MockOptions): Promise<MockState> {
    if (!this.mock) throw new Error("mock is disabled");
    await this.restoreMock();
    this.mock.set(scenario, Date.now(), options);
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

/** Журнал подій: один ключ на годину (див. `eventlog.ts`). */
function logStorageFor(storage: DurableObjectStorage): LogStore {
  return {
    readHour: (hour) => storage.get<LogEntry[]>(LOG_PREFIX + hour),
    writeHour: (hour, entries) => storage.put(LOG_PREFIX + hour, entries),
    async readFrom(fromHour) {
      const buckets = await storage.list<LogEntry[]>({ prefix: LOG_PREFIX, start: LOG_PREFIX + fromHour });
      return [...buckets].map(([key, entries]) => [key.slice(LOG_PREFIX.length), entries]);
    },
    async deleteBefore(hour) {
      const old = await storage.list({ prefix: LOG_PREFIX, end: LOG_PREFIX + hour });
      const keys = [...old.keys()];
      for (let i = 0; i < keys.length; i += PUT_BATCH) await storage.delete(keys.slice(i, i + PUT_BATCH));
    },
  };
}
