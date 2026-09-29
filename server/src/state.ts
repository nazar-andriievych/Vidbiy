import { AIR } from "./ukrainealarm";
import type { AlertEvent, AlertsResponse, RegionAlert, RegionState } from "./types";

/** Скільки останніх подій тримаємо для `/stats`. */
const RECENT_LIMIT = 30;

/**
 * Наскільки вебхуки продовжують свіжість після останнього успішного знімка (FR-30).
 *
 * Лише знімок підтверджує повну картину: тиша по конкретному регіону нічого не доводить.
 * Вебхуки з будь-яких регіонів доводять, що канал живий, а загублених вебхуків за
 * вимірами не було, — тож короткому збою `/alerts` довіряємо, довгому — ні.
 */
export const WEBHOOK_TRUST_MS = 15 * 60_000;

/**
 * Статистика потоку вебхуків. Заради неї весь експеримент: чи досить самих вебхуків,
 * без регулярних запитів до ukrainealarm.
 *
 * Головне питання — паузи між подіями. Застосунок вважає дані свіжими, поки проксі
 * «чує» ukrainealarm (NFR-1, поріг 3 хв). Подій по всій країні багато, тож потік
 * вебхуків сам може бути пульсом, — але лише якщо паузи довші за 3 хв трапляються рідко.
 */
export interface WebhookStats {
  /** Коли прийшов перший перевірений вебхук, мс. */
  since: number | null;
  /** Перевірені вебхуки, усіх типів і регіонів. */
  received: number;
  /** Змінили стан повітряної тривоги в регіоні. */
  changed: number;
  /** Прийшли пізніше за новішу подію того ж регіону й були відкинуті. */
  outOfOrder: number;
  /** Паузи між сусідніми вебхуками, за тривалістю. */
  gaps: { under1m: number; under3m: number; under10m: number; under30m: number; over30m: number };
  maxGapSeconds: number;
  /** Коли закінчилася найдовша пауза, мс. */
  maxGapEndedAt: number | null;
  /** Затримка доставки: від `createdAt` у ukrainealarm до отримання нами. */
  delay: { count: number; totalSeconds: number; maxSeconds: number };
  recent: RecentEvent[];
}

export interface RecentEvent {
  receivedAt: string;
  regionId: string | null;
  alarmType: string;
  status: string;
  active: boolean;
  delaySeconds: number;
  outcome: ReceiveOutcome;
}

export type ReceiveOutcome = "changed" | "unchanged" | "out_of_order" | "ignored";

/** Усе, крім стану регіонів: воно пишеться одним записом. */
export interface Meta {
  /** Коли завершилося початкове завантаження повного списку, мс. */
  syncedAt: number | null;
  /**
   * Коли стан востаннє підтвердився: перевірений вебхук, перевірка на тиші або знімок.
   * Від нього рахується `age_seconds`, тобто свіжість даних для застосунку.
   */
  heardAt: number | null;
  /** Коли востаннє успішно завантажили знімок `/alerts`. */
  lastCheckAt: number | null;
  /** Коли прийшов останній вебхук — для вимірювання пауз. */
  lastWebhookAt: number | null;
  stats: WebhookStats;
}

export interface StateStore {
  read(): Promise<{ regions: Map<string, RegionState>; meta: Meta | undefined }>;
  writeRegions(changes: Map<string, RegionState>): Promise<void>;
  writeMeta(meta: Meta): Promise<void>;
}

export function emptyStats(): WebhookStats {
  return {
    since: null,
    received: 0,
    changed: 0,
    outOfOrder: 0,
    gaps: { under1m: 0, under3m: 0, under10m: 0, under30m: 0, over30m: 0 },
    maxGapSeconds: 0,
    maxGapEndedAt: null,
    delay: { count: 0, totalSeconds: 0, maxSeconds: 0 },
    recent: [],
  };
}

function emptyMeta(): Meta {
  return {
    syncedAt: null,
    heardAt: null,
    lastCheckAt: null,
    lastWebhookAt: null,
    stats: emptyStats(),
  };
}

/**
 * Стан повітряних тривог по регіонах — чиста логіка без Cloudflare, щоб її можна було
 * тестувати звичайними юніт-тестами. Сховище підставляє `hub.ts`.
 */
export class AlertBoard {
  private regions = new Map<string, RegionState>();
  private meta: Meta = emptyMeta();
  private restored: Promise<void> | null = null;

  constructor(private readonly store?: StateStore) {}

  /** Читаємо збережений стан один раз на життя об'єкта: між запитами він засинає. */
  restore(): Promise<void> {
    if (!this.store) return Promise.resolve();
    this.restored ??= this.store.read().then(({ regions, meta }) => {
      this.regions = regions;
      if (meta) this.meta = withoutLegacy({ ...emptyMeta(), ...meta });
    });
    return this.restored;
  }

