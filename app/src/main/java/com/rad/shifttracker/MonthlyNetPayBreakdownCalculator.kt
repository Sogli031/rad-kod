package com.rad.shifttracker

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

private const val DEFAULT_DAILY_FUND_HOURS = 8
private const val DEFAULT_REGRESS_NET = 20_000.0
private val REGRESS_MONTHS = setOf(2, 5, 8, 11)
private val PAYROLL_MATH_CONTEXT = MathContext.DECIMAL128
private val HUNDRED_DECIMAL = BigDecimal.valueOf(100L)

fun calculateMonthlyFundHours(
    year: Int,
    month: Int,
    defaultHours: Int = DEFAULT_DAILY_FUND_HOURS
): Int {
    if (year <= 0 || month !in 1..12 || defaultHours <= 0) return 0
    val yearMonth = try {
        YearMonth.of(year, month)
    } catch (_: DateTimeException) {
        return 0
    }
    var workdays = 0
    for (day in 1..yearMonth.lengthOfMonth()) {
        val weekday = Weekday.from(LocalDate.of(year, month, day))
        if (weekday.isWorkday) {
            workdays += 1
        }
    }
    return workdays * defaultHours
}

fun calculateInformativeNetHourlyBase(
    grossHourlyRate: Double,
    monthlyFundHours: Int,
    contributionsPercent: Double,
    taxRelief: Double,
    taxPercent: Double
): Double {
    if (monthlyFundHours <= 0) return 0.0
    val safeGrossHourlyRate = grossHourlyRate.sanitizeMoney()
    if (safeGrossHourlyRate <= 0.0) return 0.0

    val grossMonthBase = safeGrossHourlyRate * monthlyFundHours
    val contributions = grossMonthBase * contributionsPercent.sanitizePercent() / 100.0
    val taxableBase = calculateTaxableBase(grossMonthBase, taxRelief.sanitizeMoney())
    val tax = taxableBase * taxPercent.sanitizePercent() / 100.0
    val netMonthBase = grossMonthBase - contributions - tax
    return max(0.0, netMonthBase / monthlyFundHours)
}

