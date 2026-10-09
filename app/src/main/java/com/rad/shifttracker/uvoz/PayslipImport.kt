package com.rad.shifttracker.uvoz

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToInt
import com.rad.shifttracker.osnova.CONFIG
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthlyPayDetails
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.obracun.fundAbsenceMinutes
import com.rad.shifttracker.obracun.holidayPremiumMinutes
import com.rad.shifttracker.obracun.paidWorkMinuteSegments
import com.rad.shifttracker.repo.difficultWorkConditionsPercentValue
import com.rad.shifttracker.repo.employeeContributionsPercentValue
import com.rad.shifttracker.repo.getHourlyValueForMonth
import com.rad.shifttracker.repo.getFoodPerDayForMonth
import com.rad.shifttracker.repo.getKnownPayBaseAnchors
import com.rad.shifttracker.repo.getMonthlyPayDetails
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.minuliRadLastPayslipMonthValue
import com.rad.shifttracker.repo.minuliRadLastPayslipYearValue
import com.rad.shifttracker.repo.minuliRadPayslipPercentValue
import com.rad.shifttracker.repo.payCoefficientValue
import com.rad.shifttracker.repo.seniorityInputModeValue
import com.rad.shifttracker.repo.setFoodPerDayFromMonth
import com.rad.shifttracker.repo.setHourlyValueChange
import com.rad.shifttracker.repo.setKnownPayBaseAnchor
import com.rad.shifttracker.repo.taxPercentValue
import com.rad.shifttracker.repo.taxReliefValue
import com.rad.shifttracker.osnova.formatPayBaseRateText
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.listici.DIFFICULT_WORK_CONDITIONS_CODE
import com.rad.shifttracker.listici.PAYSLIP_GROSS_ROW
import com.rad.shifttracker.listici.PAYSLIP_MEAL_ROW
import com.rad.shifttracker.listici.PAYSLIP_NET_ROW
import com.rad.shifttracker.listici.PAYSLIP_PAYOUT_ROW
import com.rad.shifttracker.listici.PAYSLIP_SENIORITY_AMOUNT_ROW
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipDifference
import com.rad.shifttracker.listici.PayslipExtraItem
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.formatSerbianDecimal
import com.rad.shifttracker.listici.isSickLeave
import com.rad.shifttracker.listici.payslipDifferences
import com.rad.shifttracker.listici.payslipFormulaText
import com.rad.shifttracker.listici.payslipImportErrorText
import com.rad.shifttracker.listici.payslipMealEntitlementNet
import com.rad.shifttracker.listici.payslipMealOverspend
import com.rad.shifttracker.listici.payslipRunNumber
import com.rad.shifttracker.listici.payslipSourceText
import com.rad.shifttracker.listici.unknownHourItems
import com.rad.shifttracker.listici.ABSENCE_ROUNDING_EXTRA_LABEL
import com.rad.shifttracker.listici.PayslipCheckRow
import com.rad.shifttracker.repo.ensureKnownPayBaseMode
import com.rad.shifttracker.listici.toDisplayDecimal
import com.rad.shifttracker.listici.PayslipRowTone

/**
 * Uvoz platnih listića u Opcijama: predlog izmena podešavanja (sa potvrdom) i
 * provera obračuna aplikacije naspram listića (samo prikaz, ništa se ne menja).
 *
 * Koeficijent, doprinosi, porez, olakšica i staž važe za sve mesece, pa se predlažu
 * samo sa najnovijeg učitanog listića. Vrednost sata i osnovica su po mesecu.
 */
internal enum class PayslipChangeKind {
    COEFFICIENT,
    HOURLY_VALUE,
    CONTRIBUTIONS,
    TAX_RELIEF,
    TAX,
    DIFFICULT_CONDITIONS,
    SENIORITY,
    PAY_BASE,
    MEAL_PRICE
}

internal data class PayslipChange(
    val kind: PayslipChangeKind,
    val month: MonthKey,
    val label: String,
    val currentText: String,
    val payslipText: String,
    val value: BigDecimal
)