  get syncedAt(): number | null {
    return this.meta.syncedAt;
  }

  /**
   * Застосовує одну перевірену подію вебхука.
   *
   * Порядок доставки ніхто не гарантує, тож кожен регіон пам'ятає час останньої
   * застосованої зміни, і старіші події відкидаються. Це ж правило захищає від дублікатів
   * і від повторно надісланих старих повідомлень: запізніле «загроз немає» посеред тривоги
   * змусило б будильник задзвонити саме тоді, коли не треба.
   */
  async receive(event: AlertEvent, receivedAt: number): Promise<ReceiveOutcome> {
    const outcome = this.apply(event);
    this.record(event, receivedAt, outcome);

    if (outcome === "changed" || outcome === "unchanged") {
      await this.store?.writeRegions(new Map([[event.regionId!, this.regions.get(event.regionId!)!]]));
    }
    await this.store?.writeMeta(this.meta);
    return outcome;
  }

  private apply(event: AlertEvent): ReceiveOutcome {
    if (event.regionId === null || event.alarmType !== AIR) return "ignored";

    const previous = this.regions.get(event.regionId);
    if (previous && event.createdAt < previous.changedAt) return "out_of_order";

    this.regions.set(event.regionId, { active: event.active, changedAt: event.createdAt, levels: event.levels });
    return previous?.active === event.active && sameLevels(previous, event) ? "unchanged" : "changed";
  }

  private record(event: AlertEvent, receivedAt: number, outcome: ReceiveOutcome): void {
    const stats = this.meta.stats;
    stats.since ??= receivedAt;
    stats.received++;
    if (outcome === "changed") stats.changed++;
    if (outcome === "out_of_order") stats.outOfOrder++;

    if (this.meta.lastWebhookAt !== null) {
      const gapSeconds = Math.max(0, Math.round((receivedAt - this.meta.lastWebhookAt) / 1000));
      const gaps = stats.gaps;
      if (gapSeconds < 60) gaps.under1m++;
      else if (gapSeconds < 180) gaps.under3m++;
      else if (gapSeconds < 600) gaps.under10m++;
      else if (gapSeconds < 1800) gaps.under30m++;
      else gaps.over30m++;
      if (gapSeconds > stats.maxGapSeconds) {
        stats.maxGapSeconds = gapSeconds;
        stats.maxGapEndedAt = receivedAt;
      }
    }

    // Від'ємна затримка — розбіжність годинників, а не доставка з майбутнього.
    const delaySeconds = Math.round((receivedAt - event.createdAt) / 1000);
    stats.delay.count++;
    stats.delay.totalSeconds += Math.max(0, delaySeconds);
    stats.delay.maxSeconds = Math.max(stats.delay.maxSeconds, delaySeconds);

    stats.recent = [
      {
        receivedAt: new Date(receivedAt).toISOString(),
        regionId: event.regionId,
        alarmType: event.alarmType,
        status: event.status,
        active: event.active,
        delaySeconds,
        outcome,
      },
      ...stats.recent,
    ].slice(0, RECENT_LIMIT);

    this.meta.lastWebhookAt = receivedAt;
    this.meta.heardAt = Math.max(this.meta.heardAt ?? 0, receivedAt);
  }

  /**
   * Накладає повний список тривог, отриманий одним запитом у момент `asOf`.
   *
   * Вебхуки могли прийти ще до завантаження, тож знімок не затирає зміну, новішу за нього:
   * такий регіон лишається як є. Решта регіонів стає такою, як у знімку, — зокрема
   * тривоги, яких у знімку немає, вважаються завершеними.
   */
  async loadSnapshot(snapshot: Map<string, RegionState>, asOf: number): Promise<void> {
    const changes = new Map<string, RegionState>();

    for (const [id, current] of this.regions) {
      if (current.changedAt > asOf || snapshot.has(id) || !current.active) continue;
      changes.set(id, { active: false, changedAt: current.changedAt, levels: [] });
    }
    for (const [id, fresh] of snapshot) {
      const current = this.regions.get(id);
      if (current && current.changedAt > asOf) continue;
      changes.set(id, {
        active: true,
        changedAt: Math.max(fresh.changedAt, current?.changedAt ?? 0),
        levels: fresh.levels,
      });
    }

    // Знімок щохвилини повторює всі активні регіони. Переписувати незмінені — це
    // десятки тисяч записів на добу і ризик вичерпати безкоштовний ліміт сховища,
    // після чого знімки зупинилися б. Тож у сховище йде лише те, що справді змінилось.
    const writes = new Map<string, RegionState>();
    for (const [id, state] of changes) {
      const current = this.regions.get(id);
      if (!current || !sameState(current, state)) writes.set(id, state);
      this.regions.set(id, state);
    }
    this.meta.syncedAt ??= asOf;
    this.meta.heardAt = Math.max(this.meta.heardAt ?? 0, asOf);
    this.meta.lastCheckAt = asOf;

    await this.store?.writeRegions(writes);
    await this.store?.writeMeta(this.meta);
  }

