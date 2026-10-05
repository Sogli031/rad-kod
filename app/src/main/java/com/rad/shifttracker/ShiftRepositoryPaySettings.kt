@file:Suppress("TooManyFunctions", "LongMethod")

package com.rad.shifttracker

import android.util.Log
import com.rad.shifttracker.ShiftRepository.Companion.ADVANCE_SAME_AMOUNT_TOLERANCE
import com.rad.shifttracker.ShiftRepository.Companion.MAX_FOOD_PER_DAY
import com.rad.shifttracker.ShiftRepository.Companion.MAX_PERCENT
import com.rad.shifttracker.ShiftRepository.Companion.MAX_TAX_RELIEF
import com.rad.shifttracker.ShiftRepository.Companion.MAX_TRANSPORT_PER_DAY
import com.rad.shifttracker.ShiftRepository.Companion.TAG
import com.rad.shifttracker.data.storage.ShiftRepositorySnapshot
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs

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

/** Listić sačuvan za radni mesec („Uporedi“), ili null ako ga nema. */
internal fun ShiftRepository.getSavedPayslip(year: Int, month: Int): ParsedPayslip? {
    val key = MonthKey(year, month)
    val json = synchronized(lock) { savedPayslipJsonByMonth[key] } ?: return null
    // Listić iz oštećenog backup-a čiji mesec ne odgovara ključu ne prikazuje se pod tuđim mesecom.
    return payslipFromJsonOrNull(json)?.takeIf { it.month == key }
}

/**
 * Dani godišnjeg (šifra 10 / 8 h) sa sačuvanih listića za godinu. Listić bez šifre 10 daje 0,
 * a listić koji ne može da se pročita se preskače (taj mesec se broji po kalendaru).
 */
internal fun ShiftRepository.payslipVacationDaysByMonthUnsafe(year: Int): Map<Int, Double> =
    savedPayslipJsonByMonth.entries
        .filter { it.key.year == year }
        .mapNotNull { (key, json) ->
            val cached = payslipVacationDaysCache[key]?.takeIf { it.first == json }?.second
            val days = cached ?: payslipFromJsonOrNull(json)
                ?.takeIf { it.month == key }
                ?.let(::payslipVacationDays)
                ?.also { payslipVacationDaysCache[key] = json to it }
            days?.let { key.month to it }
        }
        .toMap()

/** Listić meseca je korisnik obrisao („Obriši listu“), pa se ne upisuje ponovo sam od sebe. */
internal fun ShiftRepository.isPayslipDeleted(month: MonthKey): Boolean = synchronized(lock) { month in deletedPayslipTombstones }

/** Mesec najnovijeg sačuvanog listića, ili null ako nijedan nije sačuvan. */
internal fun ShiftRepository.getLatestSavedPayslipMonth(): MonthKey? = synchronized(lock) { savedPayslipJsonByMonth.keys.maxOrNull() }

/** Meseci svih sačuvanih listića, od najstarijeg. */
internal fun ShiftRepository.getSavedPayslipMonths(): List<MonthKey> = synchronized(lock) { savedPayslipJsonByMonth.keys.sorted() }

/**
 * Čuva listić pod njegovim mesecem (zamenjuje raniji za isti mesec); ide i u backup.
 * Ako za taj mesec nije uneta osnovica, uzima se sa listića (GO, PO, praznik, bolovanje).
 */
/** [withPayBase] = false: listić se samo čuva, osnovica sa njega se ne upisuje (stariji listići). */
internal fun ShiftRepository.savePayslip(payslip: ParsedPayslip, withPayBase: Boolean = true) {
    val json = payslipToJson(payslip)
    val changed = synchronized(lock) {
        deletedPayslipTombstones.remove(payslip.month)
        savedPayslipJsonByMonth.put(payslip.month, json) != json
    }
    val anchor = payslipPayBaseAnchor(payslip)?.takeIf { withPayBase }
    if (anchor != null && getKnownPayBaseAnchors().none { it.month == anchor.month }) {
        setKnownPayBaseAnchor(anchor.month.year, anchor.month.month, anchor.grossHourlyBase)
        ensureKnownPayBaseMode(this)
    } else if (changed) {
        notifyChange()
    }
}