internal data class PayslipReconciliation(
    val month: MonthKey,
    val hasAppData: Boolean,
    val rows: List<PayslipCheckRow>,
    /** Spisak ispod tabele: „Ne slaže se“ po stavkama i neutralne napomene o zbirovima ([PayslipDifference.isNote]). */
    val differences: List<PayslipDifference> = emptyList()
)

internal data class PayslipFileFailure(val fileName: String, val message: String)

internal data class PayslipImportPlan(
    val payslips: List<ParsedPayslip>,
    val failures: List<PayslipFileFailure>,
    val changes: List<PayslipChange>,
    val reconciliations: List<PayslipReconciliation>
)

private const val VALUE_TOLERANCE = 0.005
private const val MONEY_MATCH_TOLERANCE = 1.0
private val SIXTY = BigDecimal(60)
/** Neto TO2 se na listiću zaokružuje na pare, pa broj obroka × cena sme da odstupi do dinara. */
private const val MEAL_PRICE_MATCH_TOLERANCE = 1.0
private const val MIN_PLAUSIBLE_MEAL_PRICE = 50
private const val MAX_PLAUSIBLE_MEAL_PRICE = 2_000

internal fun buildPayslipImportPlan(
    repo: ShiftRepository,
    parsed: List<ParsedPayslip>,
    failures: List<PayslipFileFailure>,
    /** Stariji listić (npr. ponovo učitan januar) ne sme da vrati koeficijent, porez i staž unazad. */
    includeGlobalSettings: Boolean = true
): PayslipImportPlan {
    // Isti mesec više puta (npr. rb.2 i rb.3): važi kasniji obračun.
    val payslips = parsed
        .sortedWith(compareBy<ParsedPayslip>({ it.month.year }, { it.month.month }, { payslipRunNumber(it.runLabel) }))
        .associateBy { it.month }
        .values
        .toList()
    val changes = mutableListOf<PayslipChange>()
    if (includeGlobalSettings) {
        payslips.lastOrNull()?.let { newest -> changes += globalSettingChanges(repo, newest) }
    }
    for (payslip in payslips) {
        changes += monthlyChanges(repo, payslip)
    }
    return PayslipImportPlan(
        payslips = payslips,
        failures = failures,
        changes = changes,
        reconciliations = payslips.map { buildPayslipReconciliation(repo, it) }
    )
}

private fun globalSettingChanges(repo: ShiftRepository, payslip: ParsedPayslip): List<PayslipChange> {
    val month = payslip.month
    val changes = mutableListOf<PayslipChange>()
    fun proposeIfDifferent(kind: PayslipChangeKind, label: String, current: Double, value: BigDecimal?, unit: String) {
        if (value == null || value.signum() < 0 || abs(current - value.toDouble()) < VALUE_TOLERANCE) return
        changes += PayslipChange(
            kind = kind,
            month = month,
            label = label,
            currentText = "${formatSerbianDecimal(current.toDisplayDecimal())}$unit",
            payslipText = "${formatSerbianDecimal(value)}$unit",
            value = value
        )
    }
    proposeIfDifferent(PayslipChangeKind.COEFFICIENT, "Koeficijent", repo.payCoefficientValue, payslip.coefficient, "")
    proposeIfDifferent(
        PayslipChangeKind.CONTRIBUTIONS,
        "Doprinosi radnika",
        repo.employeeContributionsPercentValue,
        payslip.employeeContributionsPercent,
        "%"
    )
    proposeIfDifferent(PayslipChangeKind.TAX_RELIEF, "Poreska olakšica", repo.taxReliefValue, payslip.taxReliefRsd, " RSD")
    proposeIfDifferent(PayslipChangeKind.TAX, "Porez", repo.taxPercentValue, payslip.taxPercent, "%")
    // Otežani uslovi važe samo dok ih listić ima (šifra 21); bez šifre se brišu.
    val difficultItem = payslip.items[DIFFICULT_WORK_CONDITIONS_CODE]
    if (difficultItem == null || difficultItem.percent != null) {
        proposeIfDifferent(
            PayslipChangeKind.DIFFICULT_CONDITIONS,
            "Otežani uslovi rada",
            repo.difficultWorkConditionsPercentValue,
            difficultItem?.percent ?: BigDecimal.ZERO,
            "%"
        )
    }

    payslip.seniorityPercent?.let { slipPercent ->
        val appPercent = appSeniorityPercent(repo, month)
        if (abs(appPercent - slipPercent.toDouble()) >= VALUE_TOLERANCE) {
            val years = payslip.seniorityYears?.let { " ($it god.)" }.orEmpty()
            changes += PayslipChange(
                kind = PayslipChangeKind.SENIORITY,
                month = month,
                label = "Minuli rad (staž)",
                currentText = "${formatSerbianDecimal(appPercent.toDisplayDecimal())}%",
                payslipText = "${formatSerbianDecimal(slipPercent)}%$years",
                value = slipPercent
            )
        }
    }
    return changes
}

