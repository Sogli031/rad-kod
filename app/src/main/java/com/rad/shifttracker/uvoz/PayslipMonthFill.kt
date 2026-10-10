package com.rad.shifttracker.uvoz

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import com.rad.shifttracker.repo.BatchRecord
import com.rad.shifttracker.repo.ShiftRepository
import com.rad.shifttracker.repo.brigadeTypeValue
import com.rad.shifttracker.repo.exportCloudBackupJson
import com.rad.shifttracker.repo.importFromJson
import com.rad.shifttracker.repo.setMonthlyAbsencePenaltyGross
import com.rad.shifttracker.smene.BrigadeType
import com.rad.shifttracker.smene.DayRecordSource
import com.rad.shifttracker.smene.MonthKey
import com.rad.shifttracker.smene.Shift
import com.rad.shifttracker.smene.isSerbianHoliday
import com.rad.shifttracker.smene.monthYearText
import com.rad.shifttracker.repo.bloodDonationHistory
import com.rad.shifttracker.repo.getNetPayBreakdown
import com.rad.shifttracker.repo.getSavedPayslip
import com.rad.shifttracker.repo.getSavedPayslipMonths
import com.rad.shifttracker.repo.getScheduledShiftForDate
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.listici.FillDiagnostics
import com.rad.shifttracker.listici.FillHints
import com.rad.shifttracker.listici.ParsedPayslip
import com.rad.shifttracker.listici.PayslipItem
import com.rad.shifttracker.listici.isSickLeave
import com.rad.shifttracker.listici.runPayslipSafely
import com.rad.shifttracker.listici.solveMonthFill
import com.rad.shifttracker.listici.unknownHourItems
import com.rad.shifttracker.listici.ABSENCE_ROUNDING_EXTRA_LABEL
import com.rad.shifttracker.listici.FillCalendar
import com.rad.shifttracker.listici.FillDay
import com.rad.shifttracker.listici.FillDayKind
import com.rad.shifttracker.listici.FillLeaveBlock
import com.rad.shifttracker.listici.FillSlot
import com.rad.shifttracker.listici.FillTargets
import com.rad.shifttracker.listici.PayslipCheckRow

internal sealed interface MonthFillResult {
    data class Success(
        val month: MonthKey,
        val days: List<FillDay>,
        val reconciliation: PayslipReconciliation,
        val existingRecordCount: Int,
        val absenceRoundingGross: BigDecimal = BigDecimal.ZERO
    ) : MonthFillResult

    data class Failure(val message: String) : MonthFillResult
}

private const val DAY_HOURS = 8
internal fun planMonthFromPayslip(repo: ShiftRepository, payslip: ParsedPayslip): MonthFillResult {
    unknownHourItemsText(payslip)?.let { unknown ->
        return MonthFillResult.Failure("Lista ima stavke koje popunjavanje još ne ume da rasporedi: $unknown. Mesec nije menjan.")
    }
    val targets = fillTargetsFrom(payslip) ?: return MonthFillResult.Failure(
        "Časovi na listi nisu u celim satima ili celim danima, pa ih ne mogu tačno rasporediti."
    )
    fundMismatchText(repo, payslip)?.let { return MonthFillResult.Failure(it) }
    if (repo.brigadeTypeValue == BrigadeType.CETVOROBRIGADA) return planFourBrigadeMonth(repo, payslip, targets)
    val calendar = buildFillCalendar(
        month = payslip.month,
        scheduledShift = { date -> repo.getScheduledShiftForDate(date) },
        holidayOverrideOf = { date -> repo.get(date.year, date.monthValue, date.dayOfMonth)?.holidayOverride }
    ) ?: return MonthFillResult.Failure(
        "Nije prepoznat tvoj raspored smena. Upiši bar dve nedelje smena (ili koristi „Popuni“), pa pokušaj ponovo."
    )
    val scratch = verificationCopy(repo)
    var verified: PayslipReconciliation? = null
    var closest: List<PayslipCheckRow>? = null
    val diagnostics = FillDiagnostics()
    val days = solveMonthFill(calendar, targets, fillHints(repo, payslip), diagnostics) { candidate ->
        val check = verifyMonthFill(scratch, payslip, targets, candidate)
        val mismatches = check.rows.filter { it.isHours && !it.matches }
        if (mismatches.isEmpty()) {
            verified = check
            true
        } else {
            if (closest?.let { mismatches.size < it.size } != false) closest = mismatches
            false
        }
    } ?: return MonthFillResult.Failure(fillFailureText(payslip.month, diagnostics, closest))
    return MonthFillResult.Success(
        month = payslip.month,
        days = days,
        reconciliation = verified ?: verifyMonthFill(scratch, payslip, targets, days),
        existingRecordCount = userEnteredDayCount(repo, payslip.month),
        absenceRoundingGross = targets.absenceRoundingGross
    )
}

