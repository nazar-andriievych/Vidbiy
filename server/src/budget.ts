/**
 * Жорсткий запобіжник на запити до ukrainealarm.
 *
 * Кожен прямий запит проходить через `CallBudget.take()`. Він пропускає не більше
 * `PER_MINUTE` запитів за будь-які 60 с і `PER_DAY` за добу, хоч би що сталося в решті
 * логіки: помилка в розкладі, зациклений повтор, шторм запитів від телефонів. Ліміт
 * ukrainealarm нам невідомий, а за систематичне перевищення можуть відібрати ключ.
 *
 * Лічильник рахує й запити, які ми лише *зробили б*: без ключа (`UKRAINEALARM_TOKEN`
 * не заданий) нічого не йде назовні, але `/stats` показує, скільки й чого ми просили.
 * Так розклад можна перевірити на розгорнутому воркері, не ризикуючи ключем.
 */

/** Нормальна робота — 1/хв (знімок `/alerts`); решта — запас на повтор. */
export const PER_MINUTE = 3;
/** 1/хв цілодобово — це 1440; удвічі більше за добу означає, що щось пішло не так. */
export const PER_DAY = 3000;

const MINUTE_MS = 60_000;
const RECENT_LIMIT = 20;

export type CallPath = "alerts";

export type CallOutcome =
  | "ok"
  /** Ключа немає: запит порахований, але не відправлений. */
  | "no_token"
  | "failed"
  /** Не пустив запобіжник. */
  | "over_budget";

export interface BudgetState {
  /** Час запитів за останню хвилину, мс. */
  lastMinute: number[];
  /** Доба за UTC, `YYYY-MM-DD`, і скільки запитів у ній. */
  day: string;
  dayCount: number;
  total: number;
  refused: number;
  recent: { at: string; path: CallPath; outcome: CallOutcome; status?: number | null }[];
}

export function emptyBudget(): BudgetState {
  return { lastMinute: [], day: "", dayCount: 0, total: 0, refused: 0, recent: [] };
}

export class CallBudget {
  constructor(private state: BudgetState = emptyBudget()) {}

  snapshot(): BudgetState {
    return this.state;
  }

  /** Чи можна зробити запит зараз. Якщо так — одразу записує його в лічильники. */
  take(now: number): boolean {
    const day = new Date(now).toISOString().slice(0, 10);
    if (day !== this.state.day) {
      this.state.day = day;
      this.state.dayCount = 0;
    }
    this.state.lastMinute = this.state.lastMinute.filter((at) => now - at < MINUTE_MS);

    if (this.state.lastMinute.length >= PER_MINUTE || this.state.dayCount >= PER_DAY) {
      this.state.refused++;
      return false;
    }
    this.state.lastMinute.push(now);
    this.state.dayCount++;
    this.state.total++;
    return true;
  }

  record(now: number, path: CallPath, outcome: CallOutcome, status?: number | null): void {
    this.state.recent = [
      { at: new Date(now).toISOString(), path, outcome, ...(status === undefined ? {} : { status }) },
      ...this.state.recent,
    ].slice(0, RECENT_LIMIT);
  }

  stats(now: number) {
    return {
      limits: { per_minute: PER_MINUTE, per_day: PER_DAY },
      last_minute: this.state.lastMinute.filter((at) => now - at < MINUTE_MS).length,
      today: this.state.day === new Date(now).toISOString().slice(0, 10) ? this.state.dayCount : 0,
      total: this.state.total,
      refused_by_budget: this.state.refused,
      recent: this.state.recent,
    };
  }
}
