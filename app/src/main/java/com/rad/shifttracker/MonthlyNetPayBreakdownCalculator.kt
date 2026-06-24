package com.rad.shifttracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

private const val DEFAULT_DAILY_FUND_HOURS = 8
private const val DEFAULT_REGRESS_NET = 20_000.0
private val REGRESS_MONTHS = setOf(2, 5, 8, 11)

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
    val mealAllowanceNet = summary.foodAllowance.sanitizeMoney()
    val mealSpentNet = mealAllowanceNet
    val mealCoveredNet = mealAllowanceNet
    val grossMealAddition = if (safeAdditions.mealGross > 0.0) {
        safeAdditions.mealGross
    } else {
        calculateGrossFromNet(mealCoveredNet, netFactor)
    }
    val hasPayrollBase = grossBaseBeforeSeniority > 0.0 ||
        grossSeniorityBonus > 0.0 ||
        mealAllowanceNet > 0.0
    val hasExplicitRegress = safeAdditions.regressGross > 0.0
    val netRegressAddition = when {
        !includeRegress -> 0.0
        hasExplicitRegress -> safeAdditions.regressGross * netFactor
        summary.month in REGRESS_MONTHS && hasPayrollBase -> DEFAULT_REGRESS_NET
        summary.month in REGRESS_MONTHS -> 0.0
        else -> safeAdditions.regressGross * netFactor
    }
    val grossRegressAddition = when {
        !includeRegress -> 0.0
        hasExplicitRegress -> safeAdditions.regressGross
        summary.month in REGRESS_MONTHS && hasPayrollBase -> calculateGrossFromNet(DEFAULT_REGRESS_NET, netFactor)
        summary.month in REGRESS_MONTHS -> 0.0
        else -> safeAdditions.regressGross
    }
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
    val grossAdditionsTotal = grossMealAddition + grossRegressAddition + grossBonusAddition
    val grossTotal = grossBaseBeforeSeniority + grossSeniorityBonus + grossAdditionsTotal

    if (grossTotal <= 0.0) {
        val netHourlyBase = calculateInformativeNetHourlyBase(
            grossHourlyRate = safeGrossHourlyRate,
            monthlyFundHours = safeFundHours,
            contributionsPercent = safeContributionsPercent,
            taxRelief = safeTaxRelief,
            taxPercent = safeTaxPercent
        )
        return MonthlyNetPayBreakdown(
            grossHourlyRate = safeGrossHourlyRate,
            monthlyFundHours = safeFundHours,
            informativeNetHourlyBase = netHourlyBase,
            grossBaseBeforeSeniority = 0.0,
            grossSeniorityBonus = 0.0,
            grossMealAddition = 0.0,
            grossRegressAddition = 0.0,
            grossBonusAddition = 0.0,
            grossAdditionsTotal = 0.0,
            grossTotal = 0.0,
            employeeContributions = 0.0,
            taxRelief = safeTaxRelief,
            taxableBase = 0.0,
            tax = 0.0,
            netPayBeforeDeductions = 0.0,
            netSeniorityBonus = 0.0,
            mealAllowanceNet = 0.0,
            mealSpentNet = 0.0,
            mealCoveredNet = 0.0,
            netRegressAddition = 0.0,
            netBonusAddition = 0.0,
            unionDuesNet = 0.0,
            netDeductionsTotal = 0.0,
            displayPay = 0.0
        )
    }

    val employeeContributions = grossTotal * safeContributionsPercent / 100.0
    val taxableBase = calculateTaxableBase(grossTotal, safeTaxRelief)
    val tax = taxableBase * safeTaxPercent / 100.0
    val netPayBeforeDeductions = max(0.0, grossTotal - employeeContributions - tax)
    val unionDuesNet = calculateUnionDuesNet(netPayBeforeDeductions, unionMember, unionDuesRate)
    val netDeductionsTotal = mealSpentNet + unionDuesNet
    val netWithoutSeniority = calculateNetFromGross(
        grossTotal = grossBaseBeforeSeniority + grossAdditionsTotal,
        contributionsPercent = safeContributionsPercent,
        taxRelief = safeTaxRelief,
        taxPercent = safeTaxPercent
    )
    val netSeniorityBonus = max(0.0, netPayBeforeDeductions - netWithoutSeniority)

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
        grossRegressAddition = grossRegressAddition,
        grossBonusAddition = grossBonusAddition,
        grossAdditionsTotal = grossAdditionsTotal,
        grossTotal = grossTotal,
        employeeContributions = employeeContributions,
        taxRelief = safeTaxRelief,
        taxableBase = taxableBase,
        tax = tax,
        netPayBeforeDeductions = netPayBeforeDeductions,
        netSeniorityBonus = netSeniorityBonus,
        mealAllowanceNet = mealAllowanceNet,
        mealSpentNet = mealSpentNet,
        mealCoveredNet = mealCoveredNet,
        netRegressAddition = netRegressAddition,
        netBonusAddition = netBonusAddition,
        unionDuesNet = unionDuesNet,
        netDeductionsTotal = netDeductionsTotal,
        displayPay = max(0.0, netPayBeforeDeductions - netDeductionsTotal)
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

private fun roundMoneyHalfUp(value: Double): Double {
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
    val employeeContributions = grossTotal * contributionsPercent / 100.0
    val taxableBase = calculateTaxableBase(grossTotal, taxRelief)
    val tax = taxableBase * taxPercent / 100.0
    return max(0.0, grossTotal - employeeContributions - tax)
}

private fun calculateTaxableBase(grossTotal: Double, taxRelief: Double): Double {
    return max(0.0, grossTotal - taxRelief)
}

private fun calculateGrossFromNet(net: Double, netFactor: Double): Double {
    if (net <= 0.0 || netFactor <= 0.0) return 0.0
    return net / netFactor
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
