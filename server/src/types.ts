/**
 * Відповідь `/v1/alerts`. Контракт описаний у `docs/proxy-api.md`.
 *
 * `alerts: []` — ми на зв'язку з ukrainealarm, тривог немає.
 * `alerts: null` — стан ще ні разу не завантажувався; застосунок дзвонить за fail-safe.
 */
export interface AlertsResponse {
  v: 1;
  /** Регіони (у нумерації довідника застосунку) з активною повітряною тривогою. */
  alerts: RegionAlert[] | null;
  /** Коли стан востаннє підтвердився (правило свіжості — FR-30). */
  confirmed_at: string | null;
  /** Скільки секунд минуло від `confirmed_at` на момент відповіді. */
  age_seconds: number | null;
}

export interface RegionAlert {
  region: string;
  /** Щонайменше один рівень. Червоний і жовтий можуть діяти одночасно. */
  levels: AlertLevelOut[];
}

export interface AlertLevelOut {
  level: Level;
  /** Коли цей рівень оголосили (ISO). Потрібен застосунку для правила 24 год. */
  since: string;
  /** Текст від ukrainealarm, лише для показу. Буває порожнім. */
  reason: string | null;
}

/** Жовтий — дронова загроза, червоний — ракетна. Невідомий рівень вважаємо червоним. */
export type Level = "red" | "yellow";

export interface StoredLevel {
  level: Level;
  /** Час оголошення, мс. */
  since: number;
  reason: string | null;
}

/** Повітряна тривога в одному регіоні — лише те, що потрібно для рішення. */
export interface RegionState {
  active: boolean;
  /** Час зміни за годинником ukrainealarm, мс. Упорядковує події одного регіону. */
  changedAt: number;
  /**
   * Активні рівні. Може бути відсутнім у стані, збереженому до контракту v1:
   * тоді активна тривога вважається червоною від `changedAt`.
   */
  levels?: StoredLevel[];
}

/** Одна подія вебхука, вже розібрана й зведена до нашої нумерації регіонів. */
export interface AlertEvent {
  /** `null` — регіон, який ми свідомо не відстежуємо (тестовий регіон ukrainealarm). */
  regionId: string | null;
  alarmType: string;
  /** `Activate` / `DEACTIVATE`. Лише для журналу: стан визначає `levels`. */
  status: string;
  /** Чи лишилася в регіоні хоч одна загроза цього типу після події. */
  active: boolean;
  /** Повний стан рівнів регіону після події. Порожній — тривоги немає. */
  levels: StoredLevel[];
  createdAt: number;
}