fun calculateMonthlyNetPayBreakdown(
    summary: MonthSummary,
    grossHourlyRate: Double,
    monthlyFundHours: Int,
    contributionsPercent: Double,
    taxRelief: Double,
    taxPercent: Double,
    grossAdditions: MonthlyGrossAdditions = MonthlyGrossAdditions(summary.year, summary.month),
    includeRegress: Boolean = true,
    unionMember: Boolean = false,
    unionDuesRate: Double = 0.0
): MonthlyNetPayBreakdown {
    val safeGrossHourlyRate = grossHourlyRate.sanitizeMoney()
    val safeFundHours = monthlyFundHours.coerceAtLeast(0)
    val safeContributionsPercent = contributionsPercent.sanitizePercent()
    val safeTaxRelief = taxRelief.sanitizeMoney()
    val safeTaxPercent = taxPercent.sanitizePercent()
    val safeAdditions = grossAdditions.sanitizedFor(summary.year, summary.month)
    val netFactor = calculateMarginalNetFactor(
        contributionsPercent = safeContributionsPercent,
        taxPercent = safeTaxPercent
    )

    val grossBaseBeforeSeniority = summary.totalPay.sanitizeMoney()
    val grossSeniorityBonus = summary.minuliRadBonus.sanitizeMoney()
    val resolvedMeal = resolveMonthlyMeal(summary, safeAdditions, netFactor)
    val mealEntitlementCalculation = resolvedMeal.entitlementCalculation
    val mealResolution = resolvedMeal.resolution
    val grossMealAddition = mealResolution.grossApplied.toDouble()
    val hasPayrollBase = grossBaseBeforeSeniority > 0.0 ||
        grossSeniorityBonus > 0.0 ||
        mealResolution.entitlementNet.signum() > 0
    val regress = calculateRegressAddition(
        month = summary.month,
        additions = safeAdditions,
        netFactor = netFactor,
        includeRegress = includeRegress,
        hasPayrollBase = hasPayrollBase
    )
    val netBonusAddition = if (safeAdditions.bonusNet > 0.0) {
        safeAdditions.bonusNet
    } else {
        safeAdditions.bonusGross * netFactor
    }
    val grossBonusAddition = if (safeAdditions.bonusNet > 0.0) {
        calculateGrossFromNet(safeAdditions.bonusNet, netFactor)
    } else {
        safeAdditions.bonusGross
    }
    val grossAdditionsTotal = grossMealAddition + regress.gross + grossBonusAddition
    val absencePenaltyGross = safeAdditions.absencePenaltyGross
    val grossTotalDecimal = (
        grossBaseBeforeSeniority.toPayrollDecimal() +
            grossSeniorityBonus.toPayrollDecimal() +
            grossAdditionsTotal.toPayrollDecimal() -
            absencePenaltyGross
        ).max(BigDecimal.ZERO)
    val grossTotal = grossTotalDecimal.toDouble()

    if (grossTotal <= 0.0) {
        return emptyMonthlyNetPayBreakdown(
            grossHourlyRate = safeGrossHourlyRate,
            fundHours = safeFundHours,
            contributionsPercent = safeContributionsPercent,
            taxRelief = safeTaxRelief,
            taxPercent = safeTaxPercent,
            resolution = mealResolution,
            mealEntitlementCalculation = mealEntitlementCalculation,
            absencePenaltyGross = absencePenaltyGross
        )
    }

    val employeeContributionsDecimal = grossTotalDecimal.multiply(
        safeContributionsPercent.toPayrollDecimal().divide(HUNDRED_DECIMAL, PAYROLL_MATH_CONTEXT),
        PAYROLL_MATH_CONTEXT
    )
    val taxableBaseDecimal = (grossTotalDecimal - safeTaxRelief.toPayrollDecimal()).max(BigDecimal.ZERO)
    val taxDecimal = taxableBaseDecimal.multiply(
        safeTaxPercent.toPayrollDecimal().divide(HUNDRED_DECIMAL, PAYROLL_MATH_CONTEXT),
        PAYROLL_MATH_CONTEXT
    )
    val netPayBeforeDeductionsDecimal = (
        grossTotalDecimal - employeeContributionsDecimal - taxDecimal
        ).max(BigDecimal.ZERO)
    val employeeContributions = employeeContributionsDecimal.toDouble()
    val taxableBase = taxableBaseDecimal.toDouble()
    val tax = taxDecimal.toDouble()
    val netPayBeforeDeductions = netPayBeforeDeductionsDecimal.toDouble()
    val unionDuesNet = calculateUnionDuesNet(netPayBeforeDeductions, unionMember, unionDuesRate)
    val netDeductionsTotal = mealResolution.netDeduction.toDouble() + unionDuesNet
    val netWithoutSeniority = calculateNetFromGross(
        grossTotal = (
            grossBaseBeforeSeniority +
                grossAdditionsTotal -
                absencePenaltyGross.toDouble()
            ).coerceAtLeast(0.0),
        contributionsPercent = safeContributionsPercent,
        taxRelief = safeTaxRelief,
        taxPercent = safeTaxPercent
    )
    val netSeniorityBonus = roundMoneyHalfUp(
        max(0.0, netPayBeforeDeductions - netWithoutSeniority)
    )

    return MonthlyNetPayBreakdown(
        grossHourlyRate = safeGrossHourlyRate,
        monthlyFundHours = safeFundHours,
        informativeNetHourlyBase = calculateInformativeNetHourlyBase(
            grossHourlyRate = safeGrossHourlyRate,
            monthlyFundHours = safeFundHours,
            contributionsPercent = safeContributionsPercent,
            taxRelief = safeTaxRelief,
            taxPercent = safeTaxPercent
        ),
        grossBaseBeforeSeniority = grossBaseBeforeSeniority,
        grossSeniorityBonus = grossSeniorityBonus,
        grossMealAddition = grossMealAddition,
        mealGrossSource = MealGrossSource.AUTO_FROM_NET,
        grossRegressAddition = regress.gross,
        grossBonusAddition = grossBonusAddition,
        grossAdditionsTotal = grossAdditionsTotal,
        absencePenaltyGross = absencePenaltyGross,
        grossTotal = grossTotal,
        employeeContributions = employeeContributions,
        taxRelief = safeTaxRelief,
        taxableBase = taxableBase,
        tax = tax,
        netPayBeforeDeductions = netPayBeforeDeductions,
        netSeniorityBonus = netSeniorityBonus,
        mealAllowanceNet = mealResolution.entitlementNet.toDouble(),
        mealEntitlementNetOverride = mealResolution.entitlementNetOverride,
        mealEntitlementSource = mealResolution.entitlementSource,
        manualMealSpentNet = mealResolution.manualSpentNet?.toDouble(),
        mealSpentNetApplied = mealResolution.spentApplied.toDouble(),
        mealSpentSource = mealResolution.spentSource,
        mealCoveredNet = mealResolution.spentApplied.toDouble(),
        mealNetDeduction = mealResolution.netDeduction.toDouble(),
        mealUnusedNet = mealResolution.unusedNet.toDouble(),
        mealSpendingDefaultedToEntitlement = mealResolution.spendingDefaultedToEntitlement,
        mealEntitlementCalculation = mealEntitlementCalculation,
        netRegressAddition = regress.net,
        netBonusAddition = netBonusAddition,
        unionDuesNet = unionDuesNet,
        netDeductionsTotal = netDeductionsTotal,
        displayPay = max(0.0, netPayBeforeDeductions - netDeductionsTotal)
    )
}

