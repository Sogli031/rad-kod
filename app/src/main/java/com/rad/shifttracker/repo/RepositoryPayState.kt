package com.rad.shifttracker.repo

import com.rad.shifttracker.osnova.AppConfig
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.obracun.DEFAULT_REGRESS_NET
import com.rad.shifttracker.obracun.DEFAULT_MEAL_PRICE_PERIODS
import com.rad.shifttracker.obracun.KnownPayBaseAnchor
import com.rad.shifttracker.obracun.MealPricePeriod
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthSummary
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetAdjustments
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.obracun.PayCalculator
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.RollingPayBaseCalculation
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.obracun.WebTaxParameters
import java.math.BigDecimal
import java.time.LocalDate
import java.util.TreeMap

internal class RepositoryPayState(config: AppConfig) {
    var payCoefficient = config.pay.defaultCoefficient
    val hourlyValueChanges = mutableMapOf<MonthKey, Double>()

    /** Neto regres od meseca (iz obračuna regresa); pre prvog upisa važi [DEFAULT_REGRESS_NET]. */
    val regressNetChanges = TreeMap<MonthKey, Double>()
    var sickPayBaseHourly = config.pay.sickPayBaseHourly
    var defaultPayBaseSelectionMode = PayBaseSelectionMode.CALCULATED_PREVIOUS_12_MONTHS
    var foodPerDay = config.pay.foodPerDay
    val foodPerDayChanges = DEFAULT_MEAL_PRICE_PERIODS.associateTo(mutableMapOf()) { period ->
        MonthKey(period.validFrom.year, period.validFrom.monthValue) to period.netAmountPerMeal.toDouble()
    }
    /** Prevoz po danu iz najnovijeg obračuna prevoza; važi za mesece rada bez svog obračuna. */
    var transportDailyAmount = config.pay.transportPerWorkDay

    /** Prevoz po danu po mesecu rada, sa obračuna prevoza tog meseca (avgust 380, septembar 393,02...). */
    val transportDailyByMonth = TreeMap<MonthKey, Double>()
    var employeeContributionsPercent = config.pay.defaultEmployeeContributionsPercent
    var taxRelief = config.pay.defaultTaxRelief
    var taxPercent = config.pay.defaultTaxPercent

    /** Porez, doprinosi i olakšica sa interneta; ne čuva se u backup-u (svoj fajl, [com.rad.shifttracker.uvoz.WebTaxUpdate]). */
    var webTaxParameters: WebTaxParameters? = null
    var difficultWorkConditionsPercent = config.pay.difficultWorkConditionsPercent
    var initialCompBalance = 0
    var initialCompBalanceMinutes = 0
    var minuliRadStartYear = 0
    var minuliRadStartMonth = 2
    var seniorityInputMode = SeniorityInputMode.KNOWN_START
    var minuliRadBenefitEnabled = false
    var minuliRadIncreaseFactor = SeniorityIncreaseFactor.NONE
    var minuliRadLastPayslipYear = 0
    var minuliRadLastPayslipMonth = 1
    var minuliRadPayslipPercent = 0.0
    var brigadeType = BrigadeType.TROBRIGADA
    val monthlyPayBases = mutableMapOf<MonthKey, MonthlyPayBases>()
    val monthlyGrossAdditions = mutableMapOf<MonthKey, MonthlyGrossAdditions>()
    val monthlyNetAdjustments = mutableMapOf<MonthKey, MonthlyNetAdjustments>()
    // Osnovice prepisane sa platnih listića, po radnom mesecu na čijem su listiću.
    val knownPayBaseAnchors = TreeMap<MonthKey, Double>()

    /** Najnovije uneto sidro. */
    val knownPayBaseAnchor: KnownPayBaseAnchor?
        get() = knownPayBaseAnchors.lastEntry()?.let { KnownPayBaseAnchor(it.key, it.value) }
    val summaries = mutableMapOf<MonthKey, MonthSummary>()
    val rollingPayBaseCache = mutableMapOf<MonthKey, RollingPayBaseCalculation?>()
    val dirtyMonths = mutableSetOf<MonthKey>()
    val compBalanceCache = mutableMapOf<MonthKey, Int>()
    var cachedSummaryCalc: PayCalculator? = null
    var cachedSummaryCalcFoodPerDay: Double = Double.NaN
    var cachedSummaryCalcSickPayBaseHourly: Double = Double.NaN
    var cachedSummaryCalcDifficultWorkConditionsPercent: Double = Double.NaN

    /**
     * Sidro koje važi za mesec: najnovije čiji mesec nije posle njega. Tako svaki
     * novi listić preuzima projekciju, pa se greška prenosa ne gomila kroz mesece.
     * Mesec pre prvog listića uzima prvo kasnije sidro — bliže je stvarnoj osnovici od podrazumevane.
     */
    fun knownPayBaseAnchorFor(month: MonthKey): KnownPayBaseAnchor? {
        val entry = knownPayBaseAnchors.floorEntry(month) ?: knownPayBaseAnchors.ceilingEntry(month)
        return entry?.let { KnownPayBaseAnchor(it.key, it.value) }
    }

    fun restoreRegressNetChanges(changes: Map<MonthKey, Double>) {
        regressNetChanges.clear()
        regressNetChanges.putAll(changes.filterValues { it.isFinite() && it > 0.0 })
    }

    fun restoreTransportDailyByMonth(values: Map<MonthKey, Double>) {
        transportDailyByMonth.clear()
        transportDailyByMonth.putAll(values.filterValues { it.isFinite() && it > 0.0 })
    }

    /** Prevoz po danu za mesec rada: iz obračuna tog meseca, inače najnoviji poznat. */
    fun transportDailyFor(month: MonthKey): Double = transportDailyByMonth[month] ?: transportDailyAmount

    fun regressNetForMonth(month: MonthKey): Double = regressNetChanges.floorEntry(month)?.value ?: DEFAULT_REGRESS_NET

    /** Za mesec je stigao obračun regresa (i kad je iznos isti kao raniji), makar nije 2/5/8/11. */
    fun regressPaidInMonth(month: MonthKey): Boolean = regressNetChanges.containsKey(month)

    fun foodPerDayForMonth(month: MonthKey): Double {
        val effectiveEntry = foodPerDayChanges.entries
            .asSequence()
            .filter { (effectiveMonth, _) -> effectiveMonth <= month }
            .maxByOrNull { (effectiveMonth, _) -> effectiveMonth }
        return effectiveEntry?.value
            ?: foodPerDayChanges.minByOrNull { (effectiveMonth, _) -> effectiveMonth }?.value
            ?: foodPerDay
    }

    fun mealPricePeriods(): List<MealPricePeriod> {
        return foodPerDayChanges
            .map { (month, amount) ->
                MealPricePeriod(
                    validFrom = LocalDate.of(month.year, month.month, 1),
                    netAmountPerMeal = BigDecimal.valueOf(amount)
                ).sanitized()
            }
            .sortedBy { it.validFrom }
    }

    fun invalidateSummariesAndRolling() {
        summaries.clear()
        rollingPayBaseCache.clear()
    }

    fun invalidateSummariesRollingAndCompBalance() {
        invalidateSummariesAndRolling()
        compBalanceCache.clear()
    }
}
