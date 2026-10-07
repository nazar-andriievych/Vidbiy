import { describe, expect, it } from "vitest";
import known from "../../docs/fixtures/alerts-v1.json";
import unknown from "../../docs/fixtures/alerts-v1-unknown.json";
import { AlertBoard } from "../src/state";
import type { AlertEvent } from "../src/types";
import { withUpdate } from "../src/update";

/**
 * Контракт `/v1/alerts` (docs/proxy-api.md) на прикладі: ту саму відповідь розбирає тест
 * застосунку (AlertsContractTest.kt). Якщо сервер змінить формат, впаде цей тест; якщо
 * застосунок перестане його розуміти — той. Файл змінювати лише разом з обома.
 */
const T0 = Date.parse("2026-10-06T07:00:00Z");
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

describe("контракт /v1/alerts", () => {
  it("відома картина: регіони за номером, червоний першим, причина або null", async () => {
    const board = new AlertBoard();
    await board.restore();
    await board.loadSnapshot(new Map([["124", { active: true, changedAt: T0 - MINUTE }]]), T0);
    const event: AlertEvent = {
      regionId: "14",
      alarmType: "AIR",
      status: "Activate",
      active: true,
      levels: [
        { level: "yellow", since: T0 - HOUR, reason: "Дронова загроза (жовтий рівень)" },
        { level: "red", since: T0 + MINUTE, reason: null },
      ],
      createdAt: T0 + MINUTE,
    };
    await board.receive(event, T0 + MINUTE);

    const release = { LATEST_VERSION_CODE: "3", LATEST_VERSION_NAME: "1.2", MIN_VERSION_CODE: "2", UPDATE_URL: "https://example.org/vidbiy" };
    expect(withUpdate(board.response(T0 + MINUTE + 5 * SECOND), release)).toEqual(known);
  });

  it("невідомий стан, випуск не налаштовано", async () => {
    expect(withUpdate(new AlertBoard().response(T0), {})).toEqual(unknown);
  });
});