/**
 * Briše sačuvan listić meseca (i iz backup-a, preko tombstone-a). Podešavanja koja su
 * ranije upisana sa listića (koeficijent, osnovica, porez...) ostaju kakva jesu.
 */
internal fun ShiftRepository.deletePayslip(month: MonthKey) {
    val removed = synchronized(lock) {
        (savedPayslipJsonByMonth.remove(month) != null).also { wasRemoved ->
            if (wasRemoved) {
                deletedPayslipTombstones.add(month)
                payslipVacationDaysCache.remove(month)
            }
        }
    }
    if (removed) notifyChange()
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

fun ShiftRepository.setMonthlyPayBases(year: Int, month: Int, bases: MonthlyPayBases) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        val key = MonthKey(year, month)
        val sanitized = MonthlyPayBases(
            year = year,
            month = month,
            vacationHourlyBase = bases.vacationHourlyBase.sanitizePayBaseOrNull(),
            paidHolidayHourlyBase = bases.paidHolidayHourlyBase.sanitizePayBaseOrNull(),
            sickHourlyBase = bases.sickHourlyBase.sanitizePayBaseOrNull(),
            selectionMode = bases.selectionMode
        )
        if (
            sanitized.vacationHourlyBase == null &&
            sanitized.paidHolidayHourlyBase == null &&
            sanitized.sickHourlyBase == null &&
            sanitized.selectionMode == null
        ) {
            payState.monthlyPayBases.remove(key)
        } else {
            payState.monthlyPayBases[key] = sanitized
        }
        payState.invalidateSummariesAndRolling()
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyPayBases(year: Int, month: Int): MonthlyPayBases? = synchronized(lock) {
    payState.monthlyPayBases[MonthKey(year, month)]
}

fun ShiftRepository.setMonthlyGrossAdditions(year: Int, month: Int, additions: MonthlyGrossAdditions) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyGrossAdditions(year, month, additions)
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyGrossAdditions(year: Int, month: Int): MonthlyGrossAdditions = synchronized(lock) {
    monthlyAdjustments.getMonthlyGrossAdditions(year, month)
}

fun ShiftRepository.setMonthlyAbsencePenaltyGross(year: Int, month: Int, amount: java.math.BigDecimal) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyAbsencePenaltyGross(year, month, amount)
    }
    notifyChange()
}

@Deprecated("Legacy backup compatibility only; meal gross is always calculated from the applied meal net.")
@Suppress("DEPRECATION")
fun ShiftRepository.setMonthlyMealGrossOverride(year: Int, month: Int, amount: Double?) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealGrossOverride(year, month, amount)
    }
    notifyChange()
}

@Deprecated("Legacy backup compatibility only; settlement adjustment is ignored by payroll calculation.")
@Suppress("DEPRECATION")
fun ShiftRepository.setMonthlyMealSettlementAdjustmentNet(year: Int, month: Int, amount: Double) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealSettlementAdjustmentNet(year, month, amount)
    }
    notifyChange()
}

fun ShiftRepository.setMonthlyAdvancePaymentNet(year: Int, month: Int, amount: Double) {
    if (year <= 0 || month !in 1..12) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyAdvancePaymentNet(year, month, amount)
    }
    notifyChange()
}

/**
 * Akontacija za [month] sa obračuna akontacije (iznos uplaćen na račun). Obračun je tačniji od
 * ručnog unosa, pa ga zamenjuje; true ako je iznos promenjen.
 */
fun ShiftRepository.recordAdvancePaymentFromPayslip(month: MonthKey, amount: Double): Boolean {
    val changed = synchronized(lock) {
        if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
            return@synchronized false
        }
        val current = monthlyAdjustments.getMonthlyNetAdjustments(month.year, month.month)
        if (abs(current.advancePaymentNet - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) return@synchronized false
        monthlyAdjustments.setMonthlyAdvancePaymentNet(month.year, month.month, amount)
        true
    }
    if (changed) notifyChange()
    return changed
}

/** Neto regres koji važi za [month] (poslednji sa obračuna regresa, inače 20.000). */
fun ShiftRepository.getRegressNetForMonth(month: MonthKey): Double = synchronized(lock) { payState.regressNetForMonth(month) }

