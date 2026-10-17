package com.rad.shifttracker.listici

import java.math.BigDecimal
import com.rad.shifttracker.godisnji.VACATION_HOURS_PER_DAY
import com.rad.shifttracker.smene.MonthKey

/** Jedna stavka zarade sa listića: šifra, naziv, časovi, opciono procenat i bruto iznos. */
internal data class PayslipItem(
    val code: Int,
    val name: String,
    val hours: BigDecimal,
    val percent: BigDecimal?,
    val amount: BigDecimal
)

/** Stavka bez časova koju aplikacija ne računa unapred (stimulacije, KIRU, nagrada, regres, „Izostanci 1“). */
internal data class PayslipExtraItem(val label: String, val amount: BigDecimal)

/**
 * Obustava iz tabele „Obustave iz zarade“ (bez toplog obroka): naziv sa listića i iznos.
 * [remainingDebt] i [remainingInstallments] = „Ostatak duga“ i „Ostalo rata“ posle ove rate
 * (null kad ih listić nema, npr. fiksna članarina); [percent] = obustava u % zarade (sindikat, fond).
 */
internal data class PayslipDeduction(
    val code: String,
    val name: String,
    val amount: BigDecimal,
    val remainingDebt: BigDecimal? = null,
    val remainingInstallments: Int? = null,
    val percent: BigDecimal? = null
)

/**
 * Sve što se sa listića koristi pri uvozu. Vrednosti koje nisu pronađene su null,
 * pa se za njih ništa ne predlaže (nikad se ne nagađa).
 */
internal data class ParsedPayslip(
    val month: MonthKey,
    val runLabel: String?,
    val fundHours: Int?,
    val coefficient: BigDecimal?,
    val hourlyValue: BigDecimal?,
    val seniorityYears: Int?,
    val seniorityPercent: BigDecimal?,
    val employeeContributionsPercent: BigDecimal?,
    val taxReliefRsd: BigDecimal?,
    val taxPercent: BigDecimal?,
    val grossTotal: BigDecimal?,
    val netTotal: BigDecimal?,
    val items: Map<Int, PayslipItem>,
    val extras: List<PayslipExtraItem>,
    val payBase: PayslipPayBaseResult,
    /** Bruto toplog obroka (TO2) — listić ga obračunava pun, pa potrošeno skida kao obustavu. */
    val mealGross: BigDecimal? = null,
    /** Bruto iznos minulog rada (MR). */
    val seniorityAmount: BigDecimal? = null,
    /** Obustava „Topli obrok“ (neto) — koliko je stvarno pojedeno u menzi. */
    val mealDeduction: BigDecimal? = null,
    /** „Ukupni radni staž“ u celim godinama (staž osiguranja; od njega zavisi dužina godišnjeg). */
    val totalSeniorityYears: Int? = null,
    /** Doprinos „Beneficirani staž“ (npr. 3,70% u livnici) — KU čl. 29: +2 dana godišnjeg umesto +1. */
    val benefitedSeniority: Boolean = false,
    /** Obustave sa listića bez toplog obroka; null = listić sačuvan pre nego što su se obustave čitale. */
    val deductions: List<PayslipDeduction>? = null,
    /** Neto već isplaćen kao akontacija (kolona „ISPLAĆENO“ u redu neto zarade). */
    val advanceNet: BigDecimal? = null,
    /** „Ukupno uplaćeno“ — konačna isplata koja je legla na račun posle obustava (0 kad sve ode na obustave). */
    val paidNet: BigDecimal? = null
)

/** Dani godišnjeg odmora sa listića: časovi šifre 10 / 8; bez šifre 10 je 0. */
internal fun payslipVacationDays(payslip: ParsedPayslip): Double =
    (payslip.items[PayslipPayBaseSource.VACATION.code]?.hours?.toDouble() ?: 0.0) / VACATION_HOURS_PER_DAY

internal sealed interface PayslipParseResult {
    data class Success(val payslip: ParsedPayslip) : PayslipParseResult
    data class Failure(val reason: PayslipPayBaseResult) : PayslipParseResult