/** Fond sati na listi mora da bude isti kao fond meseca u aplikaciji; inače nijedan raspored ne može da se slaže. */
private fun fundMismatchText(repo: ShiftRepository, payslip: ParsedPayslip): String? {
    val slipFund = payslip.fundHours ?: return null
    val appFund = repo.getNetPayBreakdown(payslip.month.year, payslip.month.month).monthlyFundHours
    if (slipFund == appFund) return null
    return "Fond na listi ($slipFund h) nije isti kao fond meseca u aplikaciji ($appFund h) za " +
        "${monthYearText(payslip.month)}. Mesec nije menjan."
}

/** Poruka kad raspored nije nađen: šta je najbliže i koji deo liste ne može da se složi. */
internal fun fillFailureText(month: MonthKey, diagnostics: FillDiagnostics, closest: List<PayslipCheckRow>?): String {
    val head = "Nisam našao raspored po tvom šablonu smena koji se tačno slaže sa listom za ${monthYearText(month)}."
    val reason = when {
        closest != null -> " Najbliži se ne slaže u: " +
            closest.take(MAX_LISTED_MISMATCHES).joinToString { "${it.label} ${it.appText} umesto ${it.payslipText}" } + "."
        diagnostics.tooManySpecialDays -> " Mesec ima previše radnih vikenda i praznika za tačnu pretragu."
        diagnostics.noSpecialCombination ->
            " Rad na praznik, nedelja i plaćeni praznik sa liste ne slažu se sa kalendarom meseca."
        diagnostics.noResidualCandidate -> " Režija, noćni ili prekovremeni sa liste ne slažu se sa tvojim šablonom smena."
        else -> ""
    }
    return "$head$reason Mesec nije menjan."
}

private const val MAX_LISTED_MISMATCHES = 3

/**
 * Gde blok odsustva verovatno stoji: bolovanje i godišnji se nastavljaju iz/u susedni mesec kad i
 * njegova lista ima isto odsustvo, a plaćeno odsustvo za krv počinje na dan davanja (ili sledeći radni dan).
 */
internal fun fillHints(repo: ShiftRepository, payslip: ParsedPayslip): FillHints {
    val month = YearMonth.of(payslip.month.year, payslip.month.month)
    fun leaveKinds(other: YearMonth): Set<FillDayKind> =
        repo.getSavedPayslip(other.year, other.monthValue)
            ?.let(::fillTargetsFrom)
            ?.blocks
            ?.map { it.kind }
            ?.filter { it == FillDayKind.VACATION || it == FillDayKind.SICK }
            ?.toSet()
            .orEmpty()
    return FillHints(
        continuesFromPrevious = leaveKinds(month.minusMonths(1)),
        continuesIntoNext = leaveKinds(month.plusMonths(1)),
        preferredStarts = mapOf(FillDayKind.PAID_LEAVE to bloodDonationLeaveStarts(repo.bloodDonationHistory, month))
    )
}

/** Mogući prvi dani plaćenog odsustva u mesecu: prvi radni dan od dana davanja krvi i sledeći radni dan. */
internal fun bloodDonationLeaveStarts(donations: Collection<LocalDate>, month: YearMonth): Set<LocalDate> {
    fun workdayOnOrAfter(date: LocalDate): LocalDate {
        var day = date
        while (day.dayOfWeek.value > DayOfWeek.FRIDAY.value) day = day.plusDays(1)
        return day
    }
    return donations
        .flatMap { donation ->
            val first = workdayOnOrAfter(donation)
            listOf(first, workdayOnOrAfter(first.plusDays(1)))
        }
        .filter { YearMonth.from(it) == month }
        .toSet()
}

/**
 * Dan koji ništa ne govori o stvarnom radu: smena ili praznik iz „Popuni“, prazan slobodan dan
 * vikendom ili praznikom i dan koji samo uključuje ili isključuje praznik. Mesec samo sa takvim
 * danima je neupisan.
 */
