package com.rad.shifttracker

data class MonthlyOvertimeNetImpact(
    val year: Int,
    val month: Int,
    val overtimeHours: Int,
    val currentNetPay: Double,
    val withoutOvertimeNetPay: Double,
    val paidOvertimeNetDifference: Double,
    val compTimeRecoveryNetEffect: Double,
    val deficitNetLoss: Double,
    val netDifference: Double
)
