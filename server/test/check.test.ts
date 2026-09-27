import { describe, expect, it } from "vitest";
import { runCheck, RETRY_AFTER_FAILURE_MS, type CheckApi } from "../src/check";
import {
  AlertBoard,
  AUDIT_MS,
  RELOAD_BACKOFF_MS,
  RELOAD_MIN_INTERVAL_MS,
  SILENCE_MS,
} from "../src/state";
import type { AlertEvent, AlertsResponse, RegionState } from "../src/types";
import type { ApiFailure, SnapshotResult, StatusResult } from "../src/ukrainealarm";

const RED = { level: "red" as const, since: 0, reason: null };

function ids(response: AlertsResponse): string[] | null {
  return response.alerts?.map((alert) => alert.region) ?? null;
}

const T0 = Date.parse("2026-09-25T02:00:00Z");
const SECOND = 1000;
const MINUTE = 60 * SECOND;

/** Номер зміни так, як його видає ukrainealarm: .NET ticks моменту зміни. */
function ticks(ms: number): string {
  return String(BigInt(ms) * 10_000n + 621_355_968_000_000_000n);
}

/** Підроблений API з годинником, який ми рухаємо самі. */
function fakeApi(start = T0) {
  const api = {
    time: start,
    index: ticks(start - 5 * SECOND),
    regions: new Map<string, RegionState>(),
    statusFails: false,
    alertsFails: false,
    calls: [] as string[],
    now: () => api.time,
    async status(): Promise<StatusResult | ApiFailure> {
      api.calls.push("status");
      return api.statusFails ? { ok: false, status: 401 } : { ok: true, index: api.index };
    },
    async snapshot(): Promise<SnapshotResult | ApiFailure> {
      api.calls.push("alerts");
      return api.alertsFails ? { ok: false, status: 401 } : { ok: true, regions: new Map(api.regions) };
    },
  };
  return api satisfies CheckApi;
}

function airEvent(regionId: string, active: boolean, createdAt: number): AlertEvent {
  return { regionId, alarmType: "AIR", status: active ? "Activate" : "DEACTIVATE", active, levels: active ? [RED] : [], createdAt };
}

/** Дошка після успішного старту о T0 і API, що пам'ятає лише наступні виклики. */
async function started(regions: Record<string, boolean> = {}) {
  const board = new AlertBoard();
  const api = fakeApi();
  for (const [id, active] of Object.entries(regions)) api.regions.set(id, { active, changedAt: T0 });
  await runCheck(board, api);
  api.calls = [];
  return { board, api };
}

describe("runCheck: старт", () => {
  it("бере номер і повний список — два запити", async () => {
    const board = new AlertBoard();
    const api = fakeApi();
    api.regions.set("16", { active: true, changedAt: T0 - MINUTE });

    expect(await runCheck(board, api)).toBe("reloaded");
    expect(api.calls).toEqual(["status", "alerts"]);
    expect(ids(board.response(T0))).toEqual(["16"]);
  });

  it("без знімка стан лишається невідомим, поки API недоступний", async () => {
    const board = new AlertBoard();
    const api = fakeApi();
    api.statusFails = true;

    expect(await runCheck(board, api)).toBe("failed");
    expect(ids(board.response(T0))).toBeNull();
  });
});

describe("runCheck: тиша", () => {
  it("той самий номер: один запит, і дані знову свіжі — будильник чекає далі", async () => {
    const { board, api } = await started({ "16": true });

    api.time = T0 + SILENCE_MS;
    expect(await runCheck(board, api)).toBe("confirmed");
    expect(api.calls).toEqual(["status"]);

    const response = board.response(api.time + 5 * SECOND);
    expect(ids(response)).toEqual(["16"]);
    expect(response.age_seconds).toBe(5);
  });

  it("поки підтвердження свіже, до API не ходимо", async () => {
    const { board, api } = await started();

    api.time = T0 + SILENCE_MS - SECOND;
    expect(await runCheck(board, api)).toBe("skipped");
    expect(api.calls).toEqual([]);
  });

  it("вебхук теж підтверджує свіжість і відсуває перевірку", async () => {
    const { board, api } = await started();
    await board.receive(airEvent("8", true, T0 + 50 * SECOND), T0 + 50 * SECOND);

    api.time = T0 + SILENCE_MS + 10 * SECOND;
    expect(await runCheck(board, api)).toBe("skipped");
  });
});

