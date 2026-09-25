import { describe, expect, it } from "vitest";
import { CallBudget, PER_DAY, PER_MINUTE } from "../src/budget";

const T0 = Date.parse("2026-09-25T10:00:00Z");

describe("CallBudget", () => {
  it(`пропускає не більше ${PER_MINUTE} запитів за будь-які 60 с`, () => {
    const budget = new CallBudget();

    const allowed = Array.from({ length: 10 }, (_, i) => budget.take(T0 + i * 1000));

    expect(allowed.filter(Boolean)).toHaveLength(PER_MINUTE);
    expect(budget.stats(T0 + 10_000).refused_by_budget).toBe(10 - PER_MINUTE);
  });

  it("через хвилину знову пропускає", () => {
    const budget = new CallBudget();
    for (let i = 0; i < PER_MINUTE; i++) budget.take(T0);

    expect(budget.take(T0 + 59_999)).toBe(false);
    expect(budget.take(T0 + 60_000)).toBe(true);
  });

  it(`не більше ${PER_DAY} за добу, і лічильник обнуляється опівночі UTC`, () => {
    const budget = new CallBudget();
    const dayStart = Date.parse("2026-09-25T00:00:00Z");
    let allowed = 0;
    // Раз на 21 с — у межах хвилинного ліміту, але за добу це понад 4000 спроб.
    for (let t = dayStart; t < dayStart + 86_400_000; t += 21_000) {
      if (budget.take(t)) allowed++;
    }

    expect(allowed).toBe(PER_DAY);
    expect(budget.take(dayStart + 86_400_000)).toBe(true);
  });

  it("переживає засинання: стан відновлюється зі збереженого", () => {
    const before = new CallBudget();
    for (let i = 0; i < PER_MINUTE; i++) before.take(T0);

    const after = new CallBudget(structuredClone(before.snapshot()));

    expect(after.take(T0 + 1000)).toBe(false);
  });
});
