package com.rad.shifttracker.listici

import android.content.Context
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import com.rad.shifttracker.smene.MonthKey

private const val PAYSLIP_SNAPSHOT_PREFS = "payslip_snapshot_prefs"
private const val KEY_LAST_PAYSLIP = "last_payslip_json"

/**
 * Poslednji skenirani listić za prikaz u „Listi“. Čuvaju se samo brojevi koje je
 * parser izvukao (mesec, stope, časovi, iznosi) — nikad tekst PDF-a, ime, JMBG ni račun.
 */
internal class PayslipSnapshotStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PAYSLIP_SNAPSHOT_PREFS, Context.MODE_PRIVATE)

    fun load(): ParsedPayslip? = prefs.getString(KEY_LAST_PAYSLIP, null)?.let(::payslipFromJsonOrNull)

    fun save(payslip: ParsedPayslip) {
        prefs.edit().putString(KEY_LAST_PAYSLIP, payslipToJson(payslip)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LAST_PAYSLIP).apply()
    }
}

/** Prikaz ostaje na najnovijem listiću: stariji listić ne zamenjuje noviji. */
internal fun shouldReplaceLastPayslip(current: ParsedPayslip?, scanned: ParsedPayslip): Boolean {
    current ?: return true
    val currentKey = current.month.year * 12 + current.month.month
    val scannedKey = scanned.month.year * 12 + scanned.month.month
    return scannedKey >= currentKey
}

internal fun payslipToJson(payslip: ParsedPayslip): String = JSONObject().apply {
    put("year", payslip.month.year)
    put("month", payslip.month.month)
    putOpt("runLabel", payslip.runLabel)
    putOpt("fundHours", payslip.fundHours)
    putDecimal("coefficient", payslip.coefficient)
    putDecimal("hourlyValue", payslip.hourlyValue)
    putOpt("seniorityYears", payslip.seniorityYears)
    putOpt("totalSeniorityYears", payslip.totalSeniorityYears)
    putDecimal("seniorityPercent", payslip.seniorityPercent)
    putDecimal("employeeContributionsPercent", payslip.employeeContributionsPercent)
    putDecimal("taxReliefRsd", payslip.taxReliefRsd)
    putDecimal("taxPercent", payslip.taxPercent)
    putDecimal("grossTotal", payslip.grossTotal)
    putDecimal("netTotal", payslip.netTotal)
    putDecimal("mealGross", payslip.mealGross)
    putDecimal("seniorityAmount", payslip.seniorityAmount)
    putDecimal("mealDeduction", payslip.mealDeduction)
    if (payslip.benefitedSeniority) put("benefitedSeniority", true)
    put("items", JSONArray(payslip.items.values.map { item ->
        JSONObject().apply {
            put("code", item.code)
            put("name", item.name)
            put("hours", item.hours.toPlainString())
            putDecimal("percent", item.percent)
            put("amount", item.amount.toPlainString())
        }
    }))
    put("extras", JSONArray(payslip.extras.map { extra ->
        JSONObject().put("label", extra.label).put("amount", extra.amount.toPlainString())
    }))
    putDecimal("advanceNet", payslip.advanceNet)
    putDecimal("paidNet", payslip.paidNet)
    payslip.deductions?.let { deductions ->
        put("deductions", JSONArray(deductions.map { deduction ->
            JSONObject().apply {
                put("code", deduction.code)
                put("name", deduction.name)
                put("amount", deduction.amount.toPlainString())
                putDecimal("remainingDebt", deduction.remainingDebt)
                putOpt("remainingInstallments", deduction.remainingInstallments)
                putDecimal("percent", deduction.percent)
            }
        }))
    }
}.toString()

private fun parseDeductionsJson(array: JSONArray?): List<PayslipDeduction>? = array?.let {
    (0 until it.length()).mapNotNull { index ->
        val obj = it.getJSONObject(index)
        val amount = obj.optDecimal("amount")?.takeIf { value -> value.signum() > 0 } ?: return@mapNotNull null
        PayslipDeduction(
            code = obj.optString("code"),
            name = obj.optString("name"),
            amount = amount,
            remainingDebt = obj.optDecimal("remainingDebt"),
            remainingInstallments = obj.optIntOrNull("remainingInstallments"),
            percent = obj.optDecimal("percent")
        )
    }
}

internal fun payslipFromJsonOrNull(json: String): ParsedPayslip? = runCatching {
    val obj = JSONObject(json)
    val month = MonthKey(obj.getInt("year"), obj.getInt("month"))
    // Oštećen backup: mesec van opsega bi kasnije srušio obračun (YearMonth.of), pa se listić odbacuje.
    require(month.month in 1..12 && month.year in PAYSLIP_YEARS)
    val itemsArray = obj.optJSONArray("items") ?: JSONArray()
    val items = (0 until itemsArray.length()).map { index ->
        val item = itemsArray.getJSONObject(index)
        PayslipItem(
            code = item.getInt("code"),
            name = item.getString("name"),
            hours = BigDecimal(item.getString("hours")),
            percent = item.optDecimal("percent"),
            amount = BigDecimal(item.getString("amount"))
        )
    }.associateBy { it.code }
    val extrasArray = obj.optJSONArray("extras") ?: JSONArray()
    val extras = (0 until extrasArray.length()).map { index ->
        val extra = extrasArray.getJSONObject(index)
        PayslipExtraItem(extra.getString("label"), BigDecimal(extra.getString("amount")))
    }
    val runLabel = obj.optString("runLabel").takeIf { it.isNotEmpty() }
    ParsedPayslip(
        month = month,
        runLabel = runLabel,
        fundHours = obj.optIntOrNull("fundHours"),
        coefficient = obj.optDecimal("coefficient"),
        hourlyValue = obj.optDecimal("hourlyValue"),
        seniorityYears = obj.optIntOrNull("seniorityYears"),
        seniorityPercent = obj.optDecimal("seniorityPercent"),
        employeeContributionsPercent = obj.optDecimal("employeeContributionsPercent"),
        taxReliefRsd = obj.optDecimal("taxReliefRsd"),
        taxPercent = obj.optDecimal("taxPercent"),
        grossTotal = obj.optDecimal("grossTotal"),
        netTotal = obj.optDecimal("netTotal"),
        items = items,
        extras = extras,
        mealGross = obj.optDecimal("mealGross"),
        seniorityAmount = obj.optDecimal("seniorityAmount"),
        mealDeduction = obj.optDecimal("mealDeduction"),
        totalSeniorityYears = obj.optIntOrNull("totalSeniorityYears"),
        benefitedSeniority = obj.optBoolean("benefitedSeniority", false),
        deductions = parseDeductionsJson(obj.optJSONArray("deductions")),
        advanceNet = obj.optDecimal("advanceNet"),
        paidNet = obj.optDecimal("paidNet"),
        // Osnovica se ponovo računa iz sačuvanih stavki, istom funkcijom kao pri skeniranju.
        payBase = payslipPayBaseFromItems(month, runLabel, items)
    )
}.getOrNull()

private fun JSONObject.putDecimal(key: String, value: BigDecimal?) {
    if (value != null) put(key, value.toPlainString())
}

private fun JSONObject.optDecimal(key: String): BigDecimal? =
    if (has(key) && !isNull(key)) optString(key).toBigDecimalOrNull() else null

private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