describe("runCheck: номер змінився", () => {
  it("усі зміни ми бачили у вебхуках — підтверджуємо без повного списку", async () => {
    const { board, api } = await started();
    await board.receive(airEvent("8", true, T0 + 10 * SECOND), T0 + 11 * SECOND);
    // Номер указує на ту саму подію; у номері мікросекунди, у вебхуку — рівні секунди.
    api.index = ticks(T0 + 10 * SECOND + 700);

    api.time = T0 + 11 * SECOND + SILENCE_MS;
    expect(await runCheck(board, api)).toBe("confirmed");
    expect(api.calls).toEqual(["status"]);
    expect(board.stats(api.time).checks.covered_by_webhooks).toBe(1);
  });

  it("зміна новіша за побачені вебхуки — пропустили подію, перезавантажуємо список", async () => {
    const { board, api } = await started({ "16": true });
    await board.receive(airEvent("8", true, T0 + 10 * SECOND), T0 + 11 * SECOND);
    // Відбій у регіоні 16 стався о T0+90 с, але вебхук до нас не дійшов.
    api.index = ticks(T0 + 90 * SECOND);
    api.regions.clear();
    api.regions.set("8", { active: true, changedAt: T0 + 10 * SECOND });

    api.time = T0 + RELOAD_MIN_INTERVAL_MS;
    expect(await runCheck(board, api)).toBe("reloaded");
    expect(api.calls).toEqual(["status", "alerts"]);
    expect(ids(board.response(api.time))).toEqual(["8"]);
  });

  it("незрозумілий номер — не вгадуємо, а беремо список", async () => {
    const { board, api } = await started();
    api.index = "42";

    api.time = T0 + RELOAD_MIN_INTERVAL_MS;
    expect(await runCheck(board, api)).toBe("reloaded");
  });
});

describe("runCheck: обмежувач повного списку", () => {
  it("не частіше ніж раз на 2 хв; поки чекаємо — свіжість не оновлюється", async () => {
    const { board, api } = await started({ "16": true });
    api.index = ticks(T0 + 30 * SECOND);

    api.time = T0 + SILENCE_MS;
    expect(await runCheck(board, api)).toBe("deferred");
    expect(api.calls).toEqual(["status"]);
    expect(board.response(api.time).age_seconds).toBe(60);

    // Наступна спроба — через хвилину, і тоді список уже можна.
    api.time = T0 + SILENCE_MS + RETRY_AFTER_FAILURE_MS - SECOND;
    expect(await runCheck(board, api)).toBe("skipped");
    api.time = T0 + RELOAD_MIN_INTERVAL_MS;
    expect(await runCheck(board, api)).toBe("reloaded");
  });

  it("після 401 на повний список — пауза 3 хв, а status і далі щохвилини", async () => {
    const { board, api } = await started({ "16": true });
    api.index = ticks(T0 + 30 * SECOND);
    api.alertsFails = true;

    api.time = T0 + RELOAD_MIN_INTERVAL_MS;
    expect(await runCheck(board, api)).toBe("failed");
    const failedAt = api.time;

    api.calls = [];
    api.time = failedAt + RETRY_AFTER_FAILURE_MS;
    expect(await runCheck(board, api)).toBe("deferred");
    expect(api.calls).toEqual(["status"]);

    api.alertsFails = false;
    api.calls = [];
    api.time = failedAt + RELOAD_BACKOFF_MS;
    expect(await runCheck(board, api)).toBe("reloaded");
    expect(api.calls).toEqual(["status", "alerts"]);
  });

  it("звірка не заважає свіжості: якщо список відмовив, стан однаково підтверджено", async () => {
    const { board, api } = await started({ "16": true });
    api.alertsFails = true;

    api.time = T0 + AUDIT_MS;
    expect(await runCheck(board, api)).toBe("confirmed");
    expect(api.calls).toEqual(["status", "alerts"]);
    expect(board.response(api.time).age_seconds).toBe(0);
  });

  it("навіть під безперервними вебхуками раз на 10 хв беремо повний список", async () => {
    const { board, api } = await started();
    for (let t = 30 * SECOND; t <= AUDIT_MS; t += 30 * SECOND) {
      await board.receive(airEvent("8", true, T0 + t), T0 + t);
    }
    api.index = ticks(T0 + AUDIT_MS);

    api.time = T0 + AUDIT_MS;
    expect(await runCheck(board, api)).toBe("reloaded");
    expect(api.calls).toEqual(["status", "alerts"]);
  });
});

describe("runCheck: невдачі", () => {
  it("невдача не оновлює свіжість і не частішає: наступна спроба лише за хвилину", async () => {
    const { board, api } = await started({ "16": true });
    api.statusFails = true;

    api.time = T0 + SILENCE_MS;
    expect(await runCheck(board, api)).toBe("failed");

    api.calls = [];
    api.time += RETRY_AFTER_FAILURE_MS - SECOND;
    expect(await runCheck(board, api)).toBe("skipped");
    expect(api.calls).toEqual([]);

    // Три хвилини без підтверджень — застосунок задзвонить за fail-safe.
    expect(board.response(T0 + 3 * MINUTE).age_seconds).toBe(180);
  });

  it("у найгіршому разі: не більше одного status за хвилину й одного списку за 2 хв", async () => {
    const { board, api } = await started({ "16": true });
    // Номер змінюється щохвилини, вебхуків немає, список відмовляє через раз.
    let flip = false;
    for (let t = T0 + 30 * SECOND; t <= T0 + 30 * MINUTE; t += 30 * SECOND) {
      api.time = t;
      api.index = ticks(t - SECOND);
      api.alertsFails = (flip = !flip);
      await runCheck(board, api);
    }

    const statuses = api.calls.filter((call) => call === "status").length;
    const lists = api.calls.filter((call) => call === "alerts").length;
    expect(statuses).toBeLessThanOrEqual(30);
    expect(lists).toBeLessThanOrEqual(15);
  });
});
