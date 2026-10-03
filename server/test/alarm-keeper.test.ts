import { describe, expect, it, vi } from "vitest";
import { AlarmKeeper, CHECK_EVERY_MS, type AlarmStorage } from "../src/alarm-keeper";

const T0 = Date.parse("2026-10-03T10:00:00Z");
const INTERVAL = 30_000;

function fakeStorage(alarm: number | null = null) {
  const storage = {
    alarm,
    failSet: false,
    gets: 0,
    async getAlarm() {
      storage.gets++;
      return storage.alarm;
    },
    async setAlarm(at: number) {
      if (storage.failSet) throw new Error("rows written limit");
      storage.alarm = at;
    },
  };
  return storage satisfies AlarmStorage;
}

describe("AlarmKeeper", () => {
  it("заводить будильник, якщо його немає", async () => {
    const storage = fakeStorage();
    await new AlarmKeeper(storage, INTERVAL).ensure(T0);

    expect(storage.alarm).toBe(T0 + INTERVAL);
  });

  it("не чіпає будильник, що вже заведений", async () => {
    const storage = fakeStorage(T0 + 5_000);
    await new AlarmKeeper(storage, INTERVAL).ensure(T0);

    expect(storage.alarm).toBe(T0 + 5_000);
  });

  it("не читає сховище на кожен запит — не частіше ніж раз на хвилину", async () => {
    const storage = fakeStorage(T0 + INTERVAL);
    const keeper = new AlarmKeeper(storage, INTERVAL);
    await keeper.ensure(T0);
    await keeper.ensure(T0 + 10_000);
    await keeper.ensure(T0 + CHECK_EVERY_MS - 1);

    expect(storage.gets).toBe(1);
  });

  it("поки alarm() сам заводить наступний будильник, перевірок немає", async () => {
    const storage = fakeStorage(T0 + INTERVAL);
    const keeper = new AlarmKeeper(storage, INTERVAL);
    await keeper.ensure(T0);
    for (let at = T0 + INTERVAL; at <= T0 + 10 * INTERVAL; at += INTERVAL) {
      keeper.armed(at);
      await keeper.ensure(at + 1_000);
    }

    expect(storage.gets).toBe(1);
  });

  it("помічає обірваний ланцюжок за хвилину, навіть коли запити йдуть безперервно", async () => {
    const storage = fakeStorage(T0 + INTERVAL);
    const keeper = new AlarmKeeper(storage, INTERVAL);
    const log = vi.spyOn(console, "error").mockImplementation(() => {});
    await keeper.ensure(T0);

    // Будильник спрацював, а наступний завести не вдалося: ланцюжка більше немає.
    storage.alarm = null;
    let now = T0;
    while (storage.alarm === null && now < T0 + 10 * CHECK_EVERY_MS) {
      now += 5_000;
      await keeper.ensure(now);
    }

    expect(storage.alarm).toBe(now + INTERVAL);
    expect(now - T0).toBeLessThanOrEqual(CHECK_EVERY_MS);
    expect(log).toHaveBeenCalledWith(expect.stringContaining("обірвався"));
    log.mockRestore();
  });

  it("невдале заведення не ламає запит і повторюється наступного разу", async () => {
    const storage = fakeStorage();
    storage.failSet = true;
    const keeper = new AlarmKeeper(storage, INTERVAL);
    const log = vi.spyOn(console, "error").mockImplementation(() => {});

    await expect(keeper.ensure(T0)).resolves.toBeUndefined();
    expect(storage.alarm).toBeNull();

    storage.failSet = false;
    await keeper.ensure(T0 + 1_000);
    expect(storage.alarm).toBe(T0 + 1_000 + INTERVAL);
    log.mockRestore();
  });
});