private fun appSeniorityPercent(repo: ShiftRepository, month: MonthKey): Double {
    val details = repo.getMonthlyPayDetails(month.year, month.month)
    return details.completedYearsOfService * CONFIG.pay.minuliRadPerYear * 100.0
}

private fun monthlyChanges(repo: ShiftRepository, payslip: ParsedPayslip): List<PayslipChange> {
    val month = payslip.month
    val monthText = monthYearText(month)
    val changes = mutableListOf<PayslipChange>()

    payslip.hourlyValue?.let { value ->
        val current = repo.getHourlyValueForMonth(month.year, month.month)
        if (value.signum() > 0 && abs(current - value.toDouble()) >= VALUE_TOLERANCE) {
            changes += PayslipChange(
                kind = PayslipChangeKind.HOURLY_VALUE,
                month = month,
                label = "Vrednost sata od $monthText",
                currentText = formatSerbianDecimal(current.toDisplayDecimal(), minFraction = 2),
                payslipText = formatSerbianDecimal(value, minFraction = 2),
                value = value
            )
        }
    }

    (payslip.payBase as? PayslipPayBaseResult.Success)?.let { base ->
        val value = base.chosen.hourlyBase
        val current = repo.getKnownPayBaseAnchors().firstOrNull { it.month == month }?.grossHourlyBase
        if (current == null || abs(current - value.toDouble()) >= VALUE_TOLERANCE) {
            changes += PayslipChange(
                kind = PayslipChangeKind.PAY_BASE,
                month = month,
                label = "Osnovica naknada $monthText",
                currentText = current?.let(::formatPayBaseRateText) ?: "nije uneta",
                payslipText = payslipFormulaText(base.chosen),
                value = value
            )
        }
    }

    payslipMealPrice(repo, payslip)?.let { price ->
        val current = repo.getFoodPerDayForMonth(month.year, month.month)
        if (abs(current - price.toDouble()) >= VALUE_TOLERANCE) {
            changes += PayslipChange(
                kind = PayslipChangeKind.MEAL_PRICE,
                month = month,
                label = "Topli obrok po danu od $monthText",
                currentText = formatSerbianDecimal(current.toDisplayDecimal(), minFraction = 2),
                payslipText = formatSerbianDecimal(price, minFraction = 2),
                value = price
            )
        }
    }
    return changes
}

/**
 * Cena obroka sa listića. TO2 je pojedeno, ograničeno onim što pripada; samo kad je pojedeno
 * više (obustava „Topli obrok“ veća od neto TO2), neto TO2 je pun iznos koji pripada
 * (broj obroka × cena). U ostalim mesecima TO2 = pojedeno i ne govori ništa o ceni.
 * Broj obroka daju upisane smene meseca. Cena je ceo dinar, pa se predlaže samo kad se TO2
 * deli bez ostatka — inače smene u aplikaciji ne odgovaraju obračunu i cena se ne dira.
 */
