import { describe, expect, it } from "vitest";
import { AlertBoard, type Meta, type StateStore } from "../src/state";
import type { AlertEvent, RegionState } from "../src/types";

const T0 = Date.parse("2026-09-24T20:00:00Z");
const SECOND = 1000;
const MINUTE = 60 * SECOND;

function event(regionId: string | null, active: boolean, createdAt: number, alarmType = "AIR"): AlertEvent {
  return { regionId, alarmType, status: active ? "Activate" : "DEACTIVATE", active, createdAt };
}

/** Сховище в пам'яті — імітує сховище Durable Object між «засинаннями». */
function memoryStore() {
  const regions = new Map<string, RegionState>();
  let meta: Meta | undefined;
  const store: StateStore = {
    read: async () => ({ regions: new Map(regions), meta: meta && structuredClone(meta) }),
    writeRegions: async (changes) => {
      for (const [id, state] of changes) regions.set(id, state);
    },
    writeMeta: async (next) => {
      meta = structuredClone(next);
    },
  };
  return { store, regions };
}

async function syncedBoard(snapshot: Record<string, number> = {}, asOf = T0) {
  const { store, regions } = memoryStore();
  const board = new AlertBoard(store);
  await board.restore();
  await board.loadSnapshot(
    new Map(Object.entries(snapshot).map(([id, changedAt]) => [id, { active: true, changedAt }])),
    asOf,
  );
  return { board, store, regions };
}

describe("AlertBoard: відповідь", () => {
  it("до початкового знімка стан невідомий — навіть якщо вебхуки вже приходили", async () => {
    const board = new AlertBoard();
    await board.receive(event("16", true, T0), T0);

    expect(board.response(T0)).toEqual({ v: 2, active: null, heard_at: null, age_seconds: null });
  });

  it("після знімка показує активні регіони, відсортовані за номером", async () => {
    const { board } = await syncedBoard({ "124": T0 - MINUTE, "16": T0 - MINUTE });

    expect(board.response(T0 + 5 * SECOND)).toEqual({
      v: 2,
      active: ["16", "124"],
      heard_at: new Date(T0).toISOString(),
      age_seconds: 5,
    });
  });

  it("порожній список означає «перевірено, тривог немає», а не «не знаємо»", async () => {
    const { board } = await syncedBoard();

    expect(board.response(T0).active).toEqual([]);
  });

  it("вік рахується від останнього вебхука будь-якого типу: це пульс каналу", async () => {
    const { board } = await syncedBoard();
    await board.receive(event("124", true, T0 + 2 * MINUTE, "ARTILLERY"), T0 + 2 * MINUTE);

    const response = board.response(T0 + 2 * MINUTE + 10 * SECOND);

    expect(response.active).toEqual([]);
    expect(response.age_seconds).toBe(10);
  });

  it("без подій вік росте — і застосунок сам задзвонить за fail-safe", async () => {
    const { board } = await syncedBoard({ "16": T0 });

    expect(board.response(T0 + 10 * MINUTE).age_seconds).toBe(600);
  });
});