internal fun DayRecord.isAutomaticPlaceholder(): Boolean =
    source == DayRecordSource.AUTO_FILL ||
        (
            shift == Shift.OFF && compHoursUsed == 0 &&
                (holidayOverride != null || (holidayWorkOverride == null && (isHoliday || weekday.isWeekend)))
            )

/** Broj dana meseca koje je korisnik sam upisao (bez automatskih, vidi [isAutomaticPlaceholder]). */
internal fun userEnteredDayCount(repo: ShiftRepository, month: MonthKey): Int =
    repo.getMonthRecords(month.year, month.month).count { !it.isAutomaticPlaceholder() }

/** Kako se nudi popunjavanje po listi: odmah (nema tvojih upisa) ili tek posle potvrde zamene upisanih dana. */
internal enum class MonthFillOffer { NONE, EMPTY_MONTH, REPLACE }

/**
 * Časovi se već slažu: ništa se ne nudi. Mesec bez korisnikovih upisa (mesec popunjen samo redovnim
 * danima ostaje „automatski“): popuna odmah. Inače (npr. lista ispravljena posle prve popune):
 * zamena upisanih dana, samo uz potvrdu.
 */
internal fun monthFillOffer(repo: ShiftRepository, month: MonthKey, reconciliation: PayslipReconciliation?): MonthFillOffer {
    val hoursMatch = reconciliation?.let { check -> check.hasAppData && check.rows.filter { it.isHours }.all { it.matches } } == true
    return when {
        hoursMatch -> MonthFillOffer.NONE
        isFourBrigadeMonthFilled(repo, month) -> MonthFillOffer.NONE
        userEnteredDayCount(repo, month) == 0 -> MonthFillOffer.EMPTY_MONTH
        else -> MonthFillOffer.REPLACE
    }
}

/** „Popuni iz liste“ bez potvrde: mesec nema korisnikovih upisa i časovi se ne slažu sa listom. */
internal fun shouldOfferMonthFill(repo: ShiftRepository, month: MonthKey, reconciliation: PayslipReconciliation?): Boolean =
    monthFillOffer(repo, month, reconciliation) == MonthFillOffer.EMPTY_MONTH

/** Četvorobrigada: mesec je već upisan kao najverovatniji raspored po listi (ostale razlike lista ne objašnjava). */
private fun isFourBrigadeMonthFilled(repo: ShiftRepository, month: MonthKey): Boolean {
    if (repo.brigadeTypeValue != BrigadeType.CETVOROBRIGADA) return false
    val payslip = repo.getSavedPayslip(month.year, month.month) ?: return false
    val targets = fillTargetsFrom(payslip) ?: return false
    val days = bestFourBrigadeDays(repo, payslip, targets) ?: return false
    return monthMatchesFillDays(repo, month, days)
}

/** Ishod popune svih meseci: popunjeni, i meseci koje nije moglo (sa porukom). */
internal data class FillAllResult(val filled: List<MonthKey>, val failed: List<Pair<MonthKey, String>>)

/** Meseci sa sačuvanom listom koji nemaju tvojih upisa, a časovi se ne slažu (od najstarijeg). */
internal fun fillableMonths(repo: ShiftRepository): List<MonthKey> =
    repo.getSavedPayslipMonths().filter { month ->
        val payslip = repo.getSavedPayslip(month.year, month.month) ?: return@filter false
        val reconciliation = runPayslipSafely { buildPayslipReconciliation(repo, payslip) }
        shouldOfferMonthFill(repo, month, reconciliation)
    }

/** Popunjava svaki mesec iz [fillableMonths]; mesec koji ne uspe ostaje netaknut. */
internal fun fillAllMonths(repo: ShiftRepository, months: List<MonthKey> = fillableMonths(repo)): FillAllResult {
    val filled = mutableListOf<MonthKey>()
    val failed = mutableListOf<Pair<MonthKey, String>>()
    for (month in months) {
        val payslip = repo.getSavedPayslip(month.year, month.month) ?: continue
        when (val outcome = runPayslipSafely { planMonthFromPayslip(repo, payslip) }) {
            is MonthFillResult.Success -> {
                applyMonthFill(repo, outcome)
                filled += month
            }
            is MonthFillResult.Failure -> failed += month to outcome.message
            null -> failed += month to PAYSLIP_UNEXPECTED_ERROR_TEXT
        }
    }
    return FillAllResult(filled, failed)
}

