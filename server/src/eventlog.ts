/**
 * Журнал подій: історія того, що сервер дізнавався і як змінював стан регіонів.
 *
 * Стан регіонів (`state.ts`) знає лише «що зараз». Журнал відповідає на «чому сервер
 * о 06:12 сказав телефону, що тривоги немає»: який вебхук прийшов, що показав знімок
 * `/alerts` і коли. Лише публічні дані про тривоги, нічого про користувачів.
 *
 * На обробку тривог журнал не впливає: помилка запису лише потрапляє в лог воркера.
 *
 * Зберігання — годинні «кошики»: один ключ сховища на годину (`log:2026-09-29T09`), куди
 * дописуються всі події цієї години. Кожна подія — один записаний рядок (кошик переписується
 * цілим), а старі кошики видаляються раз на годину одним запитом за діапазоном ключів.
 * Так ліміт записаних рядків (100 000/добу) витрачається лише на самі події, ~2 600/добу.
 */

export const LOG_KEEP_HOURS = 48;
const HOUR_MS = 60 * 60_000;

/** Стан регіону одним словом: `none`, `red`, `yellow`, `red+yellow`. */
export type Summary = string;

/** Рівень як є: `[рівень, час оголошення мс]`. */
export type LevelMark = [string, number];

export interface WebhookEntry {
  kind: "webhook";
  /** Коли отримали, мс за годинником воркера. */
  receivedAt: number;
  /** `createdAt` події, мс за годинником ukrainealarm. */
  createdAt: number;
  region: string;
  from: Summary;
  to: Summary;
  levels: LevelMark[];
  /** `changed` / `unchanged` / `out_of_order`. */
  outcome: string;
}

export interface SnapshotChange {
  region: string;
  from: Summary;
  to: Summary;
  /** Сирий `lastUpdate` зі списку (до злиття з часом вебхука); `null` — регіону в списку немає. */
  lastUpdate: number | null;
  levels: LevelMark[];
}

export interface SnapshotEntry {
  kind: "snapshot";
  /** Коли почали запит, мс за годинником воркера. */
  asOf: number;
  /** Скільки тривав запит до ukrainealarm, мс. */
  tookMs: number | null;
  ok: boolean;
  /** Код відмови; `null` — мережа/тайм-аут. Лише для `ok: false`. */
  status?: number | null;
  detail?: string;
  /** Скільки регіонів з повітряною тривогою було в списку. */
  active?: number;
  /** Регіони, які знімок змінив. */
  changes?: SnapshotChange[];
  /** Регіони, де знімок розійшовся б із вебхуком, новішим за запит, — стан лишено від вебхука. */
  kept?: string[];
}

export type LogEntry = WebhookEntry | SnapshotEntry;

export interface LogStore {
  readHour(hour: string): Promise<LogEntry[] | undefined>;
  writeHour(hour: string, entries: LogEntry[]): Promise<void>;
  /** Усі кошики, починаючи з години `fromHour` включно, за зростанням. */
  readFrom(fromHour: string): Promise<[string, LogEntry[]][]>;
  /** Видаляє кошики, старші за `hour` (саму `hour` лишає). */
  deleteBefore(hour: string): Promise<void>;
}

/** `2026-09-29T09` — година за UTC; рядки впорядковуються так само, як час. */
export function hourKey(time: number): string {
  return new Date(time).toISOString().slice(0, 13);
}

export class EventLog {
  private hour: string | null = null;
  private entries: LogEntry[] = [];

  constructor(private readonly store?: LogStore) {}

  /**
   * Дописує подію в кошик її години. `at` — час за годинником воркера.
   * Першу подію години (і першу після «засинання» об'єкта) спершу дочитує зі сховища,
   * щоб не затерти те, що вже записано в цю годину.
   */
  async append(entry: LogEntry, at: number): Promise<void> {
    const hour = hourKey(at);
    if (hour !== this.hour) {
      this.entries = (await this.store?.readHour(hour)) ?? [];
      this.hour = hour;
      await this.store?.deleteBefore(hourKey(at - LOG_KEEP_HOURS * HOUR_MS));
    }
    this.entries.push(entry);
    await this.store?.writeHour(hour, this.entries);
  }

  /**
   * Події за останні `hours` год. З `region` — лише те, що стосується цього регіону;
   * знімки лишаються (без чужих змін), щоб було видно, коли вони відбувались.
   */
  async read(now: number, hours: number, region?: string): Promise<LogEntry[]> {
    const from = now - hours * HOUR_MS;
    const buckets = this.store
      ? await this.store.readFrom(hourKey(from))
      : this.hour === null ? [] : [[this.hour, this.entries] as [string, LogEntry[]]];
    const all = buckets.flatMap(([, entries]) => entries).filter((entry) => timeOf(entry) >= from);
    if (region === undefined) return all;
    return all.flatMap((entry): LogEntry[] => {
      if (entry.kind === "webhook") return entry.region === region ? [entry] : [];
      return [
        {
          ...entry,
          changes: entry.changes?.filter((change) => change.region === region),
          kept: entry.kept?.filter((id) => id === region),
        },
      ];
    });
  }
}

function timeOf(entry: LogEntry): number {
  return entry.kind === "webhook" ? entry.receivedAt : entry.asOf;
}
