package com.rad.shifttracker.data.storage

import com.rad.shifttracker.AnnualLeaveCalculationInput
import com.rad.shifttracker.BloodDonationLeaveState
import com.rad.shifttracker.BrigadeType
import com.rad.shifttracker.DayRecord
import com.rad.shifttracker.KnownPayBaseAnchor
import com.rad.shifttracker.MonthKey
import com.rad.shifttracker.MonthlyGrossAdditions
import com.rad.shifttracker.MonthlyNetAdjustments
import com.rad.shifttracker.MonthlyPayBases
import com.rad.shifttracker.PayBaseSelectionMode
import com.rad.shifttracker.SeniorityIncreaseFactor
import com.rad.shifttracker.SeniorityInputMode
import com.rad.shifttracker.VacationYearBalance
import java.time.LocalDate

internal data class ShiftRepositorySnapshot(
    val payCoefficient: Double,
    val sickPayBaseHourly: Double,
    val defaultPayBaseSelectionMode: PayBaseSelectionMode,
    val hourlyValueChanges: Map<MonthKey, Double>,
    val foodPerDay: Double,
    val transportDailyAmount: Double,
    val creditDeductionEnabled: Boolean,
    val monthlyCreditDeductionAmount: Double,
    val foodReceiptReminderEnabled: Boolean,
    val lastBloodDonationDate: LocalDate?,
    val bloodDonationCount: Int,
    val bloodDonationHistory: List<LocalDate>,
    val bloodDonationLeaves: List<BloodDonationLeaveState>,
    val initialCompBalance: Int,
    val initialCompBalanceMinutes: Int,
    val minuliRadStartYear: Int,
    val minuliRadStartMonth: Int,
    val seniorityInputMode: SeniorityInputMode,
    val minuliRadBenefitEnabled: Boolean,
    val minuliRadIncreaseFactor: SeniorityIncreaseFactor,
    val minuliRadLastPayslipYear: Int,
    val minuliRadLastPayslipMonth: Int,
    val minuliRadPayslipPercent: Double,
    val brigadeType: BrigadeType,
    val initialVacationBalance: Double,
    val vacationYearBalances: Map<Int, VacationYearBalance>,
    val employeeContributionsPercent: Double,
    val taxRelief: Double,
    val taxPercent: Double,
    val difficultWorkConditionsPercent: Double,
    val monthlyPayBases: Map<MonthKey, MonthlyPayBases>,
    val monthlyGrossAdditions: Map<MonthKey, MonthlyGrossAdditions>,
    val monthlyNetAdjustments: Map<MonthKey, MonthlyNetAdjustments>,
    val knownPayBaseAnchor: KnownPayBaseAnchor?,
    val annualLeaveCalculationInput: AnnualLeaveCalculationInput?,
    val annualLeaveLastCalculatedDays: Int,
    val records: List<DayRecord>
)
