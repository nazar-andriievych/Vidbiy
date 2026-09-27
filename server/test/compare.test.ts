import { describe, expect, it } from "vitest";
import { CONFIRM_MS, ShadowCompare } from "../src/compare";
import type { AlertEvent, RegionState } from "../src/types";

const T0 = Date.parse("2026-09-27T10:00:00Z");
const MINUTE = 60_000;

function snap(entries: Record<string, boolean>, changedAt = T0 - MINUTE): Map<string, RegionState> {
  return new Map(Object.entries(entries).map(([id, active]) => [id, { active, changedAt }]));
}

function event(regionId: string, active: boolean, createdAt: number): AlertEvent {
  return { regionId, alarmType: "AIR", status: active ? "Activate" : "DEACTIVATE", active, levels: [], createdAt };
}

describe("ShadowCompare", () => {
  it("перший знімок — точка відліку, без порівняння", () => {
    const compare = new ShadowCompare();
    compare.compare(snap({ "1": true }), T0);
    expect(compare.stats(T0)).toMatchObject({ comparisons: 0, confirmed: 0, open: [] });
  });

  it("вебхуки й знімок збігаються — розбіжностей немає", () => {
    const compare = new ShadowCompare();
    compare.compare(snap({ "1": true }), T0);
    compare.apply(event("2", true, T0 + 10_000));
    compare.apply(event("1", false, T0 + 20_000));
    compare.compare(snap({ "2": true }), T0 + MINUTE);
    expect(compare.stats(T0 + MINUTE)).toMatchObject({ comparisons: 1, transient: 0, confirmed: 0, open: [] });
  });

  it("знімок випередив вебхук — розбіжність зникає сама й рахується як затримка", () => {
    const compare = new ShadowCompare();
    compare.compare(snap({}), T0);
    compare.compare(snap({ "7": true }), T0 + MINUTE);
    expect(compare.stats(T0 + MINUTE).open).toEqual([{ regionId: "7", webhooks: false, alerts: true, seconds: 0 }]);

    compare.apply(event("7", true, T0 + MINUTE - 5_000));
    compare.compare(snap({ "7": true }), T0 + 2 * MINUTE);
    expect(compare.stats(T0 + 2 * MINUTE)).toMatchObject({ transient: 1, confirmed: 0, open: [] });
  });

  it("розбіжність, що тримається довше CONFIRM_MS, підтверджується один раз і виправляє тінь", () => {
    const compare = new ShadowCompare();
    compare.compare(snap({ "7": true }), T0);
    // Відбій, про який вебхук так і не прийшов.
    for (let t = T0 + MINUTE; t <= T0 + MINUTE + CONFIRM_MS; t += MINUTE) compare.compare(snap({}), t);

    const stats = compare.stats(T0 + 10 * MINUTE);
    expect(stats.confirmed).toBe(1);
    expect(stats.records[0]).toMatchObject({ regionId: "7", webhooks: true, alerts: false });
    expect(stats.open).toEqual([]);

    compare.compare(snap({}), T0 + 10 * MINUTE);
    expect(compare.stats(T0 + 10 * MINUTE)).toMatchObject({ confirmed: 1, transient: 0 });
  });

  it("вебхук, новіший за знімок, не вважається розбіжністю", () => {
    const compare = new ShadowCompare();
    compare.compare(snap({}), T0);
    compare.apply(event("3", true, T0 + MINUTE + 1_000));
    compare.compare(snap({}), T0 + MINUTE);
    expect(compare.stats(T0 + MINUTE)).toMatchObject({ open: [], confirmed: 0 });
  });

  it("до першого знімка вебхуки тінь не чіпають, і не-повітряні теж", () => {
    const compare = new ShadowCompare();
    expect(compare.apply(event("1", true, T0))).toBe(false);
    compare.compare(snap({}), T0);
    expect(compare.apply({ ...event("1", true, T0 + 1), alarmType: "ARTILLERY" })).toBe(false);
    expect(compare.apply(event("1", true, T0 + 1))).toBe(true);
  });
});
