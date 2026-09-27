/**
 * Розклад повних завантажень `/api/v3/alerts` — без `/alerts/status`.
 *
 * `status` нам нічого не дає: номер зміни рухається щосекунди, тож «номер той самий»
 * не трапляється ніколи (2 доби на проді: 0 разів). Тому просто беремо повний список
 * раз на хвилину. Перевірено 2026-09-26/27: понад 760 запитів поспіль без жодної відмови.
 *
 * Запобіжник: якщо ключ почав відмовляти, на годину переходимо на рідкий розклад,
 * а потім пробуємо щохвилинний знову. За робочою версією ніхто не стежить, тож
 * вимикати щохвилинний режим «до наступного деплою» не можна: дані застаріватимуть
 * (FR-30), і будильники дзвонитимуть за fail-safe посеред тривоги.
 */

export const FAST_INTERVAL_MS = 60_000;
/** Рідкий розклад: 5 хв пройшли без жодної відмови в дослідах. */
export const SLOW_INTERVAL_MS = 5 * 60_000;
/** Скільки тримаємо рідкий розклад після спрацювання запобіжника. */
export const TRIP_COOLDOWN_MS = 60 * 60_000;

/** Стільки відмов поспіль — і щохвилинний режим вимикається. */
export const TRIP_CONSECUTIVE = 3;
/**
 * Або стільки відмов серед останніх `TRIP_WINDOW` спроб (понад 20 % за останні пів години).
 * Саме ковзне вікно, а не частка за весь час: після доби успіхів загальна частка
 * помітила б нову хвилю відмов лише через багато годин.
 */
export const TRIP_WINDOW = 30;
export const TRIP_WINDOW_FAILURES = 7;

export interface ReloadState {
  nextAt: number;
  attempts: number;
  failed: number;
  consecutiveFailed: number;
  maxConsecutiveFailed: number;
  /** Запобіжник спрацював; щохвилинний режим повернеться через `TRIP_COOLDOWN_MS`. */
  tripped: { at: number; reason: string } | null;
  /** Скільки разів спрацьовував запобіжник за весь час. */
  trips: number;
  lastOkAt: number | null;
  /** Найдовший проміжок між двома успішними завантаженнями, с. */
  maxOkGapSeconds: number;
  /** Коротка історія: `+` — 200, `-` — відмова. Найновіші праворуч. */
  trail: string;
}

export function emptyReload(): ReloadState {
  return {
    nextAt: 0,
    attempts: 0,
    failed: 0,
    consecutiveFailed: 0,
    maxConsecutiveFailed: 0,
    tripped: null,
    trips: 0,
    lastOkAt: null,
    maxOkGapSeconds: 0,
    trail: "",
  };
}

export class ReloadSchedule {
  constructor(private state: ReloadState) {}

  /** Відновлює збережений стан; лічильники переживають деплой. Поля старих версій відкидаємо. */
  static restore(saved: Partial<ReloadState> | undefined): ReloadSchedule {
    const state = emptyReload();
    if (saved) {
      for (const key of Object.keys(state) as (keyof ReloadState)[]) {
        if (saved[key] !== undefined) (state as unknown as Record<string, unknown>)[key] = saved[key];
      }
    }
    return new ReloadSchedule(state);
  }

  snapshot(): ReloadState {
    return this.state;
  }

  fast(now: number): boolean {
    const { tripped } = this.state;
    return tripped === null || now >= tripped.at + TRIP_COOLDOWN_MS;
  }

  due(now: number): boolean {
    return now >= this.state.nextAt;
  }

  /** Результат однієї спроби. Відмова не прискорює наступну: інтервал той самий. */
  record(ok: boolean, startedAt: number): void {
    const s = this.state;
    if (s.tripped !== null && this.fast(startedAt)) {
      // Година минула: пробуємо щохвилинний режим знову, старі відмови не рахуємо.
      s.tripped = null;
      s.trail = "";
    }
    const fast = this.fast(startedAt);
    s.attempts++;
    s.trail = (s.trail + (ok ? "+" : "-")).slice(-120);

    if (ok) {
      if (s.lastOkAt !== null) {
        s.maxOkGapSeconds = Math.max(s.maxOkGapSeconds, Math.round((startedAt - s.lastOkAt) / 1000));
      }
      s.lastOkAt = startedAt;
      s.consecutiveFailed = 0;
    } else {
      s.failed++;
      s.consecutiveFailed++;
      s.maxConsecutiveFailed = Math.max(s.maxConsecutiveFailed, s.consecutiveFailed);

      const recentFailed = [...s.trail.slice(-TRIP_WINDOW)].filter((mark) => mark === "-").length;
      if (fast && s.consecutiveFailed >= TRIP_CONSECUTIVE) {
        s.tripped = { at: startedAt, reason: `${s.consecutiveFailed} відмови поспіль` };
      } else if (fast && recentFailed >= TRIP_WINDOW_FAILURES) {
        s.tripped = { at: startedAt, reason: `${recentFailed} відмов серед останніх ${TRIP_WINDOW} спроб` };
      }
      if (s.tripped?.at === startedAt) s.trips++;
    }

    s.nextAt = startedAt + (this.fast(startedAt) ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS);
  }

  stats(now: number) {
    const s = this.state;
    return {
      mode: this.fast(now) ? "every_minute" : "every_5_minutes",
      tripped: s.tripped && {
        at: new Date(s.tripped.at).toISOString(),
        reason: s.tripped.reason,
        until: new Date(s.tripped.at + TRIP_COOLDOWN_MS).toISOString(),
      },
      trips: s.trips,
      attempts: s.attempts,
      failed: s.failed,
      consecutive_failed: s.consecutiveFailed,
      max_consecutive_failed: s.maxConsecutiveFailed,
      max_ok_gap_seconds: s.maxOkGapSeconds,
      next_at: s.nextAt ? new Date(s.nextAt).toISOString() : null,
      trail: s.trail,
    };
  }
}
