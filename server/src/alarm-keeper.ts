/**
 * Стежить, щоб ланцюжок будильників Durable Object не обірвався.
 *
 * Будильник об'єкта одноразовий: кожне пробудження (`hub.ts`, `alarm()`) саме заводить
 * наступне. Якщо завести не вдалося (вичерпано ліміт записів, збій сховища) і повтори
 * Cloudflare теж не допомогли, ланцюжок обривається — і щохвилинні знімки `/alerts` зникають.
 *
 * Тому запити телефонів і вебхуки час від часу перевіряють, чи будильник справді заведено.
 * Не щоразу (це зайве читання сховища на кожен запит), а не частіше ніж раз на `CHECK_EVERY_MS`.
 * Раніше тут був прапорець «вже заведено», який не перевірявся до кінця життя об'єкта:
 * під час тривоги, коли запити йдуть безперервно і об'єкт не вивантажується, обірваний
 * ланцюжок міг лишатися непоміченим годинами.
 */

/** Як часто перевіряти, що будильник заведено. */
export const CHECK_EVERY_MS = 60_000;

export interface AlarmStorage {
  getAlarm(): Promise<number | null>;
  setAlarm(at: number): Promise<void>;
}

export class AlarmKeeper {
  /** Коли востаннє переконались, що будильник є; `null` — ще ні разу. */
  private checkedAt: number | null = null;

  constructor(
    private readonly storage: AlarmStorage,
    private readonly intervalMs: number,
  ) {}

  /** Будильник щойно заведено в `alarm()` — перевіряти найближчу хвилину нема чого. */
  armed(now: number): void {
    this.checkedAt = now;
  }

  /**
   * Заводить будильник, якщо його немає. Помилка лише потрапляє в лог: телефон має отримати
   * відповідь зі стану в пам'яті, а наступний запит спробує знову.
   */
  async ensure(now: number): Promise<void> {
    if (this.checkedAt !== null && now - this.checkedAt < CHECK_EVERY_MS) return;
    try {
      if ((await this.storage.getAlarm()) === null) {
        if (this.checkedAt !== null) console.error("ланцюжок будильників обірвався — заводимо знову");
        await this.storage.setAlarm(now + this.intervalMs);
      }
      this.checkedAt = now;
    } catch (error) {
      console.error(`будильник не заведено: ${String(error)}`);
    }
  }
}
