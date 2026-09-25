import { describe, expect, it } from "vitest";
import { RetryProbe, sha256Hex } from "../src/probe";

const T0 = Date.parse("2026-09-25T10:00:00Z");

describe("RetryProbe", () => {
  it("вимкнена — ніколи не відмовляє", () => {
    const probe = new RetryProbe();

    for (let i = 0; i < 10; i++) expect(probe.onDelivery(`h${i}`, T0, 0).refuse).toBe(false);
  });

  it("відмовляє кожну N-ту подію й помічає повтор", () => {
    const probe = new RetryProbe();

    expect(probe.onDelivery("a", T0, 2).refuse).toBe(false);
    expect(probe.onDelivery("b", T0, 2).refuse).toBe(true);
    // Та сама подія прийшла вдруге через 30 с — повтор, і вдруге її не відхиляємо.
    expect(probe.onDelivery("b", T0 + 30_000, 2).refuse).toBe(false);

    expect(probe.stats(2)).toEqual({
      every: 2,
      refused: 1,
      redelivered: 1,
      redelivery_delays_seconds: [30],
      waiting: 0,
    });
  });

  it("повтори рахуються й після вимкнення перевірки", () => {
    const probe = new RetryProbe();
    probe.onDelivery("a", T0, 1);

    probe.onDelivery("a", T0 + 5_000, 0);

    expect(probe.stats(0).redelivered).toBe(1);
  });

  it("відбиток залежить від тіла", async () => {
    expect(await sha256Hex("x")).not.toBe(await sha256Hex("y"));
    expect(await sha256Hex("x")).toHaveLength(64);
  });
});
