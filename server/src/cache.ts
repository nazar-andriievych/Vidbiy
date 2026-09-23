import type { ActiveAlert, AlertsResponse } from "./types";
import type { UpstreamResult } from "./alerts-in-ua";

/** Свіжіші за це дані віддаємо з пам'яті. 15 с = максимум 4 запити/хв до alerts.in.ua. */
export const TTL_MS = 15_000;

/** Після 429 чекаємо довше: за систематичне перевищення лімітів блокують IP і токен. */
export const RATE_LIMITED_BACKOFF_MS = 60_000;

export interface CacheDeps {
  fetchAlerts: () => Promise<UpstreamResult>;
  /** Годинник — підмінний, щоб тести не спали по-справжньому. */
  now?: () => number;
}

/**
 * Кеш активних тривог із жорстким обмежувачем частоти звернень до апстріму.
 *
 * Живе в пам'яті воркера. Cache API тут не підходить: на безкоштовному `*.workers.dev`
 * він не працює, потрібен власний домен.
 *
 * Про годинник: у Workers `Date.now()` оновлюється лише після операцій вводу-виводу,
 * тож між ними час «стоїть». Для нас це неважливо — кожен запит і кожен fetch
 * самі по собі є вводом-виводом, тому час зрушує саме там, де ми його читаємо.
 */
export class AlertsCache {
  private readonly fetchAlerts: () => Promise<UpstreamResult>;
  private readonly now: () => number;

  /** Останні дані, які вдалося отримати, — навіть якщо відтоді апстрім впав. */
  private lastGood: { alerts: ActiveAlert[]; fetchedAt: number } | null = null;
  private lastAttempt: { ok: boolean; status: number | null } | null = null;
  private nextFetchAt = 0;
  private inFlight: Promise<void> | null = null;

  constructor(deps: CacheDeps) {
    this.fetchAlerts = deps.fetchAlerts;
    this.now = deps.now ?? Date.now;
  }

  async get(): Promise<AlertsResponse> {
    if (this.now() >= this.nextFetchAt) {
      await this.refresh();
    }
    return this.snapshot();
  }

  /**
   * Позначає дані застарілими, щоб наступний `get()` одразу пішов до апстріму.
   *
   * Останні відомі дані свідомо лишаються на місці: якщо новий запит провалиться,
   * застосунок має отримати їх із чесним віком, а не порожнечу.
   * Потрібно лише mock-режиму при зміні сценарію.
   */
  expire(): void {
    this.nextFetchAt = 0;
  }

  /** Паралельні запити від різних телефонів чекають на один спільний запит до апстріму. */
  private refresh(): Promise<void> {
    this.inFlight ??= this.fetchOnce().finally(() => {
      this.inFlight = null;
    });
    return this.inFlight;
  }

  private async fetchOnce(): Promise<void> {
    let result: UpstreamResult;
    try {
      result = await this.fetchAlerts();
    } catch {
      result = { ok: false, status: null, reason: "network" };
    }

    const now = this.now();
    if (result.ok) {
      this.lastGood = { alerts: result.alerts, fetchedAt: now };
      this.lastAttempt = { ok: true, status: null };
      this.nextFetchAt = now + TTL_MS;
    } else {
      // Старі дані не викидаємо: хай застосунок сам вирішить за їхнім віком.
      this.lastAttempt = { ok: false, status: result.status };
      this.nextFetchAt = now + (result.status === 429 ? RATE_LIMITED_BACKOFF_MS : TTL_MS);
    }
  }

  private snapshot(): AlertsResponse {
    const upstreamOk = this.lastAttempt?.ok ?? false;
    const head = {
      v: 1 as const,
      upstream_ok: upstreamOk,
      ...(upstreamOk ? {} : { upstream_status: this.lastAttempt?.status ?? null }),
    };

    if (!this.lastGood) {
      return { ...head, fetched_at: null, age_seconds: null, alerts: null };
    }

    return {
      ...head,
      fetched_at: new Date(this.lastGood.fetchedAt).toISOString(),
      age_seconds: Math.max(0, Math.round((this.now() - this.lastGood.fetchedAt) / 1000)),
      alerts: this.lastGood.alerts,
    };
  }
}
