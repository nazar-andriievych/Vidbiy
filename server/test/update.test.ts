import { describe, expect, it } from "vitest";
import { appUpdate } from "../src/update";

describe("відомості про випуск", () => {
  it("усе задано — віддаємо як є", () => {
    expect(
      appUpdate({ LATEST_VERSION_CODE: "3", LATEST_VERSION_NAME: "1.2", MIN_VERSION_CODE: "2", UPDATE_URL: "https://vidbiy.example/" }),
    ).toEqual({ latest_version_code: 3, latest_version_name: "1.2", min_version_code: 2, url: "https://vidbiy.example/" });
  });

  it("без останньої версії про оновлення мовчимо", () => {
    expect(appUpdate({})).toBeNull();
    expect(appUpdate({ LATEST_VERSION_CODE: "", MIN_VERSION_CODE: "2" })).toBeNull();
    expect(appUpdate({ LATEST_VERSION_CODE: "1.2" })).toBeNull();
    expect(appUpdate({ LATEST_VERSION_CODE: "0" })).toBeNull();
  });

  it("мінімальна версія не вища за останню: інакше оновитися не було б на що", () => {
    expect(appUpdate({ LATEST_VERSION_CODE: "3", MIN_VERSION_CODE: "9" })?.min_version_code).toBe(3);
  });

  it("крива мінімальна версія — нічого не вимагаємо", () => {
    expect(appUpdate({ LATEST_VERSION_CODE: "3", MIN_VERSION_CODE: "abc" })?.min_version_code).toBe(0);
  });

  it("без назви версії показуємо номер, адреса лише https", () => {
    const update = appUpdate({ LATEST_VERSION_CODE: "3", UPDATE_URL: "http://vidbiy.example/" });
    expect(update?.latest_version_name).toBe("3");
    expect(update?.url).toBeNull();
    expect(appUpdate({ LATEST_VERSION_CODE: "3", UPDATE_URL: "  " })?.url).toBeNull();
  });
});
