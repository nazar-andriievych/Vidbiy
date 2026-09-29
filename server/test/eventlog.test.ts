import { describe, expect, it } from "vitest";
import { EventLog, hourKey, type LogEntry, type LogStore, type SnapshotEntry, type WebhookEntry } from "../src/eventlog";
import { AlertBoard } from "../src/state";
import type { AlertEvent, RegionState } from "../src/types";

const T0 = Date.parse("2026-09-29T06:00:00Z");
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

/** Сховище кошиків у пам'яті + лічильник записів, як у Durable Object. */
function memoryLogStore() {
  const buckets = new Map<string, LogEntry[]>();
  let writes = 0;
  const store: LogStore = {
    readHour: async (hour) => structuredClone(buckets.get(hour)),
    writeHour: async (hour, entries) => {
      writes++;
      buckets.set(hour, structuredClone(entries));
    },
    readFrom: async (fromHour) => [...buckets].filter(([hour]) => hour >= fromHour).sort(([a], [b]) => a.localeCompare(b)),
    deleteBefore: async (hour) => {
      for (const key of [...buckets.keys()]) if (key < hour) buckets.delete(key);
    },
  };
  return { store, buckets, writes: () => writes };
}

const RED = (since: number) => ({ level: "red" as const, since, reason: null });

function event(regionId: string, active: boolean, createdAt: number): AlertEvent {
  return { regionId, alarmType: "AIR", status: active ? "Activate" : "DEACTIVATE", active, levels: active ? [RED(createdAt)] : [], createdAt };
}

function snapshotEntry(asOf: number): SnapshotEntry {
  return { kind: "snapshot", asOf, tookMs: 100, ok: true, active: 0, changes: [], kept: [] };
}

describe("EventLog: зберігання", () => {
  it("події однієї години — в одному кошику, кожна подія — один запис", async () => {
    const { store, buckets, writes } = memoryLogStore();
    const log = new EventLog(store);

    await log.append(snapshotEntry(T0), T0);
    await log.append(snapshotEntry(T0 + MINUTE), T0 + MINUTE);
    await log.append(snapshotEntry(T0 + HOUR), T0 + HOUR);

    expect([...buckets.keys()]).toEqual([hourKey(T0), hourKey(T0 + HOUR)]);
    expect(buckets.get(hourKey(T0))).toHaveLength(2);
    expect(writes()).toBe(3);
  });

  it("після «засинання» дописує в кошик години, а не затирає його", async () => {
    const { store, buckets } = memoryLogStore();
    await new EventLog(store).append(snapshotEntry(T0), T0);

    await new EventLog(store).append(snapshotEntry(T0 + MINUTE), T0 + MINUTE);

    expect(buckets.get(hourKey(T0))!.map((e) => (e as SnapshotEntry).asOf)).toEqual([T0, T0 + MINUTE]);
  });

  it("видаляє кошики, старші за 48 год", async () => {
    const { store, buckets } = memoryLogStore();
    const log = new EventLog(store);
    for (let h = 0; h <= 50; h++) await log.append(snapshotEntry(T0 + h * HOUR), T0 + h * HOUR);

    const hours = [...buckets.keys()].sort();
    expect(hours[0]).toBe(hourKey(T0 + 2 * HOUR));
    expect(hours).toHaveLength(49);
  });

  it("читання за останні години й за регіоном: чужі вебхуки й зміни відкидає, знімки лишає", async () => {
    const { store } = memoryLogStore();
    const board = new AlertBoard(undefined, new EventLog(store));
    await board.loadSnapshot(new Map(), T0 - 2 * HOUR);
    await board.receive(event("75", true, T0), T0 + SECOND);
    await board.receive(event("76", true, T0), T0 + SECOND);
    await board.loadSnapshot(new Map([["75", { active: true, changedAt: T0, levels: [RED(T0)] }]]), T0 + MINUTE);

    const entries = await board.readLog(T0 + MINUTE, 1, "75");

    expect(entries.map((e) => [e.kind, e.kind === "webhook" ? e.region : e.changes?.length])).toEqual([
      ["webhook", "75"],
      // Знімок зняв тривогу в 76, але для 75 нічого не змінив.
      ["snapshot", 0],
    ]);
  });
});

