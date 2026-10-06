package com.rad.shifttracker.repo

import android.util.Log
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_FOOD_PER_DAY
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_PERCENT
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_TAX_RELIEF
import com.rad.shifttracker.repo.ShiftRepository.Companion.MAX_TRANSPORT_PER_DAY
import com.rad.shifttracker.repo.ShiftRepository.Companion.TAG
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import java.time.LocalDate
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.DEFAULT_MEAL_PRICE_PERIODS
import com.rad.shifttracker.obracun.HourlyValueChange
import com.rad.shifttracker.obracun.KnownPayBaseAnchor
import com.rad.shifttracker.obracun.MealPricePeriod
import com.rad.shifttracker.obracun.MonthlyNetPayBreakdown
import com.rad.shifttracker.smene.MonthKey

var ShiftRepository.payCoefficientValue: Double
    get() = synchronized(lock) { payState.payCoefficient }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value <= 0.0) {
                Log.w(TAG, "Odbijena nevažeća vrednost koeficijenta: $value")
                return
            }
            paySettingsUpdater.setPayCoefficient(value.coerceAtMost(MAX_BASE_PAY))
        }
        notifyChange()
    }

fun ShiftRepository.getHourlyValueForMonth(year: Int, month: Int): Double = synchronized(lock) {
    hourlyValueUnsafe(year, month)
}

fun ShiftRepository.getGrossHourlyRateForMonth(year: Int, month: Int): Double = synchronized(lock) {
    grossHourlyRateUnsafe(year, month)
}

fun ShiftRepository.setHourlyValueChange(year: Int, month: Int, value: Double) {
    if (year <= 0 || month !in 1..12 || !value.isFinite() || value <= 0.0) return
    synchronized(lock) {
        val key = MonthKey(year, month)
        paySettingsUpdater.setHourlyValueChange(key, value.coerceAtMost(MAX_BASE_PAY))
        deletedHourlyValueTombstones.remove(key)
    }
    notifyChange()
}

fun ShiftRepository.removeHourlyValueChange(year: Int, month: Int) {
    if (year <= 0 || month !in 1..12) return
    val removed = synchronized(lock) {
        val key = MonthKey(year, month)
        paySettingsUpdater.removeHourlyValueChange(key).also { wasRemoved ->
            if (wasRemoved) deletedHourlyValueTombstones.add(key)
        }
    }
    if (removed) notifyChange()
}

fun ShiftRepository.getHourlyValueChanges(): List<HourlyValueChange> = synchronized(lock) {
    payState.hourlyValueChanges
        .toSortedMap()
        .map { (key, value) -> HourlyValueChange(key.year, key.month, value) }
}

internal fun ShiftRepository.grossHourlyRateUnsafe(year: Int, month: Int): Double {
    return payQueries.grossHourlyRateUnsafe(year, month)
}

internal fun ShiftRepository.hourlyValueUnsafe(year: Int, month: Int): Double {
    return payQueries.hourlyValueUnsafe(year, month)
}

internal fun ShiftRepository.withHourlyValueUnsafe(
    year: Int,
    month: Int,
    breakdown: MonthlyNetPayBreakdown
): MonthlyNetPayBreakdown {
    return payQueries.withHourlyValueUnsafe(year, month, breakdown)
}

// Legacy-compatible accessor: source of truth is month-based pay coefficient + hourly value.
// Kept for legacy callers that expect a single base-gross pay value.
var ShiftRepository.grossHourlyRateValue: Double
    get() {
        val today = LocalDate.now(clock)
        return getGrossHourlyRateForMonth(today.year, today.monthValue)
    }
    set(value) {
        val today = LocalDate.now(clock)
        val hourlyValue = getHourlyValueForMonth(today.year, today.monthValue)
        payCoefficientValue = if (hourlyValue > 0.0) {
            value / hourlyValue
        } else {
            config.pay.defaultCoefficient
        }
    }

// Legacy-compatible alias kept only as a compatibility shim for callers still using basePayValue.
var ShiftRepository.basePayValue: Double
    get() = grossHourlyRateValue
    set(value) {
        grossHourlyRateValue = value
    }

var ShiftRepository.sickPayBaseHourlyValue: Double
    get() = synchronized(lock) { payState.sickPayBaseHourly }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value < 0.0) {
                Log.w(TAG, "Odbijena nevažeća osnovica bolovanja: $value")
                return
            }
            paySettingsUpdater.setSickPayBaseHourly(value.coerceAtMost(MAX_BASE_PAY))
        }
        notifyChange()
    }

var ShiftRepository.defaultPayBaseSelectionModeValue: PayBaseSelectionMode
    get() = synchronized(lock) { payState.defaultPayBaseSelectionMode }
    set(value) {
        val currentManualBase = synchronized(lock) { payState.sickPayBaseHourly }
        setDefaultPayBaseSelection(value, currentManualBase)
    }

