package com.rad.shifttracker.data.storage

import com.rad.shifttracker.godisnji.AnnualLeaveCalculationInput
import com.rad.shifttracker.krv.BloodDonationLeaveState
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.obracun.MonthlyGrossAdditions
import com.rad.shifttracker.obracun.MonthlyNetAdjustments
import com.rad.shifttracker.obracun.MonthlyPayBases
import com.rad.shifttracker.obracun.PayBaseSelectionMode
import com.rad.shifttracker.obracun.SeniorityIncreaseFactor
import com.rad.shifttracker.obracun.SeniorityInputMode
import com.rad.shifttracker.godisnji.VacationYearBalance
import java.time.LocalDate

internal data class ShiftRepositorySnapshot(
    val payCoefficient: Double,
    val sickPayBaseHourly: Double,
    val defaultPayBaseSelectionMode: PayBaseSelectionMode,
    val hourlyValueChanges: Map<MonthKey, Double>,
    val foodPerDay: Double,
    val transportDailyAmount: Double,
    val notificationAccessPromptSuppressed: Boolean,
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
    val knownPayBaseAnchors: Map<MonthKey, Double>,
    val annualLeaveCalculationInput: AnnualLeaveCalculationInput?,
    val annualLeaveLastCalculatedDays: Int,
    val records: List<DayRecord>,
    val foodPerDayChanges: Map<MonthKey, Double> = emptyMap(),
    /** Neto regres od meseca, sa obračuna regresa. */
    val regressNetChanges: Map<MonthKey, Double> = emptyMap(),
    /** Prevoz po danu po mesecu rada, sa obračuna prevoza (iznos se menja od meseca do meseca). */
    val transportDailyByMonth: Map<MonthKey, Double> = emptyMap(),
    /**
     * Meseci koje je korisnik izričito obrisao (tombstone). Bez ovoga bi
     * spajanje sa arhivom u oblaku vratilo obrisani najstariji mesec —
     * granica merge-a se pomeri naviše pa ga arhiva ponovo isporuči.
     */
    val deletedMonths: List<MonthKey> = emptyList(),
    /**
     * Satnice koje je korisnik izričito obrisao (tombstone). Oblak spaja satnice
     * iz arhive i trenutnog stanja da se ručni unos nikad ne izgubi, pa bez
     * ovoga obrisana satnica ne bi mogla da ostane obrisana.
     */
    val deletedHourlyValueMonths: List<MonthKey> = emptyList(),
    /** Da li kartice ispod kalendara („Plata za septembar", „Plata za avgust") uključuju prevoz. */
    val mainPaySalaryOnly: Boolean = true,
    /** Sačuvani platni listići po mesecu (JSON listića) za „Uporedi“. */
    val savedPayslips: Map<MonthKey, String> = emptyMap(),
    /** Listići koje je korisnik izričito obrisao (tombstone); oblak ih inače spaja kao uniju. */
    val deletedPayslipMonths: List<MonthKey> = emptyList()
)
