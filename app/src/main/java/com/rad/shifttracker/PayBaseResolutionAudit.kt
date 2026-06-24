package com.rad.shifttracker

internal fun payBaseResolutionDebugMap(
    repo: ShiftRepository,
    target: MonthKey,
    resolutionMonth: MonthKey,
    resolved: ResolvedPayBases
): Map<String, Any?> {
    val anchor = repo.getKnownPayBaseAnchor()
    val resolvedBases = listOf(
        resolved.vacationHourlyBase,
        resolved.paidHolidayHourlyBase,
        resolved.sickHourlyBase
    )
    val anchorApplied = anchor != null && resolvedBases.all { base ->
        base.source == PayBaseSource.KNOWN_BASE &&
            base.sourceYear == anchor.month.year &&
            base.sourceMonth == anchor.month.month
    }
    val anchorDecision = when {
        anchor == null -> "NOT_FOUND"
        repo.defaultPayBaseSelectionModeValue != PayBaseSelectionMode.KNOWN_BASE -> {
            "REJECTED_SELECTION_MODE_${repo.defaultPayBaseSelectionModeValue.name}"
        }
        anchor.month == target && anchorApplied -> "APPLIED_EXACT_SELECTED_MONTH"
        anchor.month > target -> "REJECTED_ANCHOR_AFTER_SELECTED_MONTH"
        anchorApplied -> "APPLIED_AS_KNOWN_BASE_HISTORY"
        else -> "REJECTED_KNOWN_BASE_CALCULATION_UNAVAILABLE"
    }
    return linkedMapOf(
        "selectedYear" to target.year,
        "selectedMonth" to target.month,
        "resolutionYear" to resolutionMonth.year,
        "resolutionMonth" to resolutionMonth.month,
        "selectionMode" to repo.defaultPayBaseSelectionModeValue.name,
        "sourceUsed" to payBaseSourceCategory(resolved.sickHourlyBase.source),
        "sickHourlyBase" to resolvedPayBaseAuditMap(resolved.sickHourlyBase),
        "vacationHourlyBase" to resolvedPayBaseAuditMap(resolved.vacationHourlyBase),
        "paidHolidayHourlyBase" to resolvedPayBaseAuditMap(resolved.paidHolidayHourlyBase),
        "knownPayBaseAnchorFound" to (anchor != null),
        "knownPayBaseAnchor" to anchor?.let(::knownPayBaseAnchorAuditMap),
        "knownPayBaseAnchorApplied" to anchorApplied,
        "knownPayBaseAnchorDecision" to anchorDecision
    )
}

internal fun resolvedPayBaseAuditMap(base: ResolvedPayBase): Map<String, Any?> {
    return linkedMapOf(
        "value" to base.value,
        "source" to base.source.name,
        "sourceCategory" to payBaseSourceCategory(base.source),
        "sourceYear" to base.sourceYear,
        "sourceMonth" to base.sourceMonth,
        "calculation" to base.calculation?.let { calculation ->
            linkedMapOf(
                "hourlyBase" to calculation.hourlyBase,
                "grossTotal" to calculation.grossTotal,
                "hoursTotal" to calculation.hoursTotal
            )
        }
    )
}

private fun payBaseSourceCategory(source: PayBaseSource): String {
    return when (source) {
        PayBaseSource.KNOWN_BASE -> "KNOWN_BASE"
        PayBaseSource.CURRENT_MONTH,
        PayBaseSource.LAST_KNOWN -> "MONTHLY_BASE"
        PayBaseSource.CALCULATED_PREVIOUS_12_MONTHS -> "ROLLING_12M"
        PayBaseSource.DEFAULT -> "DEFAULT"
    }
}

private fun knownPayBaseAnchorAuditMap(anchor: KnownPayBaseAnchor): Map<String, Any?> {
    return linkedMapOf(
        "year" to anchor.month.year,
        "month" to anchor.month.month,
        "grossHourlyBase" to anchor.grossHourlyBase
    )
}
