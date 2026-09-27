import { describe, expect, it } from "vitest";
import { labAccess, upstreamPaused } from "../src/lab";
import { fetchRaw } from "../src/ukrainealarm";

describe("labAccess", () => {
  it("без секрету LAB_KEY маршруту не існує", () => {
    expect(labAccess(undefined, "anything", "alerts")).toEqual({ ok: false, status: 404, error: "not_found" });
    expect(labAccess("", "", "alerts")).toMatchObject({ status: 404 });
  });

  it("без правильного заголовка — 403", () => {
    expect(labAccess("k3y", null, "alerts")).toMatchObject({ status: 403 });
    expect(labAccess("k3y", "k3", "alerts")).toMatchObject({ status: 403 });
    expect(labAccess("k3y", "k3y!", "alerts")).toMatchObject({ status: 403 });
  });

  it("пускає лише відомі шляхи", () => {
    expect(labAccess("k3y", "k3y", "alerts")).toEqual({ ok: true, path: "alerts" });
    expect(labAccess("k3y", "k3y", "alerts/status")).toEqual({ ok: true, path: "alerts/status" });
    expect(labAccess("k3y", "k3y", "regions")).toMatchObject({ status: 400 });
    expect(labAccess("k3y", "k3y", null)).toMatchObject({ status: 400 });
  });
});

describe("upstreamPaused", () => {
  it("вмикається лише явно", () => {
    expect(upstreamPaused("1")).toBe(true);
    expect(upstreamPaused("true")).toBe(true);
    expect(upstreamPaused(undefined)).toBe(false);
    expect(upstreamPaused("0")).toBe(false);
  });
});

describe("fetchRaw", () => {
  it("віддає код, заголовки й початок тіла відмови", async () => {
    const result = await fetchRaw("alerts", "secret", async () =>
      new Response("limit", { status: 401, headers: { "retry-after": "60" } }),
    );

    expect(result).toMatchObject({ status: 401, bytes: 5, body_head: "limit" });
    expect(result.headers["retry-after"]).toBe("60");
  });

  it("тіло успішної відповіді не повертає", async () => {
    const result = await fetchRaw("alerts", "secret", async () => new Response("[]"));

    expect(result).toMatchObject({ status: 200, bytes: 2 });
    expect(result.body_head).toBeUndefined();
  });

  it("мережеву помилку перетворює на status: null", async () => {
    const result = await fetchRaw("alerts", "secret", async () => {
      throw new Error("offline");
    });

    expect(result).toMatchObject({ status: null, error: "Error: offline" });
  });
});
