import { describe, expect, it, vi } from "vitest";
import type { AlertsResponse } from "../src/types";

// Справжній DurableObject є лише в середовищі Cloudflare; маршрутизації він не потрібен.
vi.mock("cloudflare:workers", () => ({ DurableObject: class {} }));

const { default: worker } = await import("../src/index");
type Env = Parameters<typeof worker.fetch>[1];

const KNOWN: AlertsResponse = { v: 1, alerts: [], confirmed_at: "2026-10-06T10:00:00.000Z", age_seconds: 3 };
const UNKNOWN: AlertsResponse = { v: 1, alerts: null, confirmed_at: null, age_seconds: null };

/** Підробний Durable Object: записує, що в нього просили. */
function fakeHub(overrides: Record<string, unknown> = {}) {
  const calls: string[] = [];
  const hub = {
    getAlerts: async () => (calls.push("getAlerts"), KNOWN),
    setMock: async (scenario: string) => (calls.push(`setMock:${scenario}`), { scenario }),
    mockState: async () => (calls.push("mockState"), {}),
    log: async (hours: number) => (calls.push(`log:${hours}`), []),
    stats: async () => (calls.push("stats"), {}),
    receive: async () => (calls.push("receive"), "changed"),
    ...overrides,
  };
  const env = {
    ALERTS_HUB: { idFromName: () => "global", get: () => hub },
  } as unknown as Env;
  return { env, calls };
}

function get(path: string, init?: RequestInit) {
  return new Request(`https://proxy.example${path}`, init);
}

describe("маршрути воркера", () => {
  it("/v1/alerts віддає стан без кешування: інакше вік даних брехав би", async () => {
    const { env } = fakeHub();

    const response = await worker.fetch(get("/v1/alerts"), env);

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toEqual(KNOWN);
  });

  it("недосяжний стан — «даних немає», щоб застосунок задзвонив за fail-safe", async () => {
    const { env } = fakeHub({ getAlerts: async () => { throw new Error("DO down"); } });

    expect(await (await worker.fetch(get("/v1/alerts"), env)).json()).toEqual(UNKNOWN);
  });

  it("/mock без MOCK=1 не існує: на бойовому сервері тривогу не підробити", async () => {
    const { env, calls } = fakeHub();

    const response = await worker.fetch(get("/mock?scenario=clear"), env);

    expect(response.status).toBe(404);
    expect(calls).toEqual([]);
  });

  it("/mock з MOCK=1 перемикає сценарій, незнайомий — 400", async () => {
    const { env, calls } = fakeHub();
    const mockEnv = { ...env, MOCK: "1" } as Env;

    expect((await worker.fetch(get("/mock?scenario=alert&uid=75"), mockEnv)).status).toBe(200);
    expect((await worker.fetch(get("/mock?scenario=boom"), mockEnv)).status).toBe(400);
    expect(calls).toEqual(["setMock:alert"]);
  });

  it("вебхук без підпису відхиляється ще до стану", async () => {
    const { env, calls } = fakeHub();

    const response = await worker.fetch(get("/webhook", { method: "POST", body: "{}" }), env);

    expect(response.status).toBe(401);
    expect(calls).toEqual([]);
  });

  it("завеликий вебхук відхиляється, не перевіряючи підпису", async () => {
    const { env } = fakeHub();

    const response = await worker.fetch(get("/webhook", { method: "POST", body: "x".repeat(64 * 1024 + 1) }), env);

    expect(response.status).toBe(413);
  });

  it("вебхук лише через POST, решта маршрутів — лише читання", async () => {
    const { env } = fakeHub();

    expect((await worker.fetch(get("/webhook"), env)).status).toBe(405);
    expect((await worker.fetch(get("/v1/alerts", { method: "POST" }), env)).status).toBe(405);
  });

  it("/log тримає години в межах журналу: 6 за замовчуванням, не більше 48", async () => {
    const { env, calls } = fakeHub();

    await worker.fetch(get("/log"), env);
    await worker.fetch(get("/log?hours=500"), env);
    await worker.fetch(get("/log?hours=abc"), env);

    expect(calls).toEqual(["log:6", "log:48", "log:6"]);
  });

  it("невідома адреса — 404", async () => {
    const { env } = fakeHub();
    expect((await worker.fetch(get("/admin"), env)).status).toBe(404);
  });
});