internal fun payslipMealPrice(repo: ShiftRepository, payslip: ParsedPayslip): BigDecimal? {
    payslipMealOverspend(payslip) ?: return null
    val entitledNet = payslipMealEntitlementNet(payslip)?.takeIf { it > 0.0 } ?: return null
    val month = payslip.month
    val mealCount = repo.getNetPayBreakdown(month.year, month.month)
        .mealEntitlementCalculation.recognizedMealCount
    if (mealCount <= 0) return null
    val price = (entitledNet / mealCount).roundToInt()
    if (price !in MIN_PLAUSIBLE_MEAL_PRICE..MAX_PLAUSIBLE_MEAL_PRICE) return null
    if (abs(price.toDouble() * mealCount - entitledNet) > MEAL_PRICE_MATCH_TOLERANCE) return null
    return BigDecimal(price).setScale(2)
}

internal fun applyPayslipChanges(repo: ShiftRepository, changes: List<PayslipChange>) {
    for (change in changes.sortedWith(compareBy({ it.month.year }, { it.month.month }))) {
        val value = change.value.toDouble()
        val (year, month) = change.month.year to change.month.month
        when (change.kind) {
            PayslipChangeKind.COEFFICIENT -> repo.payCoefficientValue = value
            PayslipChangeKind.HOURLY_VALUE -> repo.setHourlyValueChange(year, month, value)
            PayslipChangeKind.CONTRIBUTIONS -> repo.employeeContributionsPercentValue = value
            PayslipChangeKind.TAX_RELIEF -> repo.taxReliefValue = value
            PayslipChangeKind.TAX -> repo.taxPercentValue = value
            PayslipChangeKind.DIFFICULT_CONDITIONS -> repo.difficultWorkConditionsPercentValue = value
            PayslipChangeKind.SENIORITY -> {
                repo.minuliRadLastPayslipYearValue = year
                repo.minuliRadLastPayslipMonthValue = month
                repo.minuliRadPayslipPercentValue = value
                repo.seniorityInputModeValue = SeniorityInputMode.PAYSLIP_PERCENT
            }
            PayslipChangeKind.PAY_BASE -> repo.setKnownPayBaseAnchor(year, month, value)
            PayslipChangeKind.MEAL_PRICE -> repo.setFoodPerDayFromMonth(year, month, value)
        }
    }
    ensureKnownPayBaseMode(repo)
}

