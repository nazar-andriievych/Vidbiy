import { describe, expect, it } from "vitest";
import { MockAlerts } from "../src/mock";

const NOW = Date.parse("2026-09-24T20:00:00Z");

describe("MockAlerts", () => {
  it("за замовчуванням тривог немає, дані свіжі", () => {
    expect(new MockAlerts().response(NOW)).toEqual({
      v: 2,
      active: [],
      heard_at: new Date(NOW).toISOString(),
      age_seconds: 0,
    });
  });

  it("оголошує тривогу у вказаному регіоні", () => {
    const mock = new MockAlerts();
    mock.set("alert", "8");

    expect(mock.response(NOW).active).toEqual(["8"]);
  });

  it("down: тривога лишається, але дані застарілі — застосунок має дзвонити саме через вік", () => {
    const mock = new MockAlerts();
    mock.set("alert", "8");
    mock.set("down");

    const response = mock.response(NOW);

    expect(response.active).toEqual(["8"]);
    expect(response.age_seconds).toBeGreaterThan(180);
  });

  it("переживає перезапуск: збережений стан відновлюється повністю", () => {
    const before = new MockAlerts();
    before.set("alert", "16");

    const after = new MockAlerts();
    after.restore(before.state());

    expect(after.state()).toEqual({ scenario: "alert", uid: "16" });
    expect(after.response(NOW)).toEqual(before.response(NOW));
  });
});
