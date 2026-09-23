package ua.vidbiy.app.alarm

import ua.vidbiy.app.data.SelectedRegion

/**
 * Чи накриває хоч одна з активних тривог обраний регіон.
 *
 * [activeAlertUids] — UID регіонів із активними повітряними тривогами (що їх віддає проксі).
 * Тривога рахується лише тоді, коли оголошена на території, яка містить обраний регіон:
 * у самій громаді, у її районі або в її області. Сусідні громади — не наш випадок.
 */
fun SelectedRegion.isUnderAlert(activeAlertUids: Collection<String>): Boolean =
    activeAlertUids.any { it in coveringUids }
