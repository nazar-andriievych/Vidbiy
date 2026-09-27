import { describe, expect, it } from "vitest";
import { MockAlerts, type MockState } from "../src/mock";

const NOW = Date.parse("2026-09-24T20:00:00Z");
const HOUR = 3_600_000;

describe("MockAlerts", () => {
  it("за замовчуванням тривог немає, дані свіжі", () => {
    expect(new MockAlerts().response(NOW)).toEqual({
      v: 1,
      alerts: [],
      confirmed_at: new Date(NOW).toISOString(),
      age_seconds: 0,
    });
  });

  it("оголошує червону тривогу у вказаному регіоні, щойно", () => {
    const mock = new MockAlerts();
    mock.set("alert", NOW, { uid: "8" });

    expect(mock.response(NOW).alerts).toEqual([
      { region: "8", levels: [{ level: "red", since: new Date(NOW).toISOString(), reason: null }] },
    ]);
  });

  it("рівень і давність задаються: так перевіряють «лише червона» і правило 24 год", () => {
    const mock = new MockAlerts();
    mock.set("alert", NOW, { uid: "8", level: "yellow", startedHoursAgo: "25" });

    const [alert] = mock.response(NOW).alerts!;

    expect(alert.levels).toEqual([
      { level: "yellow", since: new Date(NOW - 25 * HOUR).toISOString(), reason: null },
    ]);
  });

  it("down: тривога лишається, але дані застарілі — застосунок має дзвонити саме через вік", () => {
    const mock = new MockAlerts();
    mock.set("alert", NOW, { uid: "8" });
    mock.set("down", NOW);

    const response = mock.response(NOW);

    expect(response.alerts?.map((alert) => alert.region)).toEqual(["8"]);
    expect(response.age_seconds).toBeGreaterThan(180);
  });

  it("переживає перезапуск: збережений стан відновлюється повністю", () => {
    const before = new MockAlerts();
    before.set("alert", NOW, { uid: "16", level: "yellow" });

    const after = new MockAlerts();
    after.restore(before.state());

    expect(after.state()).toEqual({ scenario: "alert", uid: "16", level: "yellow", since: NOW });
    expect(after.response(NOW)).toEqual(before.response(NOW));
  });

  it("сценарій, збережений до появи рівнів, стає червоним", () => {
    const mock = new MockAlerts();
    mock.restore({ scenario: "alert", uid: "16" } as MockState);

    expect(mock.response(NOW).alerts?.[0].levels[0].level).toBe("red");
  });
});
