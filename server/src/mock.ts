import type { UpstreamResult } from "./alerts-in-ua";

/**
 * Підробка alerts.in.ua для локальної розробки.
 *
 * Потрібна, щоб перевіряти застосунок, не чекаючи на справжню тривогу:
 * можна вручну «оголосити тривогу», подивитися, що будильник мовчить,
 * потім «оголосити відбій» і переконатися, що він дзвонить протягом 2 хв (NFR-3).
 */
export type MockScenario = "clear" | "alert" | "down";

export function isMockScenario(value: string): value is MockScenario {
  return value === "clear" || value === "alert" || value === "down";
}

export class MockUpstream {
  private scenario: MockScenario = "clear";
  private uid = "16";
  private startedAt = new Date().toISOString();

  set(scenario: MockScenario, uid?: string): void {
    if (scenario === "alert" && this.scenario !== "alert") {
      this.startedAt = new Date().toISOString();
    }
    this.scenario = scenario;
    if (uid) this.uid = uid;
  }

  state(): { scenario: MockScenario; uid: string } {
    return { scenario: this.scenario, uid: this.uid };
  }

  fetch = async (): Promise<UpstreamResult> => {
    switch (this.scenario) {
      case "down":
        return { ok: false, status: 429, reason: "http" };
      case "clear":
        return { ok: true, alerts: [] };
      case "alert":
        return {
          ok: true,
          alerts: [{ uid: this.uid, type: "oblast", started_at: this.startedAt }],
        };
    }
  };
}