    /**
     * Poseban obračun za mesec (akontacija, prevoz, regres...): nije listić za „Listu“.
     * [month] = „ZA MESEC“ (mesec rada); [paymentMonth] = mesec iz „Datum isplate“ — aplikacija
     * vodi akontaciju pod mesecom isplate. [paidNet] = „Ukupno uplaćeno“ (šta je leglo na račun).
     * [transportDaily] = prevoz po danu iz stavke „PREVOZ PRV“ (iznos / (časovi / 8)).
     * Vrednosti koje nisu pročitane su null.
     */
    data class SideRun(
        val kind: PayslipSideRunKind,
        val month: MonthKey,
        val runLabel: String?,
        val paidNet: BigDecimal?,
        val paymentMonth: MonthKey? = null,
        val transportDaily: BigDecimal? = null
    ) : PayslipParseResult
}

/**
 * Posebni obračuni koji stižu istim mejlom kao listić, sa istim zaglavljem „ZA MESEC MM/GGGG rb.N“,
 * ali bez „/konačna“: akontacija ~5., prevoz ~15., regres po potrebi (za 08/2026 isplaćen 28.08).
 * [item] = početak stavke u tabeli, bez razmaka („A K O N T A C I J A AK“, „PREVOZPRV“, „RegresREG“).
 */
internal enum class PayslipSideRunKind(val item: String?, val label: String) {
    ADVANCE("AKONTACIJA", "akontacije"),
    TRANSPORT("PREVOZ", "prevoza"),
    REGRESS("REGRES", "regresa"),

    /** Nepoznat obračun bez „/konačna“ i bez stavki sa satima. */
    OTHER(null, "")
}

// Sa separatorom hiljada (34,221.00) ili bez njega (226.00).
private const val NUMBER = """(?:-?\d{1,3}(?:,\d{3})+(?:\.\d+)?|-?\d+(?:\.\d+)?)"""

