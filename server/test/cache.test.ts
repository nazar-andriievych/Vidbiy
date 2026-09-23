import { beforeEach, describe, expect, it } from "vitest";
import { AlertsCache, RATE_LIMITED_BACKOFF_MS, TTL_MS } from "../src/cache";
import type { UpstreamResult } from "../src/alerts-in-ua";

/** Керований годинник: тести не мають спати по-справжньому. */
class Clock {
  constructor(public ms = Date.parse("2026-09-23T10:00:00.000Z")) {}
  now = () => this.ms;
  advance(ms: number) {
    this.ms += ms;
  }
}

class Upstream {
  calls = 0;
  result: UpstreamResult = { ok: true, alerts: [] };
  fetch = async (): Promise<UpstreamResult> => {
    this.calls++;
    return this.result;
  };
}

const ALERT = { uid: "16", type: "oblast" as const, started_at: null };

let clock: Clock;
let upstream: Upstream;
let cache: AlertsCache;

beforeEach(() => {
  clock = new Clock();
  upstream = new Upstream();
  cache = new AlertsCache({ fetchAlerts: upstream.fetch, now: clock.now });
});

describe("свіжість і ліміт звернень", () => {
  it("перший запит іде до апстріму", async () => {
    const response = await cache.get();

    expect(upstream.calls).toBe(1);
    expect(response.upstream_ok).toBe(true);
    expect(response.age_seconds).toBe(0);
    expect(response.fetched_at).toBe("2026-09-23T10:00:00.000Z");
  });

  it("у межах TTL апстрім не турбуємо, але вік росте", async () => {
    await cache.get();
    clock.advance(TTL_MS - 1000);
    const response = await cache.get();

    expect(upstream.calls).toBe(1);
    expect(response.age_seconds).toBe(14);
  });

  it("після TTL дані оновлюються", async () => {
    await cache.get();
    clock.advance(TTL_MS);
    const response = await cache.get();

    expect(upstream.calls).toBe(2);
    expect(response.age_seconds).toBe(0);
  });

  it("паралельні запити дають одне звернення до апстріму", async () => {
    await Promise.all([cache.get(), cache.get(), cache.get()]);

    expect(upstream.calls).toBe(1);
  });
});

describe("порожній список і відсутність даних — різні стани", () => {
  it("перевірено, тривог немає", async () => {
    const response = await cache.get();

    expect(response.alerts).toEqual([]);
    expect(response.age_seconds).toBe(0);
  });

  it("успішних відповідей не було жодного разу", async () => {
    upstream.result = { ok: false, status: 401, reason: "http" };
    const response = await cache.get();

    expect(response.alerts).toBeNull();
    expect(response.age_seconds).toBeNull();
    expect(response.fetched_at).toBeNull();
    expect(response.upstream_ok).toBe(false);
    expect(response.upstream_status).toBe(401);
  });
});

describe("апстрім упав", () => {
  it("віддаємо останні відомі дані з чесним віком", async () => {
    upstream.result = { ok: true, alerts: [ALERT] };
    await cache.get();

    upstream.result = { ok: false, status: 500, reason: "http" };
    clock.advance(TTL_MS);
    const response = await cache.get();

    expect(response.alerts).toEqual([ALERT]);
    expect(response.upstream_ok).toBe(false);
    expect(response.upstream_status).toBe(500);
    // 15 с — застосунок ще довіряє цим даним; після 3 хв спрацює fail-safe.
    expect(response.age_seconds).toBe(15);
  });

  it("вік доростає до порогу застарілості, поки апстрім лежить", async () => {
    upstream.result = { ok: true, alerts: [ALERT] };
    await cache.get();

    upstream.result = { ok: false, status: null, reason: "network" };
    clock.advance(4 * 60_000);
    const response = await cache.get();

    expect(response.age_seconds).toBe(240);
    expect(response.upstream_status).toBeNull();
  });

  it("виняток із fetch не валить відповідь", async () => {
    cache = new AlertsCache({
      fetchAlerts: async () => {
        throw new Error("boom");
      },
      now: clock.now,
    });

    const response = await cache.get();

    expect(response.upstream_ok).toBe(false);
    expect(response.alerts).toBeNull();
  });
});

describe("expire()", () => {
  it("змушує оновитися, але не викидає останні відомі дані", async () => {
    upstream.result = { ok: true, alerts: [ALERT] };
    await cache.get();

    upstream.result = { ok: false, status: 429, reason: "http" };
    cache.expire();
    const response = await cache.get();

    expect(upstream.calls).toBe(2);
    expect(response.alerts).toEqual([ALERT]);
    expect(response.upstream_ok).toBe(false);
  });
});

describe("бекоф після 429", () => {
  it("хвилину не стукаємо в апстрім", async () => {
    upstream.result = { ok: false, status: 429, reason: "http" };
    await cache.get();

    clock.advance(TTL_MS * 2);
    await cache.get();
    expect(upstream.calls).toBe(1);

    clock.advance(RATE_LIMITED_BACKOFF_MS);
    await cache.get();
    expect(upstream.calls).toBe(2);
  });

  it("інші помилки повторюємо через звичайний TTL", async () => {
    upstream.result = { ok: false, status: 500, reason: "http" };
    await cache.get();

    clock.advance(TTL_MS);
    await cache.get();

    expect(upstream.calls).toBe(2);
  });
});
