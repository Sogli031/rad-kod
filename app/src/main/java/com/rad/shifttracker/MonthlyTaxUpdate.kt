package com.rad.shifttracker

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.YearMonth

private const val MONTHLY_TAX_UPDATE_PREFS = "monthly_tax_update_prefs"
private const val KEY_LAST_PROCESSED_MONTH = "last_processed_month"

private const val TAX_PARAMETER_CACHE_PREFS = "tax_parameter_cache_prefs"
private const val KEY_CACHED_TAX_RATE = "salary_tax_rate"
private const val KEY_CACHED_TAX_ALLOWANCE = "salary_tax_allowance"
private const val KEY_CACHED_PIO = "pio_employee"
private const val KEY_CACHED_HEALTH = "health_employee"
private const val KEY_CACHED_UNEMPLOYMENT = "unemployment_employee"
private const val KEY_CACHED_AS_OF = "cached_as_of_epoch_day"
private const val TAX_CACHE_TAG = "TaxParameterCache"

fun taxUpdateMonthKey(month: YearMonth): String {
    return "%04d-%02d".format(month.year, month.monthValue)
}

fun shouldRunMonthlyTaxUpdate(lastProcessedMonth: String?, currentMonth: YearMonth): Boolean {
    return lastProcessedMonth != taxUpdateMonthKey(currentMonth)
}

class MonthlyTaxUpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        MONTHLY_TAX_UPDATE_PREFS,
        Context.MODE_PRIVATE
    )

    fun shouldRun(currentMonth: YearMonth): Boolean {
        return shouldRunMonthlyTaxUpdate(
            lastProcessedMonth = prefs.getString(KEY_LAST_PROCESSED_MONTH, null),
            currentMonth = currentMonth
        )
    }

    fun markProcessed(currentMonth: YearMonth) {
        prefs.edit()
            .putString(KEY_LAST_PROCESSED_MONTH, taxUpdateMonthKey(currentMonth))
            .commit()
    }
}

class TaxParameterCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        TAX_PARAMETER_CACHE_PREFS,
        Context.MODE_PRIVATE
    )

    fun save(parameters: TaxParameters, asOf: LocalDate) {
        prefs.edit()
            .putFloat(KEY_CACHED_TAX_RATE, parameters.salaryTaxRatePercent.toFloat())
            .putFloat(KEY_CACHED_TAX_ALLOWANCE, parameters.salaryTaxAllowanceRsd.toFloat())
            .putFloat(KEY_CACHED_PIO, parameters.pioEmployeePercent.toFloat())
            .putFloat(KEY_CACHED_HEALTH, parameters.healthEmployeePercent.toFloat())
            .putFloat(KEY_CACHED_UNEMPLOYMENT, parameters.unemploymentEmployeePercent.toFloat())
            .putLong(KEY_CACHED_AS_OF, asOf.toEpochDay())
            .apply()
    }

    fun load(): TaxParameters? {
        if (!prefs.contains(KEY_CACHED_TAX_RATE)) return null
        return TaxParameters(
            salaryTaxRatePercent = prefs.getFloat(KEY_CACHED_TAX_RATE, 0f).toDouble(),
            salaryTaxAllowanceRsd = prefs.getFloat(KEY_CACHED_TAX_ALLOWANCE, 0f).toDouble(),
            pioEmployeePercent = prefs.getFloat(KEY_CACHED_PIO, 0f).toDouble(),
            healthEmployeePercent = prefs.getFloat(KEY_CACHED_HEALTH, 0f).toDouble(),
            unemploymentEmployeePercent = prefs.getFloat(KEY_CACHED_UNEMPLOYMENT, 0f).toDouble()
        )
    }
}

suspend fun fetchAndApplyLatestTaxParameters(
    repo: ShiftRepository,
    updater: TaxParameterUpdater = TaxParameterUpdater(),
    asOf: LocalDate = LocalDate.now(),
    cache: TaxParameterCache? = null
): TaxParameters {
    val parameters = try {
        updater.fetchLatest(asOf).also { fresh ->
            cache?.save(fresh, asOf)
        }
    } catch (e: Exception) {
        val cached = cache?.load()
        if (cached != null) {
            Log.w(TAX_CACHE_TAG, "Skidanje poreskih parametara nije uspelo, koristim keširane vrednosti.", e)
            cached
        } else {
            throw e
        }
    }
    applyTaxParameters(repo, parameters)
    return parameters
}

fun applyTaxParameters(repo: ShiftRepository, parameters: TaxParameters) {
    repo.employeeContributionsPercentValue = parameters.employeeContributionsPercent
    repo.taxReliefValue = parameters.salaryTaxAllowanceRsd
    repo.taxPercentValue = parameters.salaryTaxRatePercent
}

fun taxUpdateSuccessMessage(parameters: TaxParameters): String {
    return "Uspešno ažurirano: doprinosi ${formatRateInput(parameters.employeeContributionsPercent)}%, " +
        "olakšica ${formatNumber(parameters.salaryTaxAllowanceRsd)} ${CONFIG.pay.currency}, " +
        "porez ${formatRateInput(parameters.salaryTaxRatePercent)}%."
}