fun ShiftRepository.setDefaultPayBaseSelection(
    selectionMode: PayBaseSelectionMode,
    manualHourlyBase: Double? = null
) {
    val globalMode = selectionMode.toGlobalPayBaseSelectionMode()
    synchronized(lock) {
        val previousMonthlyBaseKeys = payState.monthlyPayBases.keys.toList()
        if (globalMode == PayBaseSelectionMode.MANUAL) {
            val base = manualHourlyBase
            if (base == null || !base.isFinite() || base <= 0.0) {
                Log.w(TAG, "Odbijena nevažeća globalna osnovica naknada: $manualHourlyBase")
                return
            }
            payState.sickPayBaseHourly = base.coerceAtMost(MAX_BASE_PAY)
        }
        if (globalMode == PayBaseSelectionMode.KNOWN_BASE && payState.knownPayBaseAnchor == null) {
            Log.w(TAG, "Odbijen režim poznate osnovice bez sidra.")
            return
        }
        payState.defaultPayBaseSelectionMode = globalMode
        payState.monthlyPayBases.clear()
        payState.invalidateSummariesAndRolling()
        payState.dirtyMonths.addAll(recordStore.getAllMonthsSorted())
        payState.dirtyMonths.addAll(previousMonthlyBaseKeys)
    }
    notifyChange()
}

fun ShiftRepository.setKnownPayBaseAnchor(year: Int, month: Int, grossHourlyBase: Double) {
    if (year <= 0 || month !in 1..12 || !grossHourlyBase.isFinite() || grossHourlyBase <= 0.0) return
    synchronized(lock) {
        paySettingsUpdater.setKnownPayBaseAnchor(
            KnownPayBaseAnchor(
                month = MonthKey(year, month),
                grossHourlyBase = grossHourlyBase.coerceAtMost(MAX_BASE_PAY)
            )
        )
    }
    notifyChange()
}

/** Najnovije uneto sidro; sva sidra daje [getKnownPayBaseAnchors]. */
fun ShiftRepository.getKnownPayBaseAnchor(): KnownPayBaseAnchor? = synchronized(lock) {
    payState.knownPayBaseAnchor
}

fun ShiftRepository.getKnownPayBaseAnchors(): List<KnownPayBaseAnchor> = synchronized(lock) {
    payState.knownPayBaseAnchors.map { (month, base) -> KnownPayBaseAnchor(month, base) }
}

/** Sidro po kome se računa osnovica radnog meseca (najnovije koje nije posle njega, inače prvo kasnije). */
fun ShiftRepository.getKnownPayBaseAnchorForWorkMonth(year: Int, month: Int): KnownPayBaseAnchor? = synchronized(lock) {
    payState.knownPayBaseAnchorFor(MonthKey(year, month))
}

fun ShiftRepository.removeKnownPayBaseAnchor(year: Int, month: Int) {
    val removed = synchronized(lock) {
        val removed = paySettingsUpdater.removeKnownPayBaseAnchor(MonthKey(year, month))
        if (
            removed &&
            payState.knownPayBaseAnchors.isEmpty() &&
            payState.defaultPayBaseSelectionMode == PayBaseSelectionMode.KNOWN_BASE
        ) {
            payState.defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
        }
        removed
    }
    if (removed) notifyChange()
}

var ShiftRepository.employeeContributionsPercentValue: Double
    get() = synchronized(lock) { payState.employeeContributionsPercent }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value < 0.0) {
                Log.w(TAG, "Odbijena nevažeća vrednost doprinosa: $value")
                return
            }
            paySettingsUpdater.setEmployeeContributionsPercent(value.coerceAtMost(MAX_PERCENT))
        }
        notifyChange()
    }

var ShiftRepository.taxReliefValue: Double
    get() = synchronized(lock) { payState.taxRelief }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value < 0.0) {
                Log.w(TAG, "Odbijena nevažeća poreska olakšica: $value")
                return
            }
            paySettingsUpdater.setTaxRelief(value.coerceAtMost(MAX_TAX_RELIEF))
        }
        notifyChange()
    }

var ShiftRepository.taxPercentValue: Double
    get() = synchronized(lock) { payState.taxPercent }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value < 0.0) {
                Log.w(TAG, "Odbijena nevažeća vrednost poreza: $value")
                return
            }
            paySettingsUpdater.setTaxPercent(value.coerceAtMost(MAX_PERCENT))
        }
        notifyChange()
    }

var ShiftRepository.difficultWorkConditionsPercentValue: Double
    get() = synchronized(lock) { payState.difficultWorkConditionsPercent }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite() || value < 0.0) {
                Log.w(TAG, "Odbijena nevažeća vrednost otežanih uslova rada: $value")
                return
            }
            paySettingsUpdater.setDifficultWorkConditionsPercent(value.coerceAtMost(MAX_PERCENT))
        }
        notifyChange()
    }