fun ShiftRepository.isRegressPaidInMonth(month: MonthKey): Boolean = synchronized(lock) { payState.regressPaidInMonth(month) }

/**
 * Regres sa obračuna regresa za mesec [month] (iznos uplaćen na račun). Mesec se pamti uvek, pa se
 * regres računa u tom mesecu i kad nije 2/5/8/11 (npr. dve rate 20.000 u oktobru i novembru);
 * iznos važi i za kasnije mesece. True samo kad se iznos razlikuje od onog koji je za mesec važio.
 */
fun ShiftRepository.recordRegressFromPayslip(month: MonthKey, amount: Double): Boolean {
    var amountChanged = false
    val changed = synchronized(lock) {
        if (month.year <= 0 || month.month !in 1..12 || amount <= 0.0 || !amount.isFinite()) {
            return@synchronized false
        }
        amountChanged = abs(payState.regressNetForMonth(month) - amount) >= ADVANCE_SAME_AMOUNT_TOLERANCE
        val stored = payState.regressNetChanges[month]
        if (!amountChanged && stored != null && abs(stored - amount) < ADVANCE_SAME_AMOUNT_TOLERANCE) {
            return@synchronized false
        }
        payState.regressNetChanges[month] = amount
        true
    }
    if (changed) notifyChange()
    return amountChanged
}

fun ShiftRepository.getMonthlyNetAdjustments(year: Int, month: Int): MonthlyNetAdjustments = synchronized(lock) {
    monthlyAdjustments.getMonthlyNetAdjustments(year, month)
}

fun ShiftRepository.setMonthlyActualFoodSpentNet(year: Int, month: Int, amount: Double?) {
    if (year <= 0 || month !in 1..12) return
    if (amount != null && (!amount.isFinite() || amount < 0.0)) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyActualFoodSpentNet(year, month, amount)
    }
    notifyChange()
}

fun ShiftRepository.setMonthlyActualFoodSpentNetDecimal(year: Int, month: Int, amount: BigDecimal?) {
    setMonthlyActualFoodSpentNet(year, month, amount?.toMealMoney()?.toDouble())
}

internal fun ShiftRepository.setMonthlyMealEntitlementNetOverride(year: Int, month: Int, amount: BigDecimal?) {
    if (year <= 0 || month !in 1..12 || amount?.signum() == -1) return
    synchronized(lock) {
        monthlyAdjustments.setMonthlyMealEntitlementNetOverride(year, month, amount?.toMealMoney())
    }
    notifyChange()
}

fun ShiftRepository.getMonthlyFoodSpendingBreakdown(year: Int, month: Int): MonthlyFoodSpendingBreakdown = synchronized(lock) {
    getMonthlyFoodSpendingBreakdownUnsafe(MonthKey(year, month))
}

fun ShiftRepository.getResolvedPayBases(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
    getResolvedPayBasesUnsafe(year, month)
}

fun ShiftRepository.getRollingPayBaseCalculation(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
    getRollingPayBaseCalculationUnsafe(year, month)
}

fun ShiftRepository.getPayBaseSourceMonthForWorkMonth(year: Int, month: Int): MonthKey = synchronized(lock) {
    payQueries.getPayBaseResolutionMonthForWorkMonthUnsafe(year, month)
}

fun ShiftRepository.getResolvedPayBasesForWorkMonth(year: Int, month: Int): ResolvedPayBases = synchronized(lock) {
    payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
}

fun ShiftRepository.getRollingPayBaseCalculationForWorkMonth(year: Int, month: Int): RollingPayBaseCalculation? = synchronized(lock) {
    payQueries.getResolvedPayBasesForWorkMonthUnsafe(year, month)
        .sickHourlyBase
        .calculation
}

internal fun ShiftRepository.getResolvedPayBasesUnsafe(year: Int, month: Int): ResolvedPayBases {
    return payQueries.getResolvedPayBasesUnsafe(year, month)
}

internal fun ShiftRepository.getRollingPayBaseCalculationUnsafe(year: Int, month: Int): RollingPayBaseCalculation? {
    return payQueries.getRollingPayBaseCalculationUnsafe(year, month)
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