private data class MealCalculationResolution(
    val entitlementNet: BigDecimal,
    val entitlementNetOverride: BigDecimal?,
    val entitlementSource: MealEntitlementSource,
    val manualSpentNet: BigDecimal?,
    val spentApplied: BigDecimal,
    val spentSource: MealSpentSource,
    val grossApplied: BigDecimal,
    val netDeduction: BigDecimal,
    val unusedNet: BigDecimal,
    val spendingDefaultedToEntitlement: Boolean
)

/**
 * Regres se knjiži tromesečno kao poznat neto iznos, pa se bruto dobija „grossup"-om.
 * Ako je unet eksplicitan bruto, on ima prednost.
 */
private data class RegressAddition(val net: Double, val gross: Double)

private fun calculateRegressAddition(
    month: Int,
    additions: MonthlyGrossAdditions,
    netFactor: Double,
    includeRegress: Boolean,
    hasPayrollBase: Boolean
): RegressAddition {
    val explicitGross = additions.regressGross
    val fromGross = RegressAddition(net = explicitGross * netFactor, gross = explicitGross)
    return when {
        !includeRegress -> RegressAddition(0.0, 0.0)
        explicitGross > 0.0 -> fromGross
        month !in REGRESS_MONTHS -> fromGross
        hasPayrollBase -> RegressAddition(
            net = DEFAULT_REGRESS_NET,
            gross = calculateGrossFromNet(DEFAULT_REGRESS_NET, netFactor)
        )
        else -> RegressAddition(0.0, 0.0)
    }
}

private data class ResolvedMonthlyMeal(
    val entitlementCalculation: MealEntitlementCalculation,
    val resolution: MealCalculationResolution
)

