/**
 * Перевірка на практиці: чи повторює ukrainealarm доставку вебхука, якщо ми не відповіли 200.
 *
 * Вмикається змінною `WEBHOOK_RETRY_PROBE=N`: кожну N-ту подію ми застосовуємо як завжди,
 * але відповідаємо 503. Відбиток тіла (SHA-256) запам'ятовуємо; якщо те саме тіло прийде
 * знову — доставку повторили, і ми знаємо, через скільки.
 *
 * Стан від цього не страждає: подія вже застосована, а повтор — дублікат, який
 * `AlertBoard` просто ігнорує. Ризик один: після кількох відмов відправник може вирішити,
 * що наша адреса мертва, й відписати нас. Тому N варто брати великим, а перевірку
 * тримати ввімкненою недовго.
 */

/** Скільки «пасток» пам'ятаємо; повтор, що прийде пізніше, вже не впізнаємо. */
const PENDING_LIMIT = 20;
/** Скільки затримок повтору показуємо в статистиці. */
const DELAYS_LIMIT = 20;

export interface ProbeState {
  /** Скільки подій пройшло відтоді, як увімкнули перевірку. */
  seen: number;
  /** Скільки разів навмисно відповіли 503. */
  refused: number;
  /** Скільки з них прийшли повторно. */
  redelivered: number;
  /** Скільки секунд минуло від відмови до повтору. */
  delaysSeconds: number[];
  pending: { hash: string; refusedAt: number }[];
}

export function emptyProbe(): ProbeState {
  return { seen: 0, refused: 0, redelivered: 0, delaysSeconds: [], pending: [] };
}

export class RetryProbe {
  constructor(private state: ProbeState = emptyProbe()) {}

  snapshot(): ProbeState {
    return this.state;
  }

  /**
   * Вирішує, чи відповісти на цю подію відмовою, й відзначає повтор, якщо це він.
   * `every` ≤ 0 — перевірка вимкнена, але повтори раніше відхилених подій ще рахуємо.
   */
  onDelivery(hash: string, now: number, every: number): { refuse: boolean } {
    const index = this.state.pending.findIndex((entry) => entry.hash === hash);
    if (index >= 0) {
      const [entry] = this.state.pending.splice(index, 1);
      this.state.redelivered++;
      this.state.delaysSeconds = [
        Math.round((now - entry!.refusedAt) / 1000),
        ...this.state.delaysSeconds,
      ].slice(0, DELAYS_LIMIT);
      // Повтор приймаємо: вдруге ту саму подію не відхиляємо.
      return { refuse: false };
    }

    if (every <= 0) return { refuse: false };
    this.state.seen++;
    if (this.state.seen % every !== 0) return { refuse: false };

    this.state.refused++;
    this.state.pending = [{ hash, refusedAt: now }, ...this.state.pending].slice(0, PENDING_LIMIT);
    return { refuse: true };
  }

  stats(every: number) {
    return {
      every: every > 0 ? every : null,
      refused: this.state.refused,
      redelivered: this.state.redelivered,
      redelivery_delays_seconds: this.state.delaysSeconds,
      waiting: this.state.pending.length,
    };
  }
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}