private val FUND_REGEX = Regex("""Fond za mesec\s*:\s*(\d+)""")
private val COEFFICIENT_REGEX = Regex("""Koeficijent\s*:\s*(\d+(?:\.\d+)?)""")
private val HOURLY_VALUE_REGEX = Regex("""Vrednost sata\s*:\s*($NUMBER)""")
private val SENIORITY_YEARS_REGEX = Regex("""Radni staž za MR\s*:\s*(\d+)""")
// „Ukupni radni staž:7“; PDF ume da da i „Ukupan“ ili „staz“ bez kvačice.
private val TOTAL_SENIORITY_YEARS_REGEX = Regex("""Ukup(?:ni|an) radni sta[žz]\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
private val BENEFITED_SENIORITY_REGEX = Regex("""^Beneficirani sta[žz]\s+(\d+(?:\.\d+)?)%""", RegexOption.IGNORE_CASE)
private val SENIORITY_PERCENT_REGEX = Regex("""^MR\s+Minuli rad\s+(\d+(?:\.\d+)?)\s+$NUMBER$""")
private val SENIORITY_AMOUNT_REGEX = Regex("""^MR\s+Minuli rad\s+\d+(?:\.\d+)?\s+($NUMBER)$""")
private val MEAL_REGEX = Regex("""^TO\d*\s+Topli obrok\s+($NUMBER)$""")

// Obustava sa šifrom T+MMGG+rb (npr. „T07261 Topli obrok 0.00 0 2,895.00“): poslednji broj je iznos.
private val MEAL_DEDUCTION_REGEX = Regex("""^T\d{4,}\s*Topli obrok\b.*\s($NUMBER)$""")
private val CONTRIBUTION_REGEX = Regex("""^(PIO|Zdravstvo|Nezaposlenost)\s+(\d+(?:\.\d+)?)%""")
private val TAX_RELIEF_REGEX = Regex("""^PORESKA OLAKŠICA\s+($NUMBER)""")
private val TAX_REGEX = Regex("""^POREZ NA ZARADE I NAKNADE ZARADE\s+(\d+(?:\.\d+)?)%""")
private val GROSS_REGEX = Regex("""^Zarade i naknade zarada sa porezom i dop\.na teret zaposlenog\s+($NUMBER)""")
private val NET_REGEX = Regex(
    """^Zarade i naknade zarada bez poreza i dop\.na teret zaposlenog i na teret poslodavca\s+($NUMBER)"""
)
private val PAID_TOTAL_REGEX = Regex("""^Ukupno upla[ćc]eno\s+($NUMBER)""", RegexOption.IGNORE_CASE)
private val ITEM_REGEX =
    Regex("""^(\d{1,3})\s+(\D.*?)\s+(\d+\.\d)(?:\s+(\d{1,3}))?\s+(\d+(?:\.\d{1,2})?)$""")

/** Stavka bez časova: šifra (81, KIR, GN...), naziv, opciono procenat, bruto iznos. */
private val EXTRA_REGEX =
    Regex("""^([A-Z]{1,4}\d?|\d{1,3})\s+(\D.*?)\s+(?:-?\d+(?:\.\d+)?\s+)?(-?\d+(?:\.\d{1,2})?)$""")

/**
 * Šifre sa listića 01–08/2026: bez časova 81 Stimulacije, 96 Izostanci 1, KIR KIRU (±%),
 * GN Godišnja nagrada, REG Regres; MR i TO2 se čitaju posebno, a IA (akontacija) nije deo bruta.
 * Ostale šifre bez časova ulaze pod nazivom sa listića, da se ništa ne izgubi.
 */
private val KNOWN_EXTRA_LABELS = mapOf(
    "81" to "Stimulacije",
    "96" to ABSENCE_ROUNDING_EXTRA_LABEL,
    "KIR" to "KIRU",
    "GN" to "Godišnja nagrada",
    "REG" to "Regres"
)
private val NOT_EXTRA_CODES = Regex("""^(MR|TO\d*|IA|UKUPNO)$""")
private const val ITEMS_HEADER = "Oznaka i vrsta rada"
private const val ITEMS_END = "UKUPNO"

/**
 * Šifre sa časovima sa listića 01–08/2026 koje aplikacija poredi: 01 režija, 05 režija na DP,
 * 10 GO, 11 plaćeno odsustvo, 12 državni praznik, 20 noćni, 22 nedelja, 23 rad na praznik,
 * 24 uvećanje za prekovremeni (isti sati kao 30), 30 prekovremeni, 95 izostanci; bolovanje (40...) posebno.
 */
internal val PAYSLIP_KNOWN_HOUR_CODES = setOf(1, 5, 10, 11, 12, 20, 21, 22, 23, 24, 30, 95)

/** Šifra 21 „Rad u otežanim uslovima“ (npr. livnica, 32%); listić bez nje = nema otežanih uslova. */
internal const val DIFFICULT_WORK_CONDITIONS_CODE = 21

/**
 * Bolovanje na teret poslodavca: 40 „Bolovanje…“ i 41 „Razlika bolovanja do 30 d.“ (listić 08/2026,
 * 65%, ista osnovica kao GO) — obe se vode kao dani bolovanja, sati ulaze u fond.
 */
internal fun PayslipItem.isSickLeave(): Boolean =
    code == 40 || code == 41 || name.contains("bolovanj", ignoreCase = true)

/** Stavke sa časovima koje aplikacija ne poznaje (npr. nova vrsta odsustva). */
internal fun ParsedPayslip.unknownHourItems(): List<PayslipItem> =
    items.values.filter { it.code !in PAYSLIP_KNOWN_HOUR_CODES && !it.isSickLeave() && it.hours.signum() != 0 }

internal fun parsePayslip(lines: List<String>): PayslipParseResult {
    val header = parsePayslipPayBase(lines)
    val month = when (header) {
        is PayslipPayBaseResult.Success -> header.month
        is PayslipPayBaseResult.NoSource -> header.month
        else -> return PayslipParseResult.Failure(header)
    }
    val trimmed = payslipTextLines(lines)
    val runLabel = (header as? PayslipPayBaseResult.Success)?.runLabel ?: findRunLabel(trimmed)
    val items = parseItems(trimmed)
    payslipSideRunKind(trimmed, items)?.let { kind ->
        return PayslipParseResult.SideRun(
            kind = kind,
            month = month,
            runLabel = runLabel,
            paidNet = trimmed.findGroup(PAID_TOTAL_REGEX)?.toPayslipDecimal(),
            paymentMonth = parsePaymentMonth(trimmed),
            transportDaily = if (kind == PayslipSideRunKind.TRANSPORT) parseTransportDaily(trimmed) else null
        )
    }
    return PayslipParseResult.Success(
        ParsedPayslip(
            month = month,
            runLabel = runLabel,
            fundHours = trimmed.findGroup(FUND_REGEX)?.toIntOrNull(),
            coefficient = trimmed.findGroup(COEFFICIENT_REGEX)?.toPayslipDecimal(),
            hourlyValue = trimmed.findGroup(HOURLY_VALUE_REGEX)?.toPayslipDecimal(),
            seniorityYears = trimmed.findGroup(SENIORITY_YEARS_REGEX)?.toIntOrNull(),
            seniorityPercent = trimmed.findGroup(SENIORITY_PERCENT_REGEX)?.toPayslipDecimal(),
            employeeContributionsPercent = parseEmployeeContributions(trimmed),
            taxReliefRsd = trimmed.findGroup(TAX_RELIEF_REGEX)?.toPayslipDecimal(),
            taxPercent = trimmed.findGroup(TAX_REGEX)?.toPayslipDecimal(),
            grossTotal = trimmed.findGroup(GROSS_REGEX)?.toPayslipDecimal(),
            netTotal = trimmed.findGroup(NET_REGEX)?.toPayslipDecimal(),
            items = items,
            extras = parseExtras(trimmed),
            payBase = payslipPayBaseFromItems(month, runLabel, items),
            mealGross = trimmed.findGroup(MEAL_REGEX)?.toPayslipDecimal(),
            seniorityAmount = trimmed.findGroup(SENIORITY_AMOUNT_REGEX)?.toPayslipDecimal(),
            mealDeduction = trimmed.findGroup(MEAL_DEDUCTION_REGEX)?.toPayslipDecimal(),
            totalSeniorityYears = trimmed.findGroup(TOTAL_SENIORITY_YEARS_REGEX)?.toIntOrNull(),
            benefitedSeniority = (trimmed.findGroup(BENEFITED_SENIORITY_REGEX)?.toPayslipDecimal()?.signum() ?: 0) > 0,
            deductions = parseDeductions(trimmed),
            advanceNet = trimmed.firstNotNullOfOrNull { NET_COLUMNS_REGEX.find(it) }?.groupValues?.get(1)?.toPayslipDecimal(),
            paidNet = parsePaidNet(trimmed)
        )
    )
}

// „… bez poreza … poslodavca 122,828.55 58,942.00 63,886.55“: ukupno, isplaćeno (akontacija), za isplatu.
private val NET_COLUMNS_REGEX = Regex(
    """^Zarade i naknade zarada bez poreza i dop\.na teret zaposlenog i na teret poslodavca\s+$NUMBER\s+($NUMBER)\s+$NUMBER$"""
)

/**
 * „Ukupno uplaćeno“; lista sa tabelom obustava bez tog reda (06/2026: sve otišlo na kredit) je
 * uplatila 0. Lista bez ijednog od ta dva nije pročitana do kraja, pa je null.
 */
private fun parsePaidNet(lines: List<String>): BigDecimal? =
    lines.findGroup(PAID_TOTAL_REGEX)?.toPayslipDecimal()
        ?: BigDecimal.ZERO.takeIf { lines.any { it.startsWith(DEDUCTIONS_HEADER) } }

private const val DEDUCTIONS_HEADER = "Oznaka i naziv obustava"

/**
 * Red obustave: „000451 NLB Komercijalna banka 2,920,783.99 62 49,864.00“ (dug, rate, iznos),
 * „000006 SINDIKAT NEZAVISNOST ČLANARINA 1.50 % 2,277.92“ (procenat) ili „000001 OZ "DUNAV" UŽICE 150.00“.
 * Red sa „*“ na početku listić nije obustavio (nije bilo dovoljno zarade).
 */
private val DEDUCTION_REGEX = Regex(
    """^(\*\s*)?(\S+)\s+(.+?)\s+(?:($NUMBER)\s+(\d+)\s+|($NUMBER)\s*%\s+)?($NUMBER)$"""
)

/** Obustave između zaglavlja tabele i „Ukupno obustava“; topli obrok se čita posebno ([MEAL_DEDUCTION_REGEX]). */
private fun parseDeductions(lines: List<String>): List<PayslipDeduction> {
    val start = lines.indexOfFirst { it.startsWith(DEDUCTIONS_HEADER) }
    if (start < 0) return emptyList()
    return lines.drop(start + 1)
        .takeWhile { !it.contains("Ukupno obustava", ignoreCase = true) && !it.startsWith("UPLATA PO RA") }
        .distinct()
        .mapNotNull { line ->
            val groups = DEDUCTION_REGEX.matchEntire(line)?.groupValues ?: return@mapNotNull null
            if (groups[1].isNotEmpty()) return@mapNotNull null
            val name = groups[3].trim()
            if (name.startsWith("Topli obrok", ignoreCase = true)) return@mapNotNull null
            val amount = groups[7].toPayslipDecimal()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            PayslipDeduction(
                code = groups[2],
                name = name,
                amount = amount,
                remainingDebt = groups[4].takeIf { it.isNotEmpty() }?.toPayslipDecimal(),
                remainingInstallments = groups[5].takeIf { it.isNotEmpty() }?.toIntOrNull(),
                percent = groups[6].takeIf { it.isNotEmpty() }?.toPayslipDecimal()
            )
        }
}

/**
 * Vrsta posebnog obračuna, ili null za listić. Konačni listić („…/konačna“) nikad nije poseban,
 * iako ima i „IA Isplacena akontacija“ i „REG Regres“. Razmaci između slova zavise od PDF-a, pa se
 * porede redovi bez razmaka. Obračun bez „/konačna“ sa stavkama sa satima ostaje listić.
 */
internal fun payslipSideRunKind(lines: List<String>, items: Map<Int, PayslipItem>): PayslipSideRunKind? {
    val header = lines.firstOrNull { it.contains("ZA MESEC", ignoreCase = true) } ?: return null
    if (Regex("""kona[čc]n""", RegexOption.IGNORE_CASE).containsMatchIn(header)) return null
    val compact = lines.map { line -> line.filterNot(Char::isWhitespace) }
    return PayslipSideRunKind.entries.firstOrNull { kind ->
        // Šifra stavke (AK, PRV, REG) je na telefonu ispred naziva, a u drugim čitačima iza njega.
        val item = kind.item ?: return@firstOrNull false
        val regex = Regex("""^[A-Z]{0,4}?$item""", RegexOption.IGNORE_CASE)
        compact.any { regex.containsMatchIn(it) }
    } ?: PayslipSideRunKind.OTHER.takeIf { items.isEmpty() }
}

private val PAYMENT_DATE_REGEX = Regex("""Datum isplate\s*:\s*\d{1,2}\.(\d{1,2})\.(\d{4})""", RegexOption.IGNORE_CASE)

// „PRV PREVOZ 144.0 4320“ (telefon), „PREVOZPRV 144.0 4320“ ili „PREVOZ PRV …“: časovi, pa iznos.
private val TRANSPORT_ITEM_REGEX =
    Regex("""^(?:PRV\s*)?PREVOZ(?:\s*PRV)?\s+(\d+(?:\.\d+)?)\s+($NUMBER)$""", RegexOption.IGNORE_CASE)

private fun parsePaymentMonth(lines: List<String>): MonthKey? {
    val match = lines.firstNotNullOfOrNull { PAYMENT_DATE_REGEX.find(it) } ?: return null
    val month = match.groupValues[1].toInt()
    val year = match.groupValues[2].toInt()
    return if (month in 1..12 && year in PAYSLIP_YEARS) MonthKey(year, month) else null
}

/** Prevoz po danu = iznos / (časovi / 8): za 08/2026 4320 / (144 / 8) = 240. */
/**
 * Prevoz po danu: zbir iznosa / zbir dana (časovi / 8) iz svih redova „PREVOZ PRV“ — obračun može
 * imati više redova (npr. dve cene u mesecu). Red ponovljen do znaka (tabela na drugoj strani) se broji jednom.
 */
private fun parseTransportDaily(lines: List<String>): BigDecimal? {
    val rows = lines.distinct().mapNotNull { TRANSPORT_ITEM_REGEX.matchEntire(it)?.groupValues }
    if (rows.isEmpty()) return null
    val hours = rows.fold(BigDecimal.ZERO) { sum, row -> sum + BigDecimal(row[1]) }
    val amount = rows.fold(BigDecimal.ZERO) { sum, row -> sum + (row[2].toPayslipDecimal() ?: return null) }
    val days = hours.divide(BigDecimal(VACATION_HOURS_PER_DAY.toInt()), 4, java.math.RoundingMode.HALF_UP)
    if (days.signum() <= 0 || amount.signum() <= 0) return null
    return amount.divide(days, 2, java.math.RoundingMode.HALF_UP)
}

private val RUN_NUMBER_REGEX = Regex("""rb\.(\d+)""")

/** Broj obračuna iz „rb.3“ (bez njega 0) — kasniji obračun istog meseca ima veći broj. */
internal fun payslipRunNumber(runLabel: String?): Int =
    runLabel?.let { RUN_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

private fun findRunLabel(lines: List<String>): String? =
    lines.firstNotNullOfOrNull { Regex("""ZA MESEC\s+\d{1,2}/\d{4}\s+(rb\.\S+)""").find(it) }?.groupValues?.get(1)

/** Doprinosi na teret radnika: zbir stopa između „…TERET RADNIKA“ i „…TERET POSLODAVCA“. */
private fun parseEmployeeContributions(lines: List<String>): BigDecimal? {
    val start = lines.indexOfFirst { it.startsWith("DOPRINOSI NA TERET RADNIKA") }
    if (start < 0) return null
    val rates = lines.drop(start + 1)
        .takeWhile { !it.startsWith("DOPRINOSI NA TERET POSLODAVCA") }
        .mapNotNull { CONTRIBUTION_REGEX.find(it)?.groupValues?.get(2)?.let(::BigDecimal) }
    return if (rates.isEmpty()) null else rates.fold(BigDecimal.ZERO, BigDecimal::add)
}

/**
 * Stavke sa časovima po šifri. Ista stavka (šifra i naziv) u više redova (npr. dve vrednosti sata
 * u mesecu) se sabira; isti red ponovljen do znaka (tabela na drugoj strani) se broji jednom.
 * Druga stavka pod istom šifrom se preskače — svuda važi prva.
 */
private fun parseItems(lines: List<String>): Map<Int, PayslipItem> {
    val items = linkedMapOf<Int, PayslipItem>()
    for (line in lines) {
        val groups = ITEM_REGEX.matchEntire(line)?.groupValues ?: continue
        val code = groups[1].toInt()
        val item = PayslipItem(
            code = code,
            name = groups[2],
            hours = BigDecimal(groups[3]),
            percent = groups[4].takeIf { it.isNotEmpty() }?.let(::BigDecimal),
            amount = BigDecimal(groups[5])
        )
        val previous = items[code]
        items[code] = when {
            previous == null -> item
            previous.name != item.name -> previous
            previous.hours.compareTo(item.hours) == 0 && previous.amount.compareTo(item.amount) == 0 -> previous
            else -> previous.copy(hours = previous.hours + item.hours, amount = previous.amount + item.amount)
        }
    }
    return items
}

/**
 * Stavke bez časova iz tabele „Oznaka i vrsta rada“ (do „UKUPNO“). Bez zaglavlja tabele
 * čitaju se samo poznate šifre, da se ne uhvati neki drugi red listića.
 */
private fun parseExtras(lines: List<String>): List<PayslipExtraItem> {
    val start = lines.indexOfFirst { it.startsWith(ITEMS_HEADER) }
    val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.startsWith(ITEMS_END) }
    return section.mapNotNull { line ->
        if (ITEM_REGEX.matches(line)) return@mapNotNull null
        val groups = EXTRA_REGEX.matchEntire(line)?.groupValues ?: return@mapNotNull null
        val code = groups[1]
        if (NOT_EXTRA_CODES.matches(code)) return@mapNotNull null
        val label = KNOWN_EXTRA_LABELS[code]
            ?: if (start < 0) return@mapNotNull null else "${sentenceCase(groups[2])} ($code)"
        PayslipExtraItem(label, BigDecimal(groups[3]))
    }
}

/** „JUBILARNA NAGRADA“ → „Jubilarna nagrada“; naziv koji nije sav velikim slovima ostaje isti. */
private fun sentenceCase(name: String): String {
    val trimmed = name.trim()
    if (trimmed != trimmed.uppercase()) return trimmed
    return trimmed.lowercase().replaceFirstChar { it.uppercase() }
}

private fun List<String>.findGroup(regex: Regex): String? =
    firstNotNullOfOrNull { regex.find(it) }?.groupValues?.get(1)

/** Listić koristi zarez za hiljade i tačku za decimale (npr. 34,221.00). */
private fun String.toPayslipDecimal(): BigDecimal? = replace(",", "").toBigDecimalOrNull()