describe("AlertBoard: вебхуки", () => {
  it("оголошення й відбій змінюють стан регіону", async () => {
    const { board } = await syncedBoard();

    expect(await board.receive(event("8", true, T0 + MINUTE), T0 + MINUTE)).toBe("changed");
    expect(board.response(T0 + MINUTE).active).toEqual(["8"]);

    expect(await board.receive(event("8", false, T0 + 2 * MINUTE), T0 + 2 * MINUTE)).toBe("changed");
    expect(board.response(T0 + 2 * MINUTE).active).toEqual([]);
  });

  it("запізніла стара подія не затирає новішу: інакше відбій прийшов би посеред тривоги", async () => {
    const { board } = await syncedBoard();
    await board.receive(event("8", true, T0 + 2 * MINUTE), T0 + 2 * MINUTE);

    const outcome = await board.receive(event("8", false, T0 + MINUTE), T0 + 3 * MINUTE);

    expect(outcome).toBe("out_of_order");
    expect(board.response(T0 + 3 * MINUTE).active).toEqual(["8"]);
  });

  it("дублікат нічого не ламає", async () => {
    const { board } = await syncedBoard();
    await board.receive(event("8", true, T0 + MINUTE), T0 + MINUTE);

    expect(await board.receive(event("8", true, T0 + MINUTE), T0 + MINUTE + SECOND)).toBe("unchanged");
    expect(board.response(T0 + MINUTE).active).toEqual(["8"]);
  });

  it("інші типи тривог і тестовий регіон не впливають на стан", async () => {
    const { board } = await syncedBoard();

    expect(await board.receive(event("8", true, T0, "ARTILLERY"), T0)).toBe("ignored");
    expect(await board.receive(event(null, true, T0), T0)).toBe("ignored");
    expect(board.response(T0).active).toEqual([]);
  });

  it("стан переживає засинання об'єкта", async () => {
    const { board, store } = await syncedBoard({ "16": T0 });
    await board.receive(event("8", true, T0 + MINUTE), T0 + MINUTE);

    const woken = new AlertBoard(store);
    await woken.restore();

    expect(woken.response(T0 + MINUTE).active).toEqual(["8", "16"]);
  });
});

describe("AlertBoard: початковий знімок", () => {
  it("не затирає вебхук, новіший за знімок", async () => {
    const board = new AlertBoard();
    // Відбій прийшов, поки ми чекали на знімок; знімок ще бачив тривогу.
    await board.receive(event("8", false, T0 + 5 * SECOND), T0 + 6 * SECOND);

    await board.loadSnapshot(new Map([["8", { active: true, changedAt: T0 - MINUTE }]]), T0);

    expect(board.response(T0 + 7 * SECOND).active).toEqual([]);
  });

  it("знімає тривоги, яких у знімку вже немає", async () => {
    const board = new AlertBoard();
    await board.receive(event("8", true, T0 - MINUTE), T0 - MINUTE);

    await board.loadSnapshot(new Map(), T0);

    expect(board.response(T0).active).toEqual([]);
  });

  it("зберігає в сховищі лише змінені регіони", async () => {
    const { regions } = await syncedBoard({ "16": T0 - MINUTE, "29": T0 - MINUTE });

    expect([...regions.keys()].sort()).toEqual(["16", "29"]);
  });
});

describe("AlertBoard: статистика", () => {
  it("рахує паузи між вебхуками й затримку доставки", async () => {
    const { board } = await syncedBoard();
    await board.receive(event("8", true, T0), T0 + 2 * SECOND);
    await board.receive(event("8", false, T0 + 30 * SECOND), T0 + 31 * SECOND);
    await board.receive(event("9", true, T0 + 5 * MINUTE), T0 + 5 * MINUTE + 4 * SECOND);

    const stats = board.stats(T0 + 6 * MINUTE).webhooks;

    expect(stats.received).toBe(3);
    expect(stats.changed).toBe(3);
    expect(stats.gaps).toEqual({ under1m: 1, under3m: 0, under10m: 1, under30m: 0, over30m: 0 });
    expect(stats.max_gap_seconds).toBe(273);
    expect(stats.delay_max_seconds).toBe(4);
    expect(stats.delay_avg_seconds).toBe(2);
    expect(stats.recent[0]).toMatchObject({ regionId: "9", outcome: "changed", delaySeconds: 4 });
  });

  it("тримає лише останні 30 подій", async () => {
    const { board } = await syncedBoard();
    for (let i = 0; i < 40; i++) {
      await board.receive(event("8", i % 2 === 0, T0 + i * SECOND), T0 + i * SECOND);
    }

    expect(board.stats(T0 + MINUTE).webhooks.recent).toHaveLength(30);
  });
});
