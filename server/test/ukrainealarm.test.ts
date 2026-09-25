import { beforeAll, describe, expect, it } from "vitest";
import {
  fetchSnapshot,
  fetchStatus,
  importPublicKey,
  indexToTime,
  parseSnapshot,
  parseWebhook,
  toAppRegionId,
  verifyWebhook,
  WEBHOOK_PUBLIC_KEY_PEM,
} from "../src/ukrainealarm";

const NOW = 1_790_000_000; // секунди

/**
 * Приватного ключа ukrainealarm у нас немає й бути не може, тож для тестів генеруємо
 * власну пару й підписуємо так само, як вони: RSA-SHA256 над `{timestamp}.{тіло}`.
 */
let keys: CryptoKeyPair;

beforeAll(async () => {
  keys = (await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
});

async function signedHeaders(body: string, timestamp = NOW, extra: Record<string, string> = {}) {
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    keys.privateKey,
    new TextEncoder().encode(`${timestamp}.${body}`),
  );
  return new Headers({
    "X-Webhook-Signature": btoa(String.fromCharCode(...new Uint8Array(signature))),
    "X-Webhook-Timestamp": String(timestamp),
    "X-Webhook-Key-Id": "test",
    "X-Webhook-Signature-Alg": "rsa-sha256",
    ...extra,
  });
}

const BODY = JSON.stringify({
  status: "DEACTIVATE",
  regionId: 14,
  alarmType: "AIR",
  createdAt: "2026-09-07T12:32:00Z",
  alertLevel: "Yellow",
  reason: "UAV activity",
  activeAlertLevels: [],
});

describe("verifyWebhook", () => {
  it("приймає справжній підпис", async () => {
    const result = await verifyWebhook(await signedHeaders(BODY), BODY, keys.publicKey, NOW);

    expect(result).toEqual({ ok: true });
  });

  it("відкидає змінене тіло: навіть інше форматування того самого JSON", async () => {
    const headers = await signedHeaders(BODY);
    const reformatted = JSON.stringify(JSON.parse(BODY), null, 2);

    expect(await verifyWebhook(headers, reformatted, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "bad_signature",
    });
  });

  it("відкидає підпис чужим ключем", async () => {
    const theirs = await importPublicKey(WEBHOOK_PUBLIC_KEY_PEM);

    const result = await verifyWebhook(await signedHeaders(BODY), BODY, theirs, NOW);

    expect(result).toEqual({ ok: false, reason: "bad_signature" });
  });

  it("відкидає повідомлення, старіше за 5 хв: так не програти старий вебхук удруге", async () => {
    const headers = await signedHeaders(BODY, NOW - 301);

    expect(await verifyWebhook(headers, BODY, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "stale_timestamp",
    });
  });

  it("не можна просто підставити свіжий timestamp: він теж підписаний", async () => {
    const headers = await signedHeaders(BODY, NOW - 3600);
    headers.set("X-Webhook-Timestamp", String(NOW));

    expect(await verifyWebhook(headers, BODY, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "bad_signature",
    });
  });

  it("відкидає запит без заголовків підпису", async () => {
    expect(await verifyWebhook(new Headers(), BODY, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "missing_headers",
    });
  });

  it("відкидає незнайомий алгоритм", async () => {
    const headers = await signedHeaders(BODY, NOW, { "X-Webhook-Signature-Alg": "hmac-sha256" });

    expect(await verifyWebhook(headers, BODY, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "bad_algorithm",
    });
  });

  it("не падає на сміттєвому base64", async () => {
    const headers = await signedHeaders(BODY);
    headers.set("X-Webhook-Signature", "%%not-base64%%");

    expect(await verifyWebhook(headers, BODY, keys.publicKey, NOW)).toEqual({
      ok: false,
      reason: "bad_signature",
    });
  });

  it("публічний ключ ukrainealarm імпортується", async () => {
    await expect(importPublicKey(WEBHOOK_PUBLIC_KEY_PEM)).resolves.toBeDefined();
  });
});

