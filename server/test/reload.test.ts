import { describe, expect, it } from "vitest";
import {
  emptyReload,
  FAST_INTERVAL_MS,
  ReloadSchedule,
  SLOW_INTERVAL_MS,
  TRIP_COOLDOWN_MS,
} from "../src/reload";

const T0 = Date.parse("2026-09-27T10:00:00Z");
const MINUTE = 60_000;

/** Проганяє спроби щохвилини: `+` — успіх, `-` — відмова. */
function run(schedule: ReloadSchedule, pattern: string, start = T0): number {
  let now = start;
  for (const mark of pattern) {
    schedule.record(mark === "+", now);
    now += FAST_INTERVAL_MS;
  }
  return now;
}

describe("ReloadSchedule", () => {
  it("за замовчуванням щохвилини, й після відмови теж", () => {
    const schedule = new ReloadSchedule(emptyReload());
    schedule.record(true, T0);
    expect(schedule.snapshot().nextAt).toBe(T0 + FAST_INTERVAL_MS);
    schedule.record(false, T0 + MINUTE);
    expect(schedule.snapshot().nextAt).toBe(T0 + MINUTE + FAST_INTERVAL_MS);
  });

  it("дві відмови поспіль терпить, три — година на рідкому розкладі", () => {
    const schedule = new ReloadSchedule(emptyReload());
    run(schedule, "+--+");
    expect(schedule.fast(T0 + 5 * MINUTE)).toBe(true);

    const end = run(schedule, "---", T0 + 5 * MINUTE);
    expect(schedule.snapshot().tripped?.reason).toBe("3 відмови поспіль");
    expect(schedule.snapshot().trips).toBe(1);
    expect(schedule.fast(end)).toBe(false);
    expect(schedule.snapshot().nextAt).toBe(T0 + 7 * MINUTE + SLOW_INTERVAL_MS);
  });

  it("через годину після спрацювання повертається до щохвилинного з чистим вікном", () => {
    const schedule = new ReloadSchedule(emptyReload());
    run(schedule, "---");
    const trippedAt = T0 + 2 * MINUTE;
    expect(schedule.fast(trippedAt + TRIP_COOLDOWN_MS - 1)).toBe(false);

    schedule.record(true, trippedAt + TRIP_COOLDOWN_MS);

    expect(schedule.snapshot().tripped).toBeNull();
    expect(schedule.snapshot().trail).toBe("+");
    expect(schedule.snapshot().nextAt).toBe(trippedAt + TRIP_COOLDOWN_MS + FAST_INTERVAL_MS);
  });

  it("вимикає на 7 відмовах серед останніх 30, навіть не поспіль", () => {
    const schedule = new ReloadSchedule(emptyReload());
    const end = run(schedule, "+".repeat(1000));
    // Через раз: загальна частка ще далеко від 20 %, але вікно помічає одразу.
    run(schedule, "+-+-+-+-+-+-", end);
    expect(schedule.snapshot().tripped).toBeNull();
    run(schedule, "+-", end + 12 * MINUTE);
    expect(schedule.snapshot().tripped?.reason).toBe("7 відмов серед останніх 30 спроб");
  });

  it("старі відмови випадають із вікна", () => {
    const schedule = new ReloadSchedule(emptyReload());
    const end = run(schedule, "+-+-+-+-+-+-" + "+".repeat(30));
    run(schedule, "-+-+-+", end);
    expect(schedule.snapshot().tripped).toBeNull();
  });

  it("рахує найдовший проміжок між успіхами", () => {
    const schedule = new ReloadSchedule(emptyReload());
    run(schedule, "+--+");
    expect(schedule.snapshot().maxOkGapSeconds).toBe(180);
    expect(schedule.snapshot().maxConsecutiveFailed).toBe(2);
  });

  it("лічильники переживають деплой, поля старого експерименту відкидаються", () => {
    const legacy = { ...emptyReload(), attempts: 764, fastUntil: T0, fastAttempts: 764, tripped: null };

    const restored = ReloadSchedule.restore(legacy).snapshot();

    expect(restored.attempts).toBe(764);
    expect(restored).not.toHaveProperty("fastUntil");
    expect(restored.trips).toBe(0);
  });
});