private fun resolveMonthlyMeal(
    summary: MonthSummary,
    additions: MonthlyGrossAdditions,
    netFactor: Double
): ResolvedMonthlyMeal {
    val entitlement = summary.mealEntitlementCalculation
        ?: MealEntitlementCalculation.fromNet(MonthKey(summary.year, summary.month), summary.foodAllowance)
    return ResolvedMonthlyMeal(
        entitlementCalculation = entitlement,
        resolution = resolveMealCalculation(
            manualMealSpentNet = additions.mealSpentNet,
            entitlementNetOverride = additions.mealEntitlementNetOverride,
            estimatedEntitlementNet = entitlement.mealEntitlementNet,
            netFactor = netFactor
        )
    )
}

private fun resolveMealCalculation(
    manualMealSpentNet: Double?,
    entitlementNetOverride: BigDecimal?,
    estimatedEntitlementNet: BigDecimal,
    netFactor: Double
): MealCalculationResolution {
    val manualSpent = manualMealSpentNet?.toMealMoney()
    val confirmedOverride = entitlementNetOverride?.toMealMoney()
    val spendingDefaulted = manualSpent == null
    val estimatedSpending = estimatedEntitlementNet.toMealMoney()
    val spentApplied = manualSpent ?: confirmedOverride ?: estimatedSpending
    val entitlementApplied = confirmedOverride ?: spentApplied
    val entitlementSource = if (confirmedOverride != null) {
        MealEntitlementSource.CONFIRMED_MONTHLY_OVERRIDE
    } else {
        MealEntitlementSource.SAFE_DEFAULT_EQUALS_SPENDING
    }
    return MealCalculationResolution(
        entitlementNet = entitlementApplied,
        entitlementNetOverride = confirmedOverride,
        entitlementSource = entitlementSource,
        manualSpentNet = manualSpent,
        spentApplied = spentApplied,
        spentSource = if (spendingDefaulted) MealSpentSource.ENTITLEMENT_ASSUMED else MealSpentSource.MANUAL,
        grossApplied = calculateMealGrossFromNet(
            net = entitlementApplied,
            netFactor = netFactor
        ),
        netDeduction = spentApplied,
        unusedNet = if (confirmedOverride != null) {
            (entitlementApplied - spentApplied).max(BigDecimal.ZERO).toMealMoney()
        } else {
            BigDecimal.ZERO.toMealMoney()
        },
        spendingDefaultedToEntitlement = spendingDefaulted
    )
}

private fun emptyMonthlyNetPayBreakdown(
    grossHourlyRate: Double,
    fundHours: Int,
    contributionsPercent: Double,
    taxRelief: Double,
    taxPercent: Double,
    resolution: MealCalculationResolution,
    mealEntitlementCalculation: MealEntitlementCalculation,
    absencePenaltyGross: BigDecimal
): MonthlyNetPayBreakdown {
    return MonthlyNetPayBreakdown(
        grossHourlyRate = grossHourlyRate,
        monthlyFundHours = fundHours,
        informativeNetHourlyBase = calculateInformativeNetHourlyBase(
            grossHourlyRate,
            fundHours,
            contributionsPercent,
            taxRelief,
            taxPercent
        ),
        grossBaseBeforeSeniority = 0.0,
        grossSeniorityBonus = 0.0,
        grossMealAddition = 0.0,
        mealGrossSource = MealGrossSource.AUTO_FROM_NET,
        grossRegressAddition = 0.0,
        grossBonusAddition = 0.0,
        grossAdditionsTotal = 0.0,
        absencePenaltyGross = absencePenaltyGross,
        grossTotal = 0.0,
        employeeContributions = 0.0,
        taxRelief = taxRelief,
        taxableBase = 0.0,
        tax = 0.0,
        netPayBeforeDeductions = 0.0,
        netSeniorityBonus = 0.0,
        mealAllowanceNet = resolution.entitlementNet.toDouble(),
        mealEntitlementNetOverride = resolution.entitlementNetOverride,
        mealEntitlementSource = resolution.entitlementSource,
        manualMealSpentNet = resolution.manualSpentNet?.toDouble(),
        mealSpentNetApplied = resolution.spentApplied.toDouble(),
        mealSpentSource = resolution.spentSource,
        mealCoveredNet = resolution.spentApplied.toDouble(),
        mealNetDeduction = resolution.netDeduction.toDouble(),
        mealUnusedNet = resolution.unusedNet.toDouble(),
        mealSpendingDefaultedToEntitlement = resolution.spendingDefaultedToEntitlement,
        mealEntitlementCalculation = mealEntitlementCalculation,
        netRegressAddition = 0.0,
        netBonusAddition = 0.0,
        unionDuesNet = 0.0,
        netDeductionsTotal = resolution.netDeduction.toDouble(),
        displayPay = 0.0
    )
}