internal fun buildPayslipReconciliation(repo: ShiftRepository, payslip: ParsedPayslip): PayslipReconciliation {
    val month = payslip.month
    val details = repo.getMonthlyPayDetails(month.year, month.month)
    val breakdown = repo.getNetPayBreakdown(month.year, month.month)
    val items = payslip.items
    val rows = mutableListOf<PayslipCheckRow>()

    payslip.fundHours?.let { fund ->
        rows += PayslipCheckRow("Fond sati", "${breakdown.monthlyFundHours} h", "$fund h", breakdown.monthlyFundHours == fund, isHours = true)
    }
    fun hoursRow(label: String, appMinutes: Int, slipHours: BigDecimal?) {
        if (slipHours == null && appMinutes <= 0) return
        val slipMinutes = slipHours?.multiply(SIXTY)?.toDouble()?.roundToInt() ?: 0
        rows += PayslipCheckRow(
            label = label,
            appText = "${formatSerbianDecimal(minutesToHours(appMinutes))} h",
            payslipText = "${formatSerbianDecimal(slipHours ?: BigDecimal.ZERO)} h",
            matches = slipMinutes == appMinutes,
            isHours = true
        )
    }
    hoursRow("Rad u režiji (01)", details.regularWorkedBaseMinutes, items[1]?.hours)
    hoursRow("Rad na praznik (05/23)", details.publicHolidayWorkedBaseMinutes, (items[5] ?: items[23])?.hours)
    hoursRow("Dodatak za praznik 123% (23)", holidayPremiumMinutes(repo, month), items[23]?.hours)
    hoursRow("Godišnji odmor (10)", details.vacationMinutes, items[10]?.hours)
    hoursRow("Plaćeno odsustvo (11)", details.paidLeaveMinutes, items[11]?.hours)
    hoursRow("Državni praznik (12)", details.paidHolidayMinutes, items[12]?.hours)
    val sickItems = items.values.filter { it.isSickLeave() }
    hoursRow(
        "Bolovanje (${sickItems.joinToString("+") { "%02d".format(it.code) }.ifEmpty { "40" }})",
        details.sickMinutes,
        sickItems.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { sum, item -> sum + item.hours }
    )
    hoursRow("Noćni rad (20)", details.nightMinutes, items[20]?.hours)
    hoursRow("Otežani uslovi (21)", difficultConditionsMinutes(repo, details), items[DIFFICULT_WORK_CONDITIONS_CODE]?.hours)
    hoursRow("Rad nedeljom (22)", details.sundayMinutes, items[22]?.hours)
    hoursRow("Prekovremeni rad (30)", details.overtimeMinutes, items[30]?.hours)
    hoursRow("Izostanci (95)", details.fundAbsenceMinutes(breakdown.monthlyFundHours), items[95]?.hours)

    payslip.seniorityPercent?.let { slipPercent ->
        val appPercent = details.completedYearsOfService * CONFIG.pay.minuliRadPerYear * 100.0
        rows += PayslipCheckRow(
            label = "Minuli rad",
            appText = "${formatSerbianDecimal(appPercent.toDisplayDecimal())}%",
            payslipText = "${formatSerbianDecimal(slipPercent)}%",
            matches = abs(appPercent - slipPercent.toDouble()) < VALUE_TOLERANCE
        )
    }
    fun moneyRow(label: String, appValue: Double, slipValue: BigDecimal?) {
        slipValue ?: return
        rows += PayslipCheckRow(
            label = label,
            appText = formatSerbianDecimal(appValue.toDisplayDecimal(), minFraction = 2),
            payslipText = formatSerbianDecimal(slipValue, minFraction = 2),
            matches = abs(appValue - slipValue.toDouble()) < MONEY_MATCH_TOLERANCE,
            difference = slipValue.toDouble() - appValue
        )
    }
    moneyRow(PAYSLIP_SENIORITY_AMOUNT_ROW, breakdown.grossSeniorityBonus, payslip.seniorityAmount)
    val mealOverspend = payslipMealOverspend(payslip)
    payslip.mealGross?.let { slipMeal ->
        rows += payslipMealRow(breakdown.grossMealAddition, slipMeal, mealOverspend)
    }
    moneyRow(PAYSLIP_GROSS_ROW, breakdown.grossTotal, payslip.grossTotal)
    moneyRow(PAYSLIP_NET_ROW, breakdown.netPayBeforeDeductions, payslip.netTotal)
    // Isplata bez kredita i ostalih obustava: neto minus pojedeni topli obrok, na obe strane.
    payslip.netTotal?.let { slipNet ->
        val slipPayout = slipNet - (payslip.mealDeduction ?: BigDecimal.ZERO)
        moneyRow(PAYSLIP_PAYOUT_ROW, breakdown.netPayBeforeDeductions - breakdown.mealNetDeduction, slipPayout)
    }

    return PayslipReconciliation(
        month = month,
        hasAppData = details.totalPaidMinutes > 0,
        rows = rows,
        differences = payslipDifferences(
            rows = rows,
            // Šifra sa časovima koju aplikacija ne računa ulazi kao stavka sa iznosom (naziv, šifra, sati).
            extras = payslipExtrasBeyondApp(payslip.extras, breakdown.grossRegressAddition, breakdown.absencePenaltyGross) +
                payslip.unknownHourItems().map { item ->
                    PayslipExtraItem("${item.name} (${"%02d".format(item.code)}, ${formatSerbianDecimal(item.hours)} h)", item.amount)
                },
            appGross = breakdown.grossTotal,
            slipGross = payslip.grossTotal,
            mealOverspend = mealOverspend
        ) + listOfNotNull(
            // Svaki listić ima TO2; bez njega je sačuvan starijom verzijom koja ga nije čitala.
            PayslipDifference("Lista je sačuvana starijom verzijom (bez toplog obroka) — učitaj je ponovo: Zameni listu")
                .takeIf { payslip.mealGross == null }
        )
    )
}

