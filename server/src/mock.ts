import type { AlertsResponse, Level } from "./types";

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
  level: Level;
  /** Коли «оголосили» тривогу, мс. Можна зсунути в минуле, щоб перевірити правило 24 год. */
  since: number;
}

export interface MockOptions {
  uid?: string;
  level?: string;
  /** Скільки годин тому почалася тривога. За замовчуванням — щойно. */
  startedHoursAgo?: string;
}

export function isMockScenario(value: string): value is MockScenario {
  return value === "clear" || value === "alert" || value === "down";
}

export class MockAlerts {
  private current: MockState = { scenario: "clear", uid: "16", level: "red", since: 0 };

  set(scenario: MockScenario, now: number, options: MockOptions = {}): void {
    const hours = Number(options.startedHoursAgo ?? 0);
    this.current = {
      scenario,
      uid: options.uid || this.current.uid,
      level: options.level === "yellow" ? "yellow" : options.level === "red" ? "red" : this.current.level,
      since: now - (Number.isFinite(hours) && hours > 0 ? hours * 3_600_000 : 0),
    };
  }

  state(): MockState {
    return { ...this.current };
  }

  restore(state: MockState): void {
    // Сценарій, збережений до появи рівнів, доповнюємо значеннями за замовчуванням.
    this.current = { ...state, level: state.level ?? "red", since: state.since ?? 0 };
  }

  response(now: number): AlertsResponse {
    const { scenario, uid, level, since } = this.current;
    const ageSeconds = scenario === "down" ? DOWN_AGE_SECONDS : 0;
    return {
      v: 1,
      alerts:
        scenario === "clear"
          ? []
          : [{ region: uid, levels: [{ level, since: new Date(since).toISOString(), reason: null }] }],
      confirmed_at: new Date(now - ageSeconds * 1000).toISOString(),
      age_seconds: ageSeconds,
    };
  }
}
