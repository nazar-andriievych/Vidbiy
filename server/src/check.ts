import { RELOAD_BACKOFF_MS, type AlertBoard } from "./state";
import { indexToTime, type ApiFailure, type SnapshotResult, type StatusResult } from "./ukrainealarm";

/** Після невдалої чи відкладеної перевірки наступна — не раніше ніж за хвилину. */
export const RETRY_AFTER_FAILURE_MS = 60_000;

export interface CheckApi {
  status(): Promise<StatusResult | ApiFailure>;
  snapshot(): Promise<SnapshotResult | ApiFailure>;
  now(): number;
}

export type CheckOutcome =
  /** Ще не час: нещодавно було підтвердження або попередня спроба провалилася. */
  | "skipped"
  /** Стан підтверджено без повного списку: номер той самий або всі зміни ми бачили у вебхуках. */
  | "confirmed"
  /** Завантажили повний список: вперше, для звірки або тому, що щось пропустили. */
  | "reloaded"
  /** Щось пропустили, але повний список поки не можна: обмежувач `/alerts`. */
  | "deferred"
  | "failed";

/**
 * Одна перевірка стану через API — на старті, на тиші або для звірки.
 *
 * Спершу найдешевший запит: номер останньої зміни. Стан вважаємо підтвердженим, якщо
 * номер той самий, що минулого разу, або якщо час, зашитий у номер, не пізніший за
 * найсвіжішу подію, яку ми отримали вебхуком: тоді ми бачили все. Повний список —
 * дорогий запит із власним обмежувачем — беремо, лише коли щось пропустили, для
 * звірки раз на 10 хв або на старті.
 */
export async function runCheck(board: AlertBoard, api: CheckApi): Promise<CheckOutcome> {
  if (!board.needsCheck(api.now())) return "skipped";

  const status = await api.status();
  if (!status.ok) {
    await board.checkFailed(api.now() + RETRY_AFTER_FAILURE_MS);
    return "failed";
  }

  const how = board.covers(status.index, indexToTime(status.index));
  if (how !== null) {
    await board.confirm(api.now(), status.index, how);
    // Звірка — страховка від загубленого вебхука; свіжість уже підтверджена, тож
    // якщо обмежувач `/alerts` не пускає, звірка просто почекає.
    if (!board.auditDue(api.now()) || !board.reloadAllowed(api.now())) return "confirmed";
  } else if (!board.reloadAllowed(api.now())) {
    // Свіжість не оновлюємо: якщо так триватиме, застосунок задзвонить за fail-safe.
    await board.deferReload(api.now() + RETRY_AFTER_FAILURE_MS);
    return "deferred";
  }

  // Час — до запиту: вебхук, що прийде, поки ми чекаємо, може бути новішим
  // за знімок, і тоді він має перемогти.
  const asOf = api.now();
  const snapshot = await api.snapshot();
  if (!snapshot.ok) {
    await board.checkFailed(api.now() + RETRY_AFTER_FAILURE_MS, api.now() + RELOAD_BACKOFF_MS);
    return how !== null ? "confirmed" : "failed";
  }

  await board.loadSnapshot(snapshot.regions, asOf, status.index, indexToTime(status.index));
  return "reloaded";
}
