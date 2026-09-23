/** Тип регіону з довідника alerts.in.ua. */
export type LocationType = "oblast" | "raion" | "city" | "hromada" | "unknown";

/** Одна активна повітряна тривога, зведена до мінімуму, потрібного застосунку. */
export interface ActiveAlert {
  uid: string;
  type: LocationType;
  started_at: string | null;
}

/**
 * Відповідь `/v1/alerts`. Контракт описаний у `docs/proxy-api.md`.
 *
 * `alerts: []` — перевірено, тривог немає.
 * `alerts: null` — даних немає взагалі; застосунок дзвонить за fail-safe.
 */
export interface AlertsResponse {
  v: 1;
  upstream_ok: boolean;
  upstream_status?: number | null;
  fetched_at: string | null;
  age_seconds: number | null;
  alerts: ActiveAlert[] | null;
}