describe("parseWebhook", () => {
  it("визначає тривогу за activeAlertLevels, а не за status", () => {
    // Приклад із документації: знято одну загрозу, але інша лишилася — тривога триває.
    const event = parseWebhook({
      status: "DEACTIVATE",
      regionId: 14,
      alarmType: "AIR",
      createdAt: "2026-09-07T12:18:00Z",
      activeAlertLevels: [{ alertLevel: "Yellow", reason: "UAV activity" }],
    });

    expect(event).toEqual({
      regionId: "14",
      alarmType: "AIR",
      status: "DEACTIVATE",
      active: true,
      createdAt: Date.parse("2026-09-07T12:18:00Z"),
    });
  });

  it("порожній масив загроз — тривоги немає", () => {
    expect(parseWebhook(JSON.parse(BODY))?.active).toBe(false);
  });

  it("переводить Крим у нумерацію застосунку", () => {
    expect(parseWebhook({ ...JSON.parse(BODY), regionId: 9999 })?.regionId).toBe("29");
  });

  it("тестовий регіон лишається без ID, але подія все одно розібрана", () => {
    expect(parseWebhook({ ...JSON.parse(BODY), regionId: 0 })?.regionId).toBeNull();
  });

  it("повертає null, якщо тіло не схоже на подію", () => {
    expect(parseWebhook(null)).toBeNull();
    expect(parseWebhook({ regionId: 14 })).toBeNull();
    expect(parseWebhook({ ...JSON.parse(BODY), createdAt: "вчора" })).toBeNull();
    expect(parseWebhook({ ...JSON.parse(BODY), activeAlertLevels: "none" })).toBeNull();
  });
});

describe("toAppRegionId", () => {
  it("лишає звичайні ID як є", () => {
    expect(toAppRegionId(16)).toBe("16");
    expect(toAppRegionId("1293")).toBe("1293");
  });

  it("ігнорує порожнє й дивне", () => {
    expect(toAppRegionId(undefined)).toBeNull();
    expect(toAppRegionId("")).toBeNull();
    expect(toAppRegionId({})).toBeNull();
  });
});

describe("parseSnapshot", () => {
  const snapshot = [
    {
      regionId: "16",
      regionType: "State",
      lastUpdate: "2022-04-04T16:45:00Z",
      activeAlerts: [
        { regionId: "16", type: "AIR", lastUpdate: "2022-04-04T16:45:00Z", activeAlertLevels: [] },
      ],
    },
    {
      regionId: "124",
      regionType: "District",
      lastUpdate: "2026-09-24T18:00:00Z",
      activeAlerts: [
        { regionId: "124", type: "ARTILLERY", lastUpdate: "2026-09-24T18:00:00Z" },
      ],
    },
    {
      regionId: "9999",
      regionType: "State",
      lastUpdate: "2026-09-24T17:00:00Z",
      activeAlerts: [{ regionId: "9999", type: "AIR", lastUpdate: "2026-09-24T17:00:00Z" }],
    },
    { regionId: "0", activeAlerts: [{ regionId: "0", type: "AIR" }] },
    "сміття",
  ];

  it("бере лише повітряні тривоги й перекладає ID", () => {
    const regions = parseSnapshot(snapshot);

    expect([...regions!.keys()]).toEqual(["16", "29"]);
    expect(regions!.get("16")).toEqual({ active: true, changedAt: Date.parse("2022-04-04T16:45:00Z") });
  });

  it("повертає null, якщо відповідь не масив", () => {
    expect(parseSnapshot({ alerts: [] })).toBeNull();
  });
});

describe("fetchSnapshot", () => {
  it("надсилає ключ як є, без Bearer", async () => {
    let seen: Headers | undefined;
    const result = await fetchSnapshot("secret", async (_url, init) => {
      seen = new Headers(init?.headers);
      return new Response("[]");
    });

    expect(seen?.get("authorization")).toBe("secret");
    expect(result).toEqual({ ok: true, regions: new Map() });
  });

  it("повертає статус і початок тіла, коли апстрім відмовив", async () => {
    const result = await fetchSnapshot("secret", async () => new Response("nope", { status: 401 }));

    expect(result).toEqual({ ok: false, status: 401, detail: "nope" });
  });

  it("мережеву помилку перетворює на status: null", async () => {
    const result = await fetchSnapshot("secret", async () => {
      throw new Error("offline");
    });

    expect(result).toEqual({ ok: false, status: null });
  });
});

describe("indexToTime", () => {
  it("розшифровує номер зміни як .NET ticks за UTC", () => {
    // Номер, отриманий о 23:21:01, — зміна о 23:20:58.
    expect(new Date(indexToTime("639258888587901446")!).toISOString()).toBe("2026-09-24T23:20:58.790Z");
  });

  it("на незнайомий формат повертає null, а не вигадану дату", () => {
    expect(indexToTime("42")).toBeNull();
    expect(indexToTime("fake")).toBeNull();
    expect(indexToTime("99999999999999999999")).toBeNull();
  });
});

describe("fetchStatus", () => {
  it("не спотворює номер, більший за 2^53", async () => {
    const result = await fetchStatus(
      "secret",
      async () => new Response('{"lastActionIndex":639258728888077549}'),
    );

    // JSON.parse дав би 639258728888077600 — і ми щоразу бачили б «зміну».
    expect(result).toEqual({ ok: true, index: "639258728888077549" });
  });

  it("незрозуміла відповідь — невдача, а не порожній номер", async () => {
    const result = await fetchStatus("secret", async () => new Response("{}"));

    expect(result).toEqual({ ok: false, status: 200, detail: "malformed" });
  });
});