/**
 * Topli obrok: listić u TO2 priznaje pojedeno (najviše koliko pripada), aplikacija računa pun iznos.
 * Manje na listiću = ništa nije skinuto → zeleno; pojedeno više od priznatog → crveno.
 * Samo kad listić prizna više nego što aplikacija računa, to je prava razlika u broju obroka.
 */
internal fun payslipMealRow(appMealGross: Double, slipMealGross: BigDecimal, overspend: Double?): PayslipCheckRow {
    val difference = slipMealGross.toDouble() - appMealGross
    val tone = when {
        overspend != null -> PayslipRowTone.ALERT
        difference < MONEY_MATCH_TOLERANCE -> PayslipRowTone.GOOD
        else -> PayslipRowTone.DEFAULT
    }
    return PayslipCheckRow(
        label = PAYSLIP_MEAL_ROW,
        appText = formatSerbianDecimal(appMealGross.toDisplayDecimal(), minFraction = 2),
        payslipText = formatSerbianDecimal(slipMealGross, minFraction = 2),
        matches = tone == PayslipRowTone.GOOD,
        difference = difference,
        tone = tone
    )
}

/**
 * Stavke bez časova umanjene za ono što aplikacija već ima u bruto (regres, „Izostanci 1“ upisan
 * pri popunjavanju), da se u objašnjenju razlike ne broje dvaput. Stavka koju aplikacija ima,
 * a listić nema, ulazi sa minusom; stavka koja se slaže u dinar otpada.
 */
internal fun payslipExtrasBeyondApp(
    extras: List<PayslipExtraItem>,
    appRegressGross: Double,
    appAbsencePenaltyGross: BigDecimal
): List<PayslipExtraItem> {
    val inApp = mapOf(
        "Regres" to appRegressGross.toDisplayDecimal(),
        ABSENCE_ROUNDING_EXTRA_LABEL to appAbsencePenaltyGross.negate()
    )
    val fromSlip = extras.groupBy { it.label }.map { (label, items) ->
        val slipAmount = items.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount }
        PayslipExtraItem(label, slipAmount - (inApp[label] ?: BigDecimal.ZERO))
    }
    val onlyInApp = inApp.filterKeys { label -> extras.none { it.label == label } }
        .map { (label, amount) -> PayslipExtraItem(label, amount.negate()) }
    return (fromSlip + onlyInApp).filter { it.amount.setScale(2, RoundingMode.HALF_UP).signum() != 0 }
}

/** Minuti na koje je aplikacija platila otežane uslove (iz iznosa, jer nemaju svoj brojač). */
private fun difficultConditionsMinutes(repo: ShiftRepository, details: MonthlyPayDetails): Int {
    val hourly = details.baseHourlyGross * repo.difficultWorkConditionsPercentValue / 100.0
    if (hourly <= 0.0 || !hourly.isFinite()) return 0
    return (details.grossDifficultWorkConditions / hourly * 60).roundToInt()
}

/** Minuti plaćeni sa 123% (po satu: treća smena deli dan u ponoć), isto kao u obračunu. */
private fun holidayPremiumMinutes(repo: ShiftRepository, month: MonthKey): Int =
    repo.getMonthRecords(month.year, month.month).sumOf { record ->
        paidWorkMinuteSegments(record).sumOf { (shift, minutes) -> record.holidayPremiumMinutes(shift, minutes) }
    }

private fun minutesToHours(minutes: Int): BigDecimal =
    BigDecimal(minutes).divide(SIXTY, 2, RoundingMode.HALF_UP)

internal fun payslipImportSummaryText(payslip: ParsedPayslip): String {
    val header = monthYearText(payslip.month) + (payslip.runLabel?.let { " · $it" } ?: "")
    return when (val base = payslip.payBase) {
        is PayslipPayBaseResult.Success -> "$header — osnovica ${payslipSourceText(base.chosen)}: " +
            formatSerbianDecimal(base.chosen.hourlyBase, minFraction = 2) + " ${CONFIG.pay.currency}/h"
        else -> "$header — ${payslipImportErrorText(base)}"
    }
}