private fun wholeHours(value: BigDecimal?): Int? {
    val safe = value ?: return 0
    return runCatching { safe.stripTrailingZeros().intValueExact() }.getOrNull()
}

private fun unknownHourItemsText(payslip: ParsedPayslip): String? =
    payslip.unknownHourItems()
        .takeIf { it.isNotEmpty() }
        ?.joinToString { "${it.code} ${it.name}" }

internal fun fillTargetsFrom(payslip: ParsedPayslip): FillTargets? {
    val items = payslip.items
    fun hours(code: Int): Int? = wholeHours(items[code]?.hours)
    fun days(code: Int): Int? = hours(code)?.takeIf { it % DAY_HOURS == 0 }?.div(DAY_HOURS)
    val blocks = mutableListOf<FillLeaveBlock>()
    val vacationDays = days(10) ?: return null
    val paidLeaveDays = days(11) ?: return null
    if (vacationDays > 0) blocks += FillLeaveBlock(FillDayKind.VACATION, vacationDays)
    if (paidLeaveDays > 0) blocks += FillLeaveBlock(FillDayKind.PAID_LEAVE, paidLeaveDays)
    for (sick in items.values.filter { it.isSickLeave() }) {
        val sickHours = wholeHours(sick.hours)?.takeIf { it % DAY_HOURS == 0 } ?: return null
        if (sickHours > 0) {
            blocks += FillLeaveBlock(FillDayKind.SICK, sickHours / DAY_HOURS, sickLeaveRate(sick))
        }
    }
    val holidayWork = wholeHours((items[5] ?: items[23])?.hours) ?: return null
    return FillTargets(
        regularHours = hours(1) ?: return null,
        holidayWorkHours = holidayWork,
        holidayPremiumHours = hours(23) ?: return null,
        paidHolidayHours = hours(12) ?: return null,
        nightHours = hours(20) ?: return null,
        sundayHours = hours(22) ?: return null,
        overtimeHours = hours(30) ?: return null,
        blocks = blocks,
        absenceRoundingGross = payslip.extras
            .firstOrNull { it.label == ABSENCE_ROUNDING_EXTRA_LABEL }
            ?.amount?.abs() ?: BigDecimal.ZERO
    )
}

internal fun buildFillCalendar(
    month: MonthKey,
    scheduledShift: (LocalDate) -> Shift?,
    holidayOverrideOf: (LocalDate) -> Boolean? = { null }
): FillCalendar? {
    val slots = mutableListOf<FillSlot>()
    val length = YearMonth.of(month.year, month.month).lengthOfMonth()
    for (day in 1..length) {
        val date = LocalDate.of(month.year, month.month, day)
        val shift = scheduledShift(date)?.takeIf { it in Shift.workShifts() }
        val isWeekday = date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
        if (shift == null) {
            if (isWeekday) return null
            continue
        }
        val override = holidayOverrideOf(date)
        slots += FillSlot(date, shift, override ?: isSerbianHoliday(date.year, date.monthValue, date.dayOfMonth), override)
    }
    return FillCalendar(month, slots)
}

/**
 * Kopija korisnikovih podataka (podešavanja, saldo sati, drugi meseci) za proveru rasporeda.
 * Izvoz za oblak ne oreže istoriju, pa pravi repozitorijum ostaje netaknut.
 */
internal fun verificationCopy(repo: ShiftRepository): ShiftRepository {
    val copy = ShiftRepository()
    if (!copy.importFromJson(repo.exportCloudBackupJson(null))) {
        copy.brigadeTypeValue = repo.brigadeTypeValue
    }
    return copy
}

internal fun verifyMonthFill(
    scratch: ShiftRepository,
    payslip: ParsedPayslip,
    targets: FillTargets,
    days: List<FillDay>
): PayslipReconciliation {
    replaceMonth(scratch, payslip.month, days, targets.absenceRoundingGross)
    return buildPayslipReconciliation(scratch, payslip)
}

/** Upisuje raspored; dani meseca koji nisu u rasporedu brišu se (bez brisanja celog meseca). */
/**
 * Lista popunjava „% RADA“ samo kad stavka nije 100% (40 Bolovanje: 65); stavka bez procenta je pun
 * iznos — 50 „Bolovanje-Povreda na radu“ (06/2025) je 100%, kao 10 i 11.
 */
