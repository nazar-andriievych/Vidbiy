import type { AlertsResponse } from "./types";

/**
 * Підробка стану тривог для локальної розробки.
 *
 * Потрібна, щоб перевіряти застосунок, не чекаючи на справжню тривогу:
 * можна вручну «оголосити тривогу», подивитися, що будильник мовчить,
 * потім «оголосити відбій» і переконатися, що він дзвонить протягом 2 хв (NFR-3).
 *
 * `down` — проксі давно нічого не чув від ukrainealarm. Тривога в регіоні при цьому
 * лишається: застосунок має задзвонити саме через застарілість даних (NFR-1),
 * а не тому, що «все чисто».
 */
export type MockScenario = "clear" | "alert" | "down";

/** Скільки «не чули» в сценарії `down`: свідомо більше за поріг застосунку в 3 хв. */
const DOWN_AGE_SECONDS = 10 * 60;

/** Те, що переживає засинання Durable Object: інакше тривога тихо зникала б сама. */
export interface MockState {
  scenario: MockScenario;
  uid: string;
}

export function isMockScenario(value: string): value is MockScenario {
  return value === "clear" || value === "alert" || value === "down";
}

export class MockAlerts {
  private scenario: MockScenario = "clear";
  private uid = "16";

  set(scenario: MockScenario, uid?: string): void {
    this.scenario = scenario;
    if (uid) this.uid = uid;
  }

  state(): MockState {
    return { scenario: this.scenario, uid: this.uid };
  }

  restore(state: MockState): void {
    this.scenario = state.scenario;
    this.uid = state.uid;
  }

  response(now: number): AlertsResponse {
    const ageSeconds = this.scenario === "down" ? DOWN_AGE_SECONDS : 0;
    return {
      v: 2,
      active: this.scenario === "clear" ? [] : [this.uid],
      heard_at: new Date(now - ageSeconds * 1000).toISOString(),
      age_seconds: ageSeconds,
    };
  }
}
