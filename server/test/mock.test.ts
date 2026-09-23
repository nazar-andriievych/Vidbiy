import { afterEach, describe, expect, it, vi } from "vitest";
import { MockUpstream } from "../src/mock";

describe("MockUpstream", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("за замовчуванням тривог немає", async () => {
    const mock = new MockUpstream();

    expect(await mock.fetch()).toEqual({ ok: true, alerts: [] });
  });

  it("оголошує тривогу у вказаному регіоні", async () => {
    const mock = new MockUpstream();
    mock.set("alert", "8");

    const result = await mock.fetch();

    expect(result.ok).toBe(true);
    expect(result.ok && result.alerts).toEqual([
      { uid: "8", type: "oblast", started_at: expect.any(String) },
    ]);
  });

  it("переживає перезапуск: збережений стан відновлюється повністю", async () => {
    const before = new MockUpstream();
    before.set("alert", "16");
    const saved = before.snapshot();

    // Durable Object заснув і прокинувся порожнім — саме тут тривога раніше зникала.
    const after = new MockUpstream();
    after.restore(saved);

    expect(after.state()).toEqual({ scenario: "alert", uid: "16" });
    expect(await after.fetch()).toEqual(await before.fetch());
  });

  it("нова тривога після відбою починається заново", async () => {
    // Без керованого годинника обидва виклики потрапляють в одну мілісекунду.
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-23T05:00:00.000Z"));
    const mock = new MockUpstream();
    mock.set("alert", "8");
    const firstStart = mock.snapshot().startedAt;

    mock.set("clear");
    vi.setSystemTime(new Date("2026-09-23T06:30:00.000Z"));
    mock.set("alert");

    expect(mock.snapshot().startedAt).toBe("2026-09-23T06:30:00.000Z");
    expect(mock.snapshot().startedAt).not.toBe(firstStart);
  });
});