private fun sickLeaveRate(item: PayslipItem): Double = item.percent?.toDouble()?.div(100.0) ?: 1.0

internal fun applyMonthFill(repo: ShiftRepository, result: MonthFillResult.Success) {
    replaceMonth(repo, result.month, result.days, result.absenceRoundingGross)
}

private fun replaceMonth(repo: ShiftRepository, month: MonthKey, days: List<FillDay>, absenceRoundingGross: BigDecimal) {
    val planned = days.map { it.date }.toSet()
    val existing = repo.getMonthRecords(month.year, month.month)
    // Praznik koji je korisnik uključio ili isključio ostaje i kad taj dan nije u rasporedu (npr. praznik subotom).
    val holidayOverrides = existing.filter { it.holidayOverride != null }.associate { it.date to it.holidayOverride }
    for (record in existing) {
        val holidayOnly = record.date in holidayOverrides && record.shift == Shift.OFF && record.compHoursUsed == 0
        if (record.isBloodDonationLeave || (record.date !in planned && !holidayOnly)) {
            repo.delete(record.year, record.month, record.day)
        }
    }
    writeFillDays(repo, days, holidayOverrides, repo.brigadeTypeValue == BrigadeType.CETVOROBRIGADA)
    repo.setMonthlyAbsencePenaltyGross(month.year, month.month, absenceRoundingGross)
}

/**
 * Izostanak sa listića firma nije pokrila satima iz salda, pa ceo i skraćen dan
 * izostanka ne troše saldo (inače bi ga obračun platio iz pozitivnog salda).
 */
private fun writeFillDays(
    repo: ShiftRepository,
    days: List<FillDay>,
    holidayOverrides: Map<LocalDate, Boolean?>,
    fourBrigade: Boolean
) {
    val batch = days.map { day ->
        val (y, m, d) = Triple(day.date.year, day.date.monthValue, day.date.dayOfMonth)
        val shortDay = day.hours < DAY_HOURS && day.date.dayOfWeek.value <= DayOfWeek.FRIDAY.value
        val override = holidayOverrides[day.date]
        val record = when (day.kind) {
            FillDayKind.WORK,
            FillDayKind.HOLIDAY_WORK,
            FillDayKind.WEEKEND_WORK -> if (day.kind == FillDayKind.WORK && day.hours == DAY_HOURS && day.shift == day.templateShift) {
                BatchRecord(y, m, d, requireNotNull(day.shift), DAY_HOURS, source = DayRecordSource.AUTO_FILL)
            } else {
                BatchRecord(y, m, d, requireNotNull(day.shift), day.hours, balanceExempt = shortDay)
            }
            FillDayKind.VACATION -> BatchRecord(y, m, d, Shift.VACATION, DAY_HOURS)
            FillDayKind.SICK -> BatchRecord(y, m, d, Shift.SICK, DAY_HOURS, sickPayRate = day.sickRate)
            FillDayKind.PAID_LEAVE -> BatchRecord(y, m, d, Shift.SICK, DAY_HOURS, source = DayRecordSource.PAID_LEAVE)
            FillDayKind.ABSENT -> BatchRecord(y, m, d, Shift.OFF, 0, balanceExempt = true)
            // Četvorobrigada: plaćen praznik je bez balanceExempt (slobodan dan po ciklusu se ne plaća).
            FillDayKind.HOLIDAY_OFF -> BatchRecord(y, m, d, Shift.OFF, 0, balanceExempt = !fourBrigade, source = DayRecordSource.AUTO_FILL)
            FillDayKind.CYCLE_OFF -> BatchRecord(y, m, d, Shift.OFF, 0, balanceExempt = true, source = DayRecordSource.AUTO_FILL)
        }
        if (override == null) record else record.copy(holidayOverride = override, holidayWorkOverride = if (override) null else false)
    }
    repo.setBatch(batch)
}

/** Dani koji odstupaju od običnog rada po rasporedu (za pregled pre upisa). */
internal fun FillDay.isSpecial(): Boolean =
    kind != FillDayKind.CYCLE_OFF && !(kind == FillDayKind.WORK && hours == DAY_HOURS && shift == templateShift)
