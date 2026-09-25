/**
 * Відповідь `/v2/alerts`. Контракт описаний у `docs/proxy-api.md`.
 *
 * `active: []` — ми на зв'язку з ukrainealarm, тривог немає.
 * `active: null` — стан ще ні разу не завантажувався; застосунок дзвонить за fail-safe.
 */
export interface AlertsResponse {
  v: 2;
  /** ID регіонів (у нумерації довідника застосунку) з активною повітряною тривогою. */
  active: string[] | null;
  /** Коли стан востаннє підтвердився: перевірений вебхук, перевірка на тиші або знімок. */
  heard_at: string | null;
  /** Скільки секунд минуло від `heard_at` на момент відповіді. */
  age_seconds: number | null;
}

/** Повітряна тривога в одному регіоні — лише те, що потрібно для рішення. */
export interface RegionState {
  active: boolean;
  /** Час зміни за годинником ukrainealarm, мс. Упорядковує події одного регіону. */
  changedAt: number;
}

/** Одна подія вебхука, вже розібрана й зведена до нашої нумерації регіонів. */
export interface AlertEvent {
  /** `null` — регіон, який ми свідомо не відстежуємо (тестовий регіон ukrainealarm). */
  regionId: string | null;
  alarmType: string;
  /** `Activate` / `DEACTIVATE`. Лише для журналу: стан визначає `active`. */
  status: string;
  /** Чи лишилася в регіоні хоч одна загроза цього типу після події. */
  active: boolean;
  createdAt: number;
}
