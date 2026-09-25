import { AIR } from "./ukrainealarm";
import type { AlertEvent, AlertsResponse, RegionState } from "./types";

/** Скільки останніх подій тримаємо для `/v2/stats`. */
const RECENT_LIMIT = 30;

/**
 * Скільки тиші терпимо, перш ніж самі спитати ukrainealarm, чи нічого не змінилося.
 *
 * Тиша на вебхуках — це або «нічого не сталося», або «канал зламався», і розрізнити
 * їх можна лише запитом. Хвилина лишає запас до порогу застосунку в 3 хв (NFR-1):
 * навіть якщо одна перевірка провалиться, наступна встигне.
 */
export const SILENCE_MS = 60_000;

/**
 * Навіть коли вебхуки йдуть безперервно, раз на 10 хв звіряємося з API: загублений
 * вебхук інакше лишив би регіон у хибному стані доти, доки там щось не зміниться.
 */
export const AUDIT_MS = 10 * 60_000;

/**
 * Повний список (`/api/v3/alerts`) — дорогий запит: на проді приблизно кожен третій
 * отримував 401, коли ми брали його щохвилини (заміряно 2026-09-24). Тому не частіше
 * ніж раз на 2 хв, а після відмови — пауза 3 хв. `status` під це обмеження не підпадає.
 */
export const RELOAD_MIN_INTERVAL_MS = 2 * 60_000;
export const RELOAD_BACKOFF_MS = 3 * 60_000;

/**
 * Наскільки час із номера зміни може бути пізнішим за найсвіжішу побачену подію,
 * щоб ми все одно вважали, що бачили все. `createdAt` у вебхуках, судячи з прикладів,
 * округлений до секунд, а номер має точність до мікросекунд.
 */
export const INDEX_TOLERANCE_MS = 2_000;

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
  /** Не раніше цього моменту знову звертаємося до API, якщо попередня спроба не вдалася. */
  nextCheckAt: number;
  /**
   * Коли стан востаннє підтвердився: перевірений вебхук, перевірка на тиші або знімок.
   * Від нього рахується `age_seconds`, тобто свіжість даних для застосунку.
   */
  heardAt: number | null;
  /** Номер останньої зміни в ukrainealarm, яку ми бачили під час перевірки. */
  lastActionIndex: string | null;
  /** Коли ми востаннє самі звірялися з API (успішно). */
  lastCheckAt: number | null;
  /**
   * До якого моменту (за годинником ukrainealarm) ми бачили всі зміни: найсвіжіший
   * `createdAt` із вебхуків або час із номера зміни на момент повного завантаження.
   */
  coveredUntil: number | null;
  /** Останнє успішне повне завантаження списку. */
  lastReloadAt: number | null;
  /** Раніше цього моменту повний список не беремо. */
  nextReloadAt: number;
  checks: CheckStats;
  /** Коли прийшов останній вебхук — для вимірювання пауз. */
  lastWebhookAt: number | null;
  stats: WebhookStats;
}