  /**
   * Коли стан востаннє підтвердився (FR-30): останній успішний знімок `/alerts`,
   * а вебхуки продовжують його не далі ніж на `WEBHOOK_TRUST_MS`.
   */
  confirmedAt(): number | null {
    const { lastCheckAt, lastWebhookAt } = this.meta;
    if (lastCheckAt === null) return null;
    if (lastWebhookAt === null) return lastCheckAt;
    return Math.max(lastCheckAt, Math.min(lastWebhookAt, lastCheckAt + WEBHOOK_TRUST_MS));
  }

  response(now: number): AlertsResponse {
    // Поки немає початкового знімка, стан неповний: регіон, де тривога почалася
    // до нашої підписки, виглядав би чистим. Чесно кажемо «не знаємо».
    const confirmedAt = this.confirmedAt();
    if (this.meta.syncedAt === null || confirmedAt === null) {
      return { v: 1, alerts: null, confirmed_at: null, age_seconds: null };
    }

    const alerts: RegionAlert[] = [...this.regions]
      .filter(([, state]) => state.active)
      .sort(([a], [b]) => Number(a) - Number(b))
      .map(([region, state]) => ({ region, levels: levelsOut(state) }));

    return {
      v: 1,
      alerts,
      confirmed_at: new Date(confirmedAt).toISOString(),
      age_seconds: Math.max(0, Math.round((now - confirmedAt) / 1000)),
    };
  }

  stats(now: number) {
    const { stats, syncedAt, heardAt, lastWebhookAt, lastCheckAt } = this.meta;
    const iso = (time: number | null) => (time === null ? null : new Date(time).toISOString());
    return {
      synced_at: iso(syncedAt),
      confirmed_at: iso(this.confirmedAt()),
      heard_at: iso(heardAt),
      last_check_at: iso(lastCheckAt),
      seconds_since_last_webhook:
        lastWebhookAt === null ? null : Math.round((now - lastWebhookAt) / 1000),
      active_regions: [...this.regions.values()].filter((state) => state.active).length,
      webhooks: {
        since: iso(stats.since),
        received: stats.received,
        changed: stats.changed,
        out_of_order: stats.outOfOrder,
        gaps: stats.gaps,
        max_gap_seconds: stats.maxGapSeconds,
        max_gap_ended_at: iso(stats.maxGapEndedAt),
        delay_avg_seconds:
          stats.delay.count === 0 ? null : Math.round(stats.delay.totalSeconds / stats.delay.count),
        delay_max_seconds: stats.delay.count === 0 ? null : stats.delay.maxSeconds,
        recent: stats.recent,
      },
    };
  }
}

/**
 * Поля механізму перевірок через `/alerts/status` (прибраний 2026-09-27) ще лежать
 * у збереженому стані. Відкидаємо їх, щоб наступний запис їх стер.
 */
function withoutLegacy(meta: Meta): Meta {
  const legacy = ["nextCheckAt", "lastActionIndex", "coveredUntil", "lastReloadAt", "nextReloadAt", "checks"];
  const copy: Record<string, unknown> = { ...meta };
  for (const key of legacy) delete copy[key];
  return copy as unknown as Meta;
}

/** Рівні для відповіді: найсуворіший першим. Стан без рівнів (до v1) — червоний. */
function levelsOut(state: RegionState): RegionAlert["levels"] {
  const levels = state.levels?.length ? state.levels : [{ level: "red" as const, since: state.changedAt, reason: null }];
  return [...levels]
    .sort((a, b) => (a.level === b.level ? a.since - b.since : a.level === "red" ? -1 : 1))
    .map(({ level, since, reason }) => ({ level, since: new Date(since).toISOString(), reason }));
}

/** Чи збігається стан до останнього поля — тоді переписувати його в сховищі нема чого. */
function sameState(a: RegionState, b: RegionState): boolean {
  const key = (state: RegionState) =>
    JSON.stringify([
      state.active,
      state.changedAt,
      state.levels === undefined ? null : state.levels.map((l) => [l.level, l.since, l.reason]).sort(),
    ]);
  return key(a) === key(b);
}

function sameLevels(previous: RegionState, event: AlertEvent): boolean {
  const key = (levels: RegionState["levels"]) =>
    JSON.stringify((levels ?? []).map((l) => [l.level, l.since]).sort());
  return key(previous.levels) === key(event.levels);
}