var ShiftRepository.foodPerDayValue: Double
    get() = synchronized(lock) { payState.foodPerDay }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite()) {
                Log.w(TAG, "Odbijena nevažeća payState.foodPerDay vrednost: $value")
                return
            }
            if (value < 0.0) {
                Log.w(TAG, "Odbijena negativna payState.foodPerDay vrednost: $value")
                return
            }
            paySettingsUpdater.setFoodPerDay(value.coerceAtMost(MAX_FOOD_PER_DAY))
        }
        notifyChange()
    }

fun ShiftRepository.getFoodPerDayForMonth(year: Int, month: Int): Double = synchronized(lock) {
    resolveFoodPerDayForMonthUnsafe(MonthKey(year, month))
}

fun ShiftRepository.setFoodPerDayFromMonth(year: Int, month: Int, value: Double) {
    if (year <= 0 || month !in 1..12 || !value.isFinite() || value < 0.0) return
    synchronized(lock) {
        paySettingsUpdater.setFoodPerDayFromMonth(
            MonthKey(year, month),
            value.coerceAtMost(MAX_FOOD_PER_DAY)
        )
    }
    notifyChange()
}

internal fun ShiftRepository.resolveFoodPerDayForMonthUnsafe(month: MonthKey): Double {
    return payState.foodPerDayForMonth(month)
}

internal fun ShiftRepository.restoreMealPriceHistoryUnsafe(snapshot: ShiftRepositorySnapshot) {
    payState.foodPerDayChanges.clear()
    payState.foodPerDayChanges.putAll(
        snapshot.foodPerDayChanges.filter { (key, value) ->
            key.year > 0 && key.month in 1..12 && value.isFinite() && value >= 0.0
        }.mapValues { (_, value) -> value.coerceAtMost(MAX_FOOD_PER_DAY) }
    )
    // Ugrađena istorija se dopunjuje samo tamo gde korisnik nema svoju cenu
    // koja već pokriva taj period: postojeće instalacije su snimljene bez
    // perioda pre 01.06.2026 (cena 274,00 sa listića 05/2026), pa bi im
    // obračun starijih meseci koristio pogrešnu cenu. Ako korisnik ima cenu
    // koja važi od ranijeg meseca, ona ostaje netaknuta.
    DEFAULT_MEAL_PRICE_PERIODS.forEach { period ->
        val key = MonthKey(period.validFrom.year, period.validFrom.monthValue)
        val coveredByUserPrice = payState.foodPerDayChanges.keys.any { it <= key }
        if (!coveredByUserPrice) {
            payState.foodPerDayChanges[key] = period.netAmountPerMeal.toDouble()
        }
    }
}

fun ShiftRepository.getMealPricePeriods(): List<MealPricePeriod> = synchronized(lock) {
    payState.mealPricePeriods()
}

var ShiftRepository.transportDailyAmountValue: Double
    get() = synchronized(lock) { payState.transportDailyAmount }
    set(value) {
        synchronized(lock) {
            if (!value.isFinite()) {
                Log.w(TAG, "Odbijena nevažeća vrednost prevoza: $value")
                return
            }
            if (value < 0.0) {
                Log.w(TAG, "Odbijena negativna vrednost prevoza: $value")
                return
            }
            paySettingsUpdater.setTransportDailyAmount(value.coerceAtMost(MAX_TRANSPORT_PER_DAY))
        }
        notifyChange()
    }

/**
 * Glavni prekidač za automatski upis akontacije iz NLB obaveštenja.
 * Kad je isključen, servis ne upisuje ništa i nema pitanja za pristup obaveštenjima.
 */
/**
 * Da li kartice ispod kalendara („Septembarska plata", „Primanja u Septembru")
 * prikazuju samo neto platu za rad (podrazumevano), bez regresa i prevoza.
 * Isključeno = plata, regres i prevoz zajedno; detalji plate uvek prikazuju sve stavke.
 */
var ShiftRepository.mainPaySalaryOnly: Boolean
    get() = synchronized(lock) { mainPaySalaryOnlyState }
    set(value) {
        val changed = synchronized(lock) {
            if (mainPaySalaryOnlyState == value) {
                false
            } else {
                mainPaySalaryOnlyState = value
                true
            }
        }
        if (changed) {
            notifyChange()
        }
    }

var ShiftRepository.notificationAccessPromptSuppressed: Boolean
    get() = synchronized(lock) { notificationAccessPromptSuppressedState }
    set(value) {
        val changed = synchronized(lock) {
            if (notificationAccessPromptSuppressedState == value) {
                false
            } else {
                notificationAccessPromptSuppressedState = value
                true
            }
        }
        if (changed) {
            notifyChange()
        }
    }

internal fun PayBaseSelectionMode.toGlobalPayBaseSelectionMode(): PayBaseSelectionMode {
    return when (this) {
        PayBaseSelectionMode.MANUAL -> PayBaseSelectionMode.MANUAL
        PayBaseSelectionMode.KNOWN_BASE -> PayBaseSelectionMode.KNOWN_BASE
        PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS,
        PayBaseSelectionMode.DEFAULT_VALUES -> PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
    }
}