fun calculateUnionDuesNet(
    netPayBeforeDeductions: Double,
    unionMember: Boolean,
    unionDuesRate: Double
): Double {
    if (!unionMember) return 0.0
    val safeNet = netPayBeforeDeductions.sanitizeMoney()
    val safeRate = if (unionDuesRate.isFinite() && unionDuesRate > 0.0) unionDuesRate.coerceAtMost(1.0) else 0.0
    if (safeNet <= 0.0 || safeRate <= 0.0) return 0.0
    return roundMoneyHalfUp(safeNet * safeRate)
}

internal fun roundMoneyHalfUp(value: Double): Double {
    if (!value.isFinite()) return 0.0
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toDouble()
}

private fun calculateNetFromGross(
    grossTotal: Double,
    contributionsPercent: Double,
    taxRelief: Double,
    taxPercent: Double
): Double {
    if (grossTotal <= 0.0) return 0.0
    val gross = grossTotal.toPayrollDecimal()
    val contributions = gross.multiply(
        contributionsPercent.toPayrollDecimal().divide(HUNDRED_DECIMAL, PAYROLL_MATH_CONTEXT),
        PAYROLL_MATH_CONTEXT
    )
    val taxableBase = (gross - taxRelief.toPayrollDecimal()).max(BigDecimal.ZERO)
    val tax = taxableBase.multiply(
        taxPercent.toPayrollDecimal().divide(HUNDRED_DECIMAL, PAYROLL_MATH_CONTEXT),
        PAYROLL_MATH_CONTEXT
    )
    return (gross - contributions - tax).max(BigDecimal.ZERO).toDouble()
}

private fun calculateTaxableBase(grossTotal: Double, taxRelief: Double): Double {
    return max(0.0, grossTotal - taxRelief)
}

private fun calculateGrossFromNet(net: Double, netFactor: Double): Double {
    if (net <= 0.0 || netFactor <= 0.0) return 0.0
    return net.toPayrollDecimal()
        .divide(netFactor.toPayrollDecimal(), PAYROLL_MATH_CONTEXT)
        .toDouble()
}

private fun calculateMealGrossFromNet(
    net: BigDecimal,
    netFactor: Double
): BigDecimal {
    if (net.signum() <= 0 || netFactor <= 0.0) return BigDecimal.ZERO.setScale(2)
    return net
        .divide(netFactor.toPayrollDecimal(), PAYROLL_MATH_CONTEXT)
        .setScale(0, RoundingMode.HALF_UP)
        .setScale(2)
}

private fun Double.toPayrollDecimal(): BigDecimal {
    return if (isFinite()) BigDecimal.valueOf(this) else BigDecimal.ZERO
}

private fun calculateMarginalNetFactor(
    contributionsPercent: Double,
    taxPercent: Double
): Double {
    return max(0.0, 1.0 - contributionsPercent / 100.0 - taxPercent / 100.0)
}

internal fun Double.sanitizeMoney(): Double {
    return if (isFinite() && this > 0.0) this else 0.0
}

internal fun Double.sanitizePercent(): Double {
    return if (isFinite() && this > 0.0) this else 0.0
}

internal fun Double?.isPositiveFinite(): Boolean {
    return this != null && isFinite() && this > 0.0
}