/** Як закінчувалися перевірки через API — щоб бачити, чи працює порівняння за часом. */
export interface CheckStats {
  /** Номер той самий, що минулого разу. */
  sameIndex: number;
  /** Номер новий, але його час не пізніший за побачені вебхуки. */
  coveredByWebhooks: number;
  reloads: number;
  /** Треба було перезавантажити список, але обмежувач `/alerts` не дозволив. */
  deferred: number;
  failed: number;
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
    nextCheckAt: 0,
    heardAt: null,
    lastActionIndex: null,
    lastCheckAt: null,
    coveredUntil: null,
    lastReloadAt: null,
    nextReloadAt: 0,
    checks: { sameIndex: 0, coveredByWebhooks: 0, reloads: 0, deferred: 0, failed: 0 },
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
      if (meta) this.meta = { ...emptyMeta(), ...meta };
    });
    return this.restored;
  }

  get syncedAt(): number | null {
    return this.meta.syncedAt;
  }

  get lastActionIndex(): string | null {
    return this.meta.lastActionIndex;
  }

  /**
   * Чи пора самим спитати ukrainealarm: стан ще не завантажений, хвилину не було
   * жодного підтвердження, або давно не було звірки.
   */
  needsCheck(now: number): boolean {
    const { syncedAt, heardAt, nextCheckAt } = this.meta;
    if (now < nextCheckAt) return false;
    if (syncedAt === null) return true;
    if (heardAt === null || now - heardAt >= SILENCE_MS) return true;
    return this.auditDue(now);
  }

  /** Давно не брали повний список — пора звіритися, навіть якщо вебхуки йдуть. */
  auditDue(now: number): boolean {
    return this.meta.lastReloadAt === null || now - this.meta.lastReloadAt >= AUDIT_MS;
  }

  reloadAllowed(now: number): boolean {
    return now >= this.meta.nextReloadAt;
  }

  /**
   * Чи бачили ми всі зміни аж до тієї, на яку вказує номер. Якщо так, наш стан
   * правильний і повний список не потрібен.
   */
  covers(index: string, indexTime: number | null): "same_index" | "covered" | null {
    if (this.meta.syncedAt === null) return null;
    if (index === this.meta.lastActionIndex) return "same_index";
    const covered = this.meta.coveredUntil;
    if (indexTime !== null && covered !== null && indexTime <= covered + INDEX_TOLERANCE_MS) {
      return "covered";
    }
    return null;
  }

  /** Перевірка підтвердила, що стан правильний. Це і є підтвердження свіжості. */
  async confirm(now: number, index: string, how: "same_index" | "covered"): Promise<void> {
    this.meta.heardAt = Math.max(this.meta.heardAt ?? 0, now);
    this.meta.lastCheckAt = now;
    this.meta.lastActionIndex = index;
    if (how === "same_index") this.meta.checks.sameIndex++;
    else this.meta.checks.coveredByWebhooks++;
    await this.store?.writeMeta(this.meta);
  }

  /** Список треба було перезавантажити, але обмежувач `/alerts` не дозволив. */
  async deferReload(retryAt: number): Promise<void> {
    this.meta.checks.deferred++;
    this.meta.nextCheckAt = retryAt;
    await this.store?.writeMeta(this.meta);
  }

  /**
   * Невдалий запит: наступна перевірка не раніше `retryAt`, бо невдача — не привід частішати.
   * Якщо відмовив саме повний список, він отримує власну, довшу паузу.
   */
  async checkFailed(retryAt: number, reloadRetryAt?: number): Promise<void> {
    this.meta.checks.failed++;
    this.meta.nextCheckAt = retryAt;
    if (reloadRetryAt !== undefined) this.meta.nextReloadAt = reloadRetryAt;
    await this.store?.writeMeta(this.meta);
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

    this.regions.set(event.regionId, { active: event.active, changedAt: event.createdAt });
    return previous?.active === event.active ? "unchanged" : "changed";
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

    // Номер зміни в ukrainealarm рахує всі події — і не повітряні, і тестовий регіон, —
    // тож кожна перевірена подія розширює проміжок, який ми бачили.
    this.meta.coveredUntil = Math.max(this.meta.coveredUntil ?? 0, event.createdAt);
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
  async loadSnapshot(
    snapshot: Map<string, RegionState>,
    asOf: number,
    lastActionIndex: string | null = null,
    indexTime: number | null = null,
  ): Promise<void> {
    const changes = new Map<string, RegionState>();

    for (const [id, current] of this.regions) {
      if (current.changedAt > asOf || snapshot.has(id) || !current.active) continue;
      changes.set(id, { active: false, changedAt: current.changedAt });
    }
    for (const [id, fresh] of snapshot) {
      const current = this.regions.get(id);
      if (current && current.changedAt > asOf) continue;
      changes.set(id, { active: true, changedAt: Math.max(fresh.changedAt, current?.changedAt ?? 0) });
    }

    for (const [id, state] of changes) this.regions.set(id, state);
    this.meta.syncedAt ??= asOf;
    this.meta.heardAt = Math.max(this.meta.heardAt ?? 0, asOf);
    this.meta.lastCheckAt = asOf;
    this.meta.lastActionIndex = lastActionIndex;
    this.meta.lastReloadAt = asOf;
    this.meta.nextReloadAt = asOf + RELOAD_MIN_INTERVAL_MS;
    this.meta.checks.reloads++;
    if (indexTime !== null) {
      this.meta.coveredUntil = Math.max(this.meta.coveredUntil ?? 0, indexTime);
    }

    await this.store?.writeRegions(changes);
    await this.store?.writeMeta(this.meta);
  }

  response(now: number): AlertsResponse {
    // Поки немає початкового знімка, стан неповний: регіон, де тривога почалася
    // до нашої підписки, виглядав би чистим. Чесно кажемо «не знаємо».
    if (this.meta.syncedAt === null || this.meta.heardAt === null) {
      return { v: 2, active: null, heard_at: null, age_seconds: null };
    }

    const active = [...this.regions]
      .filter(([, state]) => state.active)
      .map(([id]) => id)
      .sort((a, b) => Number(a) - Number(b));

    return {
      v: 2,
      active,
      heard_at: new Date(this.meta.heardAt).toISOString(),
      age_seconds: Math.max(0, Math.round((now - this.meta.heardAt) / 1000)),
    };
  }

  stats(now: number) {
    const { stats, syncedAt, heardAt, lastWebhookAt, lastCheckAt, lastActionIndex } = this.meta;
    const { coveredUntil, lastReloadAt, nextReloadAt, checks } = this.meta;
    const iso = (time: number | null) => (time === null ? null : new Date(time).toISOString());
    return {
      synced_at: iso(syncedAt),
      heard_at: iso(heardAt),
      last_check_at: iso(lastCheckAt),
      last_action_index: lastActionIndex,
      covered_until: iso(coveredUntil),
      last_reload_at: iso(lastReloadAt),
      next_reload_allowed_at: iso(nextReloadAt || null),
      checks: {
        same_index: checks.sameIndex,
        covered_by_webhooks: checks.coveredByWebhooks,
        reloads: checks.reloads,
        deferred: checks.deferred,
        failed: checks.failed,
      },
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