describe("EventLog: що пише AlertBoard", () => {
  it("вебхук: обидва годинники, було → стало, результат", async () => {
    const { store } = memoryLogStore();
    const board = new AlertBoard(undefined, new EventLog(store));
    await board.loadSnapshot(new Map(), T0 - MINUTE);

    await board.receive(event("75", true, T0), T0 + 12 * SECOND);
    await board.receive(event("75", true, T0 - HOUR), T0 + 13 * SECOND); // старіша подія
    await board.receive({ ...event("75", true, T0), alarmType: "ARTILLERY" }, T0 + 14 * SECOND); // не повітряна

    const webhooks = (await board.readLog(T0 + MINUTE, 1)).filter((e): e is WebhookEntry => e.kind === "webhook");
    expect(webhooks).toEqual([
      { kind: "webhook", receivedAt: T0 + 12 * SECOND, createdAt: T0, region: "75", from: "none", to: "red", levels: [["red", T0]], outcome: "changed" },
      { kind: "webhook", receivedAt: T0 + 13 * SECOND, createdAt: T0 - HOUR, region: "75", from: "red", to: "red", levels: [["red", T0 - HOUR]], outcome: "out_of_order" },
    ]);
  });

  it("знімок: зміни з сирим lastUpdate, тривалість і регіони, де лишився новіший вебхук", async () => {
    const { store } = memoryLogStore();
    const board = new AlertBoard(undefined, new EventLog(store));
    await board.loadSnapshot(new Map([["16", { active: true, changedAt: T0 - HOUR, levels: [RED(T0 - HOUR)] }]]), T0 - MINUTE);
    // Вебхук новіший за наступний знімок: той його ще не бачить.
    await board.receive(event("75", true, T0 + 5 * SECOND), T0 + 6 * SECOND);

    const snapshot = new Map<string, RegionState>([["29", { active: true, changedAt: T0 - 30 * SECOND, levels: [RED(T0 - 30 * SECOND)] }]]);
    await board.loadSnapshot(snapshot, T0, 420);

    const last = (await board.readLog(T0, 1)).at(-1) as SnapshotEntry;
    expect(last).toEqual({
      kind: "snapshot",
      asOf: T0,
      tookMs: 420,
      ok: true,
      active: 1,
      changes: [
        { region: "16", from: "red", to: "none", lastUpdate: null, levels: [] },
        { region: "29", from: "none", to: "red", lastUpdate: T0 - 30 * SECOND, levels: [["red", T0 - 30 * SECOND]] },
      ],
      kept: ["75"],
    });
  });

  it("невдалий знімок — лише запис у журналі", async () => {
    const board = new AlertBoard(undefined, new EventLog(memoryLogStore().store));

    await board.snapshotFailed(T0, 8000, 503, "unavailable");

    expect(await board.readLog(T0, 1)).toEqual([
      { kind: "snapshot", asOf: T0, tookMs: 8000, ok: false, status: 503, detail: "unavailable" },
    ]);
  });

  it("зламане сховище журналу не ламає обробку тривог", async () => {
    const broken: LogStore = {
      readHour: async () => {
        throw new Error("rows written limit");
      },
      writeHour: async () => {},
      readFrom: async () => [],
      deleteBefore: async () => {},
    };
    const board = new AlertBoard(undefined, new EventLog(broken));
    await board.loadSnapshot(new Map(), T0);

    expect(await board.receive(event("75", true, T0 + SECOND), T0 + 2 * SECOND)).toBe("changed");
    expect(board.response(T0 + 2 * SECOND).alerts?.map((a) => a.region)).toEqual(["75"]);
  });
});
