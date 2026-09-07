package ru.egor.meters

/**
 * Three-way merge used only by the remaining legacy whole-dataset editors.
 *
 * The important rule is fail-closed: if both the stale editor and the live Room state changed
 * the same value (or the stale editor tries to delete an entity that changed meanwhile), the
 * operation is rejected instead of silently overwriting newer data.
 */
internal object DatasetMerge {
    fun merge(base: List<Address>, updated: List<Address>, current: List<Address>): List<Address> {
        val baseById = base.associateBy { it.id }
        val updatedById = updated.associateBy { it.id }
        val currentById = current.associateBy { it.id }
        val removedIds = baseById.keys - updatedById.keys

        removedIds.forEach { id ->
            val before = baseById.getValue(id)
            val live = currentById[id]
            require(live == null || live == before) {
                "Адрес изменился в другом экране. Обновите данные и повторите удаление."
            }
        }

        val result = current.filterNot { it.id in removedIds }.toMutableList()
        updated.forEach { changed ->
            val before = baseById[changed.id]
            val liveIndex = result.indexOfFirst { it.id == changed.id }
            if (before == null) {
                require(liveIndex < 0 || result[liveIndex] == changed) {
                    "Адрес с таким идентификатором уже изменён. Обновите данные."
                }
                if (liveIndex < 0) result += changed
            } else if (liveIndex < 0) {
                require(changed == before) {
                    "Адрес был удалён в другом экране. Обновите данные."
                }
            } else {
                result[liveIndex] = mergeAddress(before, changed, result[liveIndex])
            }
        }
        return result
    }

    private fun mergeAddress(base: Address, updated: Address, current: Address): Address {
        val baseMeters = base.meters.associateBy { it.id }
        val updatedMeters = updated.meters.associateBy { it.id }
        val currentMeters = current.meters.associateBy { it.id }
        val removedIds = baseMeters.keys - updatedMeters.keys

        removedIds.forEach { id ->
            val before = baseMeters.getValue(id)
            val live = currentMeters[id]
            require(live == null || live == before) {
                "Счётчик изменился в другом экране. Обновите данные и повторите удаление."
            }
        }

        val meters = current.meters.filterNot { it.id in removedIds }.toMutableList()
        updated.meters.forEach { changed ->
            val before = baseMeters[changed.id]
            val liveIndex = meters.indexOfFirst { it.id == changed.id }
            if (before == null) {
                require(liveIndex < 0 || meters[liveIndex] == changed) {
                    "Счётчик уже изменён в другом экране. Обновите данные."
                }
                if (liveIndex < 0) meters += changed
            } else if (liveIndex < 0) {
                require(changed == before) {
                    "Счётчик был удалён в другом экране. Обновите данные."
                }
            } else {
                meters[liveIndex] = mergeMeter(before, changed, meters[liveIndex])
            }
        }

        return current.copy(
            name = pick(base.name, updated.name, current.name),
            meters = meters,
            account = pick(base.account, updated.account, current.account),
            recipient = pick(base.recipient, updated.recipient, current.recipient)
        )
    }

    private fun mergeMeter(base: Meter, updated: Meter, current: Meter): Meter {
        val baseReadings = base.readings.associateBy { it.id }
        val updatedReadings = updated.readings.associateBy { it.id }
        val currentReadings = current.readings.associateBy { it.id }
        val removedIds = baseReadings.keys - updatedReadings.keys

        removedIds.forEach { id ->
            val before = baseReadings.getValue(id)
            val live = currentReadings[id]
            require(live == null || live == before) {
                "Показание изменилось в другом экране. Обновите данные и повторите удаление."
            }
        }

        val readings = current.readings.filterNot { it.id in removedIds }.toMutableList()
        updated.readings.forEach { changed ->
            val before = baseReadings[changed.id]
            val liveIndex = readings.indexOfFirst { it.id == changed.id }
            if (before == null) {
                require(liveIndex < 0 || readings[liveIndex] == changed) {
                    "Показание уже изменено в другом экране. Обновите данные."
                }
                if (liveIndex < 0) readings += changed
            } else if (liveIndex < 0) {
                require(changed == before) {
                    "Показание было удалено в другом экране. Обновите данные."
                }
            } else if (changed != before) {
                readings[liveIndex] = mergeReading(before, changed, readings[liveIndex])
            }
        }

        return current.copy(
            name = pick(base.name, updated.name, current.name),
            unit = pick(base.unit, updated.unit, current.unit),
            kind = pick(base.kind, updated.kind, current.kind),
            serial = pick(base.serial, updated.serial, current.serial),
            readings = readings,
            integerDigits = pick(base.integerDigits, updated.integerDigits, current.integerDigits),
            fractionDigits = pick(base.fractionDigits, updated.fractionDigits, current.fractionDigits),
            previousMeterId = pick(base.previousMeterId, updated.previousMeterId, current.previousMeterId),
            installedAt = pick(base.installedAt, updated.installedAt, current.installedAt),
            verificationUntil = pick(base.verificationUntil, updated.verificationUntil, current.verificationUntil),
            status = pick(base.status, updated.status, current.status),
            account = pick(base.account, updated.account, current.account),
            recipient = pick(base.recipient, updated.recipient, current.recipient),
            tariffZones = pick(base.tariffZones, updated.tariffZones, current.tariffZones),
            tariffSchedule = pick(base.tariffSchedule, updated.tariffSchedule, current.tariffSchedule),
            location = pick(base.location, updated.location, current.location),
            meteringPointId = pick(base.meteringPointId, updated.meteringPointId, current.meteringPointId)
        )
    }

    private fun mergeReading(base: Reading, updated: Reading, current: Reading): Reading {
        val updatedZonesChanged = updated.zoneValues != base.zoneValues
        val currentZonesChanged = current.zoneValues != base.zoneValues
        if (updatedZonesChanged && currentZonesChanged) {
            require(updated.zoneValues == current.zoneValues) {
                "Показание уже изменено в другом экране. Обновите данные."
            }
        }

        val legacySingleValueChanged = updated.valueText != base.valueText &&
            (base.zoneValues.isEmpty() || base.zoneValues.keys == setOf("TOTAL"))
        val mergedZones = when {
            updatedZonesChanged -> updated.zoneValues
            legacySingleValueChanged && !updated.valueText.isNullOrBlank() -> mapOf("TOTAL" to updated.valueText.orEmpty())
            else -> current.zoneValues
        }
        val mergedPrimary = mergedZones["TOTAL"] ?: mergedZones["T1"] ?: current.valueText ?: updated.valueText

        return current.copy(
            value = mergedPrimary?.toDoubleOrNull() ?: pick(base.value, updated.value, current.value),
            timestamp = pick(base.timestamp, updated.timestamp, current.timestamp),
            photoUri = pick(base.photoUri, updated.photoUri, current.photoUri),
            note = pick(base.note, updated.note, current.note),
            valueText = mergedPrimary,
            rollover = pick(base.rollover, updated.rollover, current.rollover),
            zoneValues = mergedZones,
            billingPeriod = pick(base.billingPeriod, updated.billingPeriod, current.billingPeriod)
        )
    }

    private fun <T> pick(base: T, updated: T, current: T): T {
        val userChanged = updated != base
        val liveChanged = current != base
        require(!(userChanged && liveChanged && updated != current)) {
            "Данные изменились в другом экране. Обновите их и повторите действие."
        }
        return if (userChanged) updated else current
    }
}
