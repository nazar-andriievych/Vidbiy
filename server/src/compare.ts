import { AIR } from "./ukrainealarm";
import type { AlertEvent, RegionState } from "./types";

/**
 * Дослід 1: чи збігається стан, зібраний лише з вебхуків, зі списком `/alerts`.
 *
 * Робочий стан (`AlertBoard`) щоразу виправляється знімком, тож загублений вебхук у ньому
 * сліду не лишає. Тому поруч живе «тінь» — той самий стан, але його змінюють тільки
 * вебхуки. Після кожного знімка тінь порівнюємо з ним.
 *
 * Розбіжність сама по собі ще не помилка: вебхук приходить із затримкою (до 85 с на проді),
 * і знімок може його випередити. Тому розбіжність стає «підтвердженою», лише якщо
 * тримається довше за `CONFIRM_MS`. Тоді ми її записуємо й виправляємо тінь, щоб один
 * загублений вебхук не рахувався щохвилини.
 */

export const CONFIRM_MS = 3 * 60_000;
const RECORDS_LIMIT = 30;

export interface OpenDiff {
  /** Що каже тінь (вебхуки) і що каже `/alerts`. */
  webhooks: boolean;
  alerts: boolean;
  firstSeenAt: number;
}

export interface DiffRecord {
  regionId: string;
  webhooks: boolean;
  alerts: boolean;
  firstSeenAt: string;
  confirmedAt: string;
}

export interface CompareState {
  /** `null` — ще не було знімка, від якого почати. */
  shadow: Record<string, RegionState> | null;
  startedAt: number | null;
  comparisons: number;
  /** Розбіжності, що зникли самі до `CONFIRM_MS` — звичайна затримка вебхука. */
  transient: number;
  confirmed: number;
  open: Record<string, OpenDiff>;
  records: DiffRecord[];
}

export function emptyCompare(): CompareState {
  return { shadow: null, startedAt: null, comparisons: 0, transient: 0, confirmed: 0, open: {}, records: [] };
}

export class ShadowCompare {
  constructor(private state: CompareState = emptyCompare()) {}

  snapshot(): CompareState {
    return this.state;
  }

  /** Вебхук змінює тінь за тими ж правилами, що й робочий стан. */
  apply(event: AlertEvent): boolean {
    const shadow = this.state.shadow;
    if (shadow === null || event.regionId === null || event.alarmType !== AIR) return false;
    const previous = shadow[event.regionId];
    if (previous && event.createdAt < previous.changedAt) return false;
    shadow[event.regionId] = { active: event.active, changedAt: event.createdAt };
    return true;
  }

  /** Порівнює тінь зі знімком, отриманим запитом у момент `asOf`. */
  compare(snapshot: Map<string, RegionState>, asOf: number): void {
    const s = this.state;
    if (s.shadow === null) {
      // Перший знімок — точка відліку: тінь стартує з нього.
      s.shadow = Object.fromEntries(snapshot);
      s.startedAt = asOf;
      return;
    }
    s.comparisons++;

    const ids = new Set([...Object.keys(s.shadow), ...snapshot.keys()]);
    const stillOpen: Record<string, OpenDiff> = {};
    const confirmedNow = new Set<string>();
    for (const id of ids) {
      const mine = s.shadow[id];
      // Вебхук, новіший за знімок, знімок ще не міг побачити — це не розбіжність.
      if (mine && mine.changedAt > asOf) {
        if (s.open[id]) stillOpen[id] = s.open[id];
        continue;
      }
      const webhooks = mine?.active ?? false;
      const alerts = snapshot.get(id)?.active ?? false;
      if (webhooks === alerts) continue;

      const open = s.open[id] ?? { webhooks, alerts, firstSeenAt: asOf };
      if (asOf - open.firstSeenAt < CONFIRM_MS) {
        stillOpen[id] = { ...open, webhooks, alerts };
        continue;
      }
      s.confirmed++;
      confirmedNow.add(id);
      s.records = [
        {
          regionId: id,
          webhooks,
          alerts,
          firstSeenAt: new Date(open.firstSeenAt).toISOString(),
          confirmedAt: new Date(asOf).toISOString(),
        },
        ...s.records,
      ].slice(0, RECORDS_LIMIT);
      // Виправляємо тінь: пізніший вебхук для цього регіону все одно переможе.
      s.shadow[id] = { active: alerts, changedAt: asOf };
    }

    for (const id of Object.keys(s.open)) {
      if (!(id in stillOpen) && !confirmedNow.has(id)) s.transient++;
    }
    s.open = stillOpen;
  }

  stats(now: number) {
    const s = this.state;
    return {
      started_at: s.startedAt === null ? null : new Date(s.startedAt).toISOString(),
      comparisons: s.comparisons,
      transient: s.transient,
      confirmed: s.confirmed,
      open: Object.entries(s.open).map(([regionId, d]) => ({
        regionId,
        webhooks: d.webhooks,
        alerts: d.alerts,
        seconds: Math.round((now - d.firstSeenAt) / 1000),
      })),
      records: s.records,
    };
  }
}
