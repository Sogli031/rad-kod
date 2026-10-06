package com.rad.shifttracker.listici

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.min
import com.rad.shifttracker.obracun.getSundayMinutesForShift
import com.rad.shifttracker.obracun.holidayPremiumMinutes
import com.rad.shifttracker.obracun.nightPremiumMinutes
import com.rad.shifttracker.smene.DayRecord
import com.rad.shifttracker.smene.Shift

/**
 * Traženje rasporeda meseca koji tačno daje sate sa listića.
 *
 * Sati svakog dana računaju se istim funkcijama kao obračun ([workVector]), pa model ne
 * nagađa pravila (noćni po punim satima, nedelja po satu, praznik po satu za treću smenu,
 * vikend kao prekovremeni). Mesec se deli na tri dela:
 *  - obične radne dane (pon–pet, nije praznik) po šablonu: tu idu GO, plaćeno odsustvo i
 *    bolovanje kao uzastopni blokovi, a ostatak kroz izostanke, kraće i produžene smene;
 *  - posebne dane (vikend, praznik, treća smena uoči praznika) koji se biraju pojedinačno;
 *  - izbor sa najmanjom „cenom“ (što manje neobičnih dana) ide prvi na proveru obračunom.
 */
internal data class HoursVec(
    val regular: Int = 0,
    val holiday: Int = 0,
    val premium: Int = 0,
    val night: Int = 0,
    val sunday: Int = 0,
    val overtime: Int = 0
)

private const val DAY_HOURS = 8
private const val MAX_SHIFT_HOURS = 12
private const val MAX_SHORTAGE_PER_DAY = DAY_HOURS - 1
private const val MAX_EXTENSION_PER_DAY = MAX_SHIFT_HOURS - DAY_HOURS
private const val LONG_SHORTAGE_HOURS = 4
private const val MAX_SWAP_DAYS = 10
private const val MAX_CANDIDATES = 40
private const val MINUTES = 60

/** 8 pravih listića 2026 prave najviše ~133.000 stanja posebnih dana; granica čuva memoriju telefona. */
private const val MAX_SPECIAL_STATES = 750_000

// „Cena“ neobičnih dana: manja cena = verovatniji raspored.
private const val COST_RUN = 1.0
private const val COST_RUN_NOT_ALIGNED = 0.05
private const val COST_EARLIER_START = 0.0005
private const val COST_CONTINUATION_BONUS = 0.4
private const val COST_DONATION_BONUS = 0.4
private const val COST_BLOCK_ON_SPECIAL_DAY = 0.5

/** Blokovi mogu da imaju negativnu cenu (nastavak iz susednog meseca), pa granica posebnih dana nije stroga. */
private const val COST_BOUND_MARGIN = 3.0
private const val BLOCK_USE_BITS = 3
private const val MAX_BLOCK_USE = (1 shl BLOCK_USE_BITS) - 1
private const val COST_ABSENT_DAY = 1.0
private const val COST_PARTIAL_DAY = 1.0
private const val COST_VERY_SHORT_DAY = 0.3
private const val COST_EXTENSION_DAY = 1.0
private const val COST_EXTRA_WORK_DAY = 1.0
private const val COST_OTHER_SHIFT = 0.5
private const val COST_ODD_HOURS = 0.2
private const val COST_SWAP_DAY = 3.0
private const val COST_ABSENT_WITH_EXTENSION = 3.0

/** Sati koje jedan dan daje po stavkama listića — isto kao mesečni obračun (trobrigada). */
internal fun workVector(date: LocalDate, shift: Shift, hours: Int, holidayOverride: Boolean? = null): HoursVec {
    val record = DayRecord(date.year, date.monthValue, date.dayOfMonth, shift, hours, holidayOverride = holidayOverride)
    val minutes = record.paidWorkMinutes
    val total = minutes / MINUTES
    val night = nightPremiumMinutes(shift, minutes) / MINUTES
    val sunday = getSundayMinutesForShift(record.weekday, shift, minutes) / MINUTES
    val premium = record.holidayPremiumMinutes(shift, minutes) / MINUTES
    if (record.weekday.isWeekend && !record.workedOnHoliday) {
        return HoursVec(premium = premium, night = night, sunday = sunday, overtime = total)
    }
    // Rad na praznik nikad nije prekovremeni; inače je prekovremeni višak preko 8 h. Rad na praznik (05)
    // je po satu kao 123% (23): treća smena uoči praznika ima 6 h praznika, a ostatak smene je režija.
    val baseMinutes = if (record.workedOnHoliday) minutes else min(minutes, DAY_HOURS * MINUTES)
    val holiday = record.holidayPremiumMinutes(shift, baseMinutes) / MINUTES
    val base = baseMinutes / MINUTES
    return HoursVec(base - holiday, holiday, premium, night, sunday, total - base)
}

private fun standardVector(shift: Shift): HoursVec =
    HoursVec(regular = DAY_HOURS, night = if (shift == Shift.THIRD) DAY_HOURS else 0)

/** Običan radni dan: pon–pet, nije praznik, i njegova smena ne dira praznik (treća uoči praznika). */
internal fun FillSlot.isGroupDay(): Boolean =
    isWeekday && !isHoliday && workVector(date, template, DAY_HOURS, holidayOverride) == standardVector(template)

internal fun splitEvenly(total: Int, maxPiece: Int): List<Int> {
    if (total <= 0) return emptyList()
    val count = (total + maxPiece - 1) / maxPiece
    val base = total / count
    val extra = total % count
    return List(count) { index -> base + if (index < extra) 1 else 0 }
}

/** Kraći dan je to manje verovatan što je manje rađeno (1h rada je ređe od 5h). */
private fun shortageCost(shortage: Int): Double =
    COST_PARTIAL_DAY + (shortage - LONG_SHORTAGE_HOURS).coerceAtLeast(0) * COST_VERY_SHORT_DAY

private fun partialDaysCost(total: Int): Double = splitEvenly(total, MAX_SHORTAGE_PER_DAY).sumOf(::shortageCost)

private fun extensionDaysCost(total: Int): Double =
    splitEvenly(total, MAX_EXTENSION_PER_DAY).size * COST_EXTENSION_DAY

private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

// ---------------------------------------------------------------------------------------
// Blokovi (GO, plaćeno odsustvo, bolovanje) po običnim radnim danima
// ---------------------------------------------------------------------------------------

/** Raspored blokova: za svaki običan radni dan indeks bloka ili -1 (slobodan). */
private class BlockPlan(val cost: Double, val assignment: IntArray)

private class BlockStep(val cost: Double, val previous: Long, val choice: Int)

/**
 * Saznanja o tome gde blok odsustva verovatno stoji: bolovanje i godišnji se nastavljaju iz i u susedne
 * mesece (lista tog meseca ima isto odsustvo), a plaćeno odsustvo za krv počinje na dan davanja.
 */
internal data class FillHints(
    val continuesFromPrevious: Set<FillDayKind> = emptySet(),
    val continuesIntoNext: Set<FillDayKind> = emptySet(),
    val preferredStarts: Map<FillDayKind, Set<LocalDate>> = emptyMap()
)

/**
 * Prirodan blok počinje ponedeljkom ili prvim danom posle praznika, a završava petkom ili danom pre
 * praznika; [startAligned] i [endAligned] su po indeksu običnog radnog dana.
 */
private class BlockStyle(val startAligned: BooleanArray, val endAligned: BooleanArray, val hints: FillHints)

private fun blockStyle(calendar: FillCalendar, group: List<FillSlot>, hints: FillHints): BlockStyle {
    val holidays = calendar.slots.filter { it.isHoliday }.map { it.date }.toSet()
    fun neighborWorkday(date: LocalDate, step: Long): LocalDate {
        var next = date.plusDays(step)
        while (next.dayOfWeek.value > DayOfWeek.FRIDAY.value) next = next.plusDays(step)
        return next
    }
    return BlockStyle(
        startAligned = BooleanArray(group.size) { i ->
            val date = group[i].date
            date.dayOfWeek == DayOfWeek.MONDAY || neighborWorkday(date, -1) in holidays
        },
        endAligned = BooleanArray(group.size) { i ->
            val date = group[i].date
            date.dayOfWeek == DayOfWeek.FRIDAY || neighborWorkday(date, 1) in holidays
        },
        hints = hints
    )
}

/**
 * Najjeftiniji raspored blokova za svaki mogući broj pokrivenih dana treće smene
 * (samo taj broj utiče na ostatak meseca). Blok je najjeftiniji kao jedan uzastopni niz.
 */
private fun planBlocks(group: List<FillSlot>, blocks: List<FillLeaveBlock>, style: BlockStyle): Map<Int, BlockPlan> {
    if (blocks.sumOf { it.days } > group.size) return emptyMap()
    if (blocks.isEmpty()) return mapOf(0 to BlockPlan(0.0, IntArray(group.size) { -1 }))
    return BlockPlanner(group, blocks, style).plan()
}

/**
 * Dinamičko programiranje po običnim radnim danima. Stanje posle dana je (preostali dani svakog bloka,
 * blok prethodnog dana ili -1, broj dana treće smene u blokovima), spakovano u [Long] ključ.
 */
private class BlockPlanner(
    private val group: List<FillSlot>,
    private val blocks: List<FillLeaveBlock>,
    private val style: BlockStyle
) {
    private val n = group.size
    private val k = blocks.size
    private val sizes = blocks.map { it.days }
    private val radix = sizes.map { it + 1 }

    fun plan(): Map<Int, BlockPlan> {
        val layers = ArrayList<HashMap<Long, BlockStep>>(n + 1)
        layers += hashMapOf(key(encodeRemaining(sizes.toIntArray()), -1, 0) to BlockStep(0.0, -1L, -1))
        for (i in 0 until n) {
            layers += nextLayer(layers[i], i)
        }
        return bestByNight(layers[n]).mapValues { (_, final) -> reconstruct(layers, final.first, final.second) }
    }

    private fun nextLayer(layer: HashMap<Long, BlockStep>, i: Int): HashMap<Long, BlockStep> {
        val next = HashMap<Long, BlockStep>()
        val daysLeftAfter = n - i - 1
        val isNight = group[i].template == Shift.THIRD
        for ((stateKey, step) in layer) {
            val night = nightOf(stateKey)
            val previous = previousOf(stateKey)
            val remainingCode = remainingCodeOf(stateKey)
            val remaining = decodeRemaining(remainingCode)
            if (remaining.sum() <= daysLeftAfter) {
                val closed = if (previous >= 0) endCost(previous, i - 1) else 0.0
                relax(next, key(remainingCode, -1, night), BlockStep(step.cost + closed, stateKey, -1))
            }
            for (b in 0 until k) {
                if (remaining[b] == 0) continue
                remaining[b] -= 1
                val cost = when {
                    previous == b -> step.cost
                    previous >= 0 -> step.cost + endCost(previous, i - 1) + startCost(b, i)
                    else -> step.cost + startCost(b, i)
                }
                relax(next, key(encodeRemaining(remaining), b, night + if (isNight) 1 else 0), BlockStep(cost, stateKey, b))
                remaining[b] += 1
            }
        }
        return next
    }

    /** Za svaki broj pokrivenih noćnih dana: završno stanje sa svim blokovima iskorišćenim i njegova cena. */
    private fun bestByNight(last: HashMap<Long, BlockStep>): Map<Int, Pair<Long, Double>> {
        val best = HashMap<Int, Pair<Long, Double>>()
        for ((stateKey, step) in last) {
            if (remainingCodeOf(stateKey) != 0L) continue
            val night = nightOf(stateKey)
            val previous = previousOf(stateKey)
            val cost = step.cost + if (previous >= 0) endCost(previous, n - 1) else 0.0
            val current = best[night]
            if (current == null || cost < current.second) best[night] = stateKey to cost
        }
        return best
    }

    private fun reconstruct(layers: List<HashMap<Long, BlockStep>>, finalKey: Long, cost: Double): BlockPlan {
        val assignment = IntArray(n)
        var stateKey = finalKey
        for (i in n downTo 1) {
            val step = layers[i].getValue(stateKey)
            assignment[i - 1] = step.choice
            stateKey = step.previous
        }
        return BlockPlan(cost, assignment)
    }

    private fun startCost(block: Int, index: Int): Double {
        val kind = blocks[block].kind
        var cost = COST_RUN + index * COST_EARLIER_START
        if (!style.startAligned[index]) cost += COST_RUN_NOT_ALIGNED
        if (index == 0 && kind in style.hints.continuesFromPrevious) cost -= COST_CONTINUATION_BONUS
        if (group[index].date in style.hints.preferredStarts[kind].orEmpty()) cost -= COST_DONATION_BONUS
        return cost
    }

    private fun endCost(block: Int, index: Int): Double {
        var cost = if (style.endAligned[index]) 0.0 else COST_RUN_NOT_ALIGNED
        if (index == n - 1 && blocks[block].kind in style.hints.continuesIntoNext) cost -= COST_CONTINUATION_BONUS
        return cost
    }

    private fun encodeRemaining(remaining: IntArray): Long =
        remaining.indices.fold(0L) { code, i -> code * radix[i] + remaining[i] }

    private fun decodeRemaining(code: Long): IntArray {
        val remaining = IntArray(k)
        var rest = code
        for (i in k - 1 downTo 0) {
            remaining[i] = (rest % radix[i]).toInt()
            rest /= radix[i]
        }
        return remaining
    }

    private fun key(remainingCode: Long, previous: Int, night: Int): Long =
        (remainingCode * (k + 1) + (previous + 1)) * NIGHT_KEY_RADIX + night

    private fun nightOf(stateKey: Long): Int = (stateKey % NIGHT_KEY_RADIX).toInt()

    private fun previousOf(stateKey: Long): Int = ((stateKey / NIGHT_KEY_RADIX) % (k + 1)).toInt() - 1

    private fun remainingCodeOf(stateKey: Long): Long = stateKey / NIGHT_KEY_RADIX / (k + 1)

    private fun relax(layer: HashMap<Long, BlockStep>, key: Long, step: BlockStep) {
        val current = layer[key]
        if (current == null || step.cost < current.cost) layer[key] = step
    }

    private companion object {
        /** Broj noćnih dana u ključu stanja (mesec ima najviše 23 obična radna dana). */
        const val NIGHT_KEY_RADIX = 64
    }
}

// ---------------------------------------------------------------------------------------
// Posebni dani: vikend, praznici, treća smena uoči praznika
// ---------------------------------------------------------------------------------------

private data class SpecialKey(
    val regular: Int,
    val holiday: Int,
    val premium: Int,
    val night: Int,
    val sunday: Int,
    val overtime: Int,
    val paidHoliday: Int,
    val absent: Boolean,
    val extended: Boolean,
    /** Koliko dana svakog bloka odsustva pada na posebne radne dane ([BLOCK_USE_BITS] bita po bloku). */
    val blockUse: Int = 0
)

private class SpecialOption(
    val vec: HoursVec,
    val paidHoliday: Int,
    val absent: Boolean,
    val extended: Boolean,
    val cost: Double,
    val day: FillDay?,
    val blockIndex: Int = -1
)

private class SpecialStep(val cost: Double, val previous: SpecialKey?, val option: Int)

private class SpecialFinal(val key: SpecialKey, val cost: Double, val days: List<FillDay>)

/**
 * Koliko posebnih dana se probava: od najprirodnijih (rad cele smene po rasporedu) ka svim mogućim.
 * Svaki sledeći nivo sadrži prethodni; prvo se traži jednostavno objašnjenje, pa tek onda neobično.
 */
private enum class OptionTier { NATURAL, TEMPLATE_SHIFT, FULL_DAYS, ALL }

private fun OptionTier.allows(slot: FillSlot, shift: Shift, hours: Int): Boolean = when (this) {
    OptionTier.NATURAL -> shift == slot.template && hours == DAY_HOURS
    OptionTier.TEMPLATE_SHIFT -> shift == slot.template
    OptionTier.FULL_DAYS -> shift == slot.template || hours == DAY_HOURS
    OptionTier.ALL -> true
}

private fun workOption(slot: FillSlot, kind: FillDayKind, shift: Shift, hours: Int, cost: Double, extended: Boolean = false) =
    SpecialOption(
        vec = workVector(slot.date, shift, hours, slot.holidayOverride),
        paidHoliday = 0,
        absent = false,
        extended = extended,
        cost = cost,
        day = FillDay(slot.date, kind, shift, hours, templateShift = slot.template)
    )

private data class OptionEffect(
    val vec: HoursVec,
    val paidHoliday: Int,
    val absent: Boolean,
    val extended: Boolean,
    val blockIndex: Int
)

/** Opcije sa istim dejstvom na sate (npr. prva i druga smena subotom) svode se na najjeftiniju. */
private fun List<SpecialOption>.cheapestPerEffect(): List<SpecialOption> =
    groupBy { OptionEffect(it.vec, it.paidHoliday, it.absent, it.extended, it.blockIndex) }
        .values
        .map { same -> same.minByOrNull { it.cost } ?: same.first() }

/** Dan posebnog slota pokriven blokom odsustva (svakim blokom koji [accepts] dozvoljava). */
private fun blockCoverOptions(slot: FillSlot, blocks: List<FillLeaveBlock>, accepts: (FillLeaveBlock) -> Boolean): List<SpecialOption> =
    blocks.mapIndexedNotNull { index, block ->
        if (block.days <= 0 || !accepts(block)) return@mapIndexedNotNull null
        val day = FillDay(slot.date, block.kind, null, DAY_HOURS, templateShift = slot.template, sickRate = block.sickRate)
        SpecialOption(HoursVec(), 0, false, false, COST_BLOCK_ON_SPECIAL_DAY, day, blockIndex = index)
    }

private fun specialOptions(slot: FillSlot, tier: OptionTier, blocks: List<FillLeaveBlock>, allowBlockCover: Boolean): List<SpecialOption> {
    val options = mutableListOf<SpecialOption>()
    if (slot.isWeekday && !slot.isHoliday) {
        options += workOption(slot, FillDayKind.WORK, slot.template, DAY_HOURS, 0.0)
        options += SpecialOption(HoursVec(), 0, true, false, COST_ABSENT_DAY, FillDay(slot.date, FillDayKind.ABSENT, null, templateShift = slot.template))
        for (hours in 1 until DAY_HOURS) {
            options += workOption(slot, FillDayKind.WORK, slot.template, hours, shortageCost(DAY_HOURS - hours))
        }
        for (hours in DAY_HOURS + 1..MAX_SHIFT_HOURS) {
            options += workOption(slot, FillDayKind.WORK, slot.template, hours, COST_EXTENSION_DAY, extended = true)
        }
        for (shift in Shift.workShifts() - slot.template) {
            options += workOption(slot, FillDayKind.WORK, shift, DAY_HOURS, COST_SWAP_DAY)
        }
        // Godišnji, bolovanje i plaćeno odsustvo mogu da pokriju i dan koji ima praznik u smeni (treća uoči praznika).
        if (allowBlockCover) options += blockCoverOptions(slot, blocks) { true }
        return options
    }
    options += if (slot.isWeekday) {
        SpecialOption(HoursVec(), DAY_HOURS, false, false, 0.0, FillDay(slot.date, FillDayKind.HOLIDAY_OFF, null, templateShift = slot.template))
    } else {
        SpecialOption(HoursVec(), 0, false, false, 0.0, null)
    }
    // Praznik radnim danom usred bolovanja je dan bolovanja, ne plaćen praznik (listić 11/2025: 11.11. u bolovanju, bez šifre 12).
    if (slot.isWeekday && allowBlockCover) options += blockCoverOptions(slot, blocks) { it.kind == FillDayKind.SICK }
    val kind = if (slot.isHoliday) FillDayKind.HOLIDAY_WORK else FillDayKind.WEEKEND_WORK
    for (shift in Shift.workShifts()) {
        for (hours in 1..MAX_SHIFT_HOURS) {
            if (!tier.allows(slot, shift, hours)) continue
            // Praznik radnim danom kraći od smene znači i izostanak tog dana.
            val cost = COST_EXTRA_WORK_DAY +
                (if (shift != slot.template) COST_OTHER_SHIFT else 0.0) +
                (if (hours != DAY_HOURS) COST_ODD_HOURS else 0.0) +
                (if (slot.isWeekday && hours < DAY_HOURS) shortageCost(DAY_HOURS - hours) else 0.0)
            options += workOption(slot, kind, shift, hours, cost)
        }
    }
    return options.cheapestPerEffect()
}

private fun SpecialKey.plus(option: SpecialOption): SpecialKey = SpecialKey(
    regular = regular + option.vec.regular,
    holiday = holiday + option.vec.holiday,
    premium = premium + option.vec.premium,
    night = night + option.vec.night,
    sunday = sunday + option.vec.sunday,
    overtime = overtime + option.vec.overtime,
    paidHoliday = paidHoliday + option.paidHoliday,
    absent = absent || option.absent,
    extended = extended || option.extended,
    blockUse = if (option.blockIndex >= 0) blockUse + (1 shl (BLOCK_USE_BITS * option.blockIndex)) else blockUse
)

private fun Int.blockUseOf(block: Int): Int = (this shr (BLOCK_USE_BITS * block)) and MAX_BLOCK_USE

private fun SpecialKey.fits(targets: FillTargets): Boolean =
    regular <= targets.regularHours &&
        holiday <= targets.holidayWorkHours &&
        premium <= targets.holidayPremiumHours &&
        night <= targets.nightHours &&
        sunday <= targets.sundayHours &&
        overtime <= targets.overtimeHours &&
        paidHoliday <= targets.paidHolidayHours &&
        targets.blocks.indices.all { blockUse.blockUseOf(it) <= targets.blocks[it].days }

/** Najviše što preostali dani (od [from] nadalje) mogu da dodaju stavkama koje moraju tačno da se slože. */
private class RemainingCapacity(optionsBySlot: List<List<SpecialOption>>) {
    private val size = optionsBySlot.size
    val holiday = suffix(optionsBySlot) { it.vec.holiday }
    val premium = suffix(optionsBySlot) { it.vec.premium }
    val sunday = suffix(optionsBySlot) { it.vec.sunday }
    val paidHoliday = suffix(optionsBySlot) { it.paidHoliday }

    private fun suffix(optionsBySlot: List<List<SpecialOption>>, value: (SpecialOption) -> Int): IntArray {
        val result = IntArray(size + 1)
        for (i in size - 1 downTo 0) result[i] = result[i + 1] + (optionsBySlot[i].maxOfOrNull(value) ?: 0)
        return result
    }
}

/**
 * Sve kombinacije posebnih dana koje tačno daju praznike i nedelju sa liste (najjeftinija po zbiru).
 * Stanja koja ne mogu da dostignu cilj sa preostalim danima odbacuju se odmah. Preko
 * [MAX_SPECIAL_STATES] stanja (neobičan mesec) vraća prazno, pa se traži sa više opcija ili odustaje.
 */
private fun planSpecialDays(
    special: List<FillSlot>,
    targets: FillTargets,
    tier: OptionTier,
    allowBlockCover: Boolean,
    costBound: Double,
    diagnostics: FillDiagnostics?
): List<SpecialFinal> {
    val optionsBySlot = special.map { specialOptions(it, tier, targets.blocks, allowBlockCover) }
    val capacity = RemainingCapacity(optionsBySlot)
    val layers = ArrayList<HashMap<SpecialKey, SpecialStep>>(special.size + 1)
    layers += hashMapOf(SpecialKey(0, 0, 0, 0, 0, 0, 0, false, false) to SpecialStep(0.0, null, -1))
    var states = 1
    for ((slotIndex, options) in optionsBySlot.withIndex()) {
        val next = HashMap<SpecialKey, SpecialStep>()
        val after = slotIndex + 1
        for ((key, step) in layers.last()) {
            options.forEachIndexed { index, option ->
                val nextKey = key.plus(option)
                if (!nextKey.fits(targets)) return@forEachIndexed
                if (nextKey.holiday + capacity.holiday[after] < targets.holidayWorkHours ||
                    nextKey.premium + capacity.premium[after] < targets.holidayPremiumHours ||
                    nextKey.sunday + capacity.sunday[after] < targets.sundayHours ||
                    nextKey.paidHoliday + capacity.paidHoliday[after] < targets.paidHolidayHours
                ) {
                    return@forEachIndexed
                }
                val cost = step.cost + option.cost
                if (cost >= costBound) return@forEachIndexed
                val current = next[nextKey]
                if (current == null && ++states > MAX_SPECIAL_STATES) {
                    diagnostics?.tooManySpecialDays = true
                    return emptyList()
                }
                if (current == null || cost < current.cost) next[nextKey] = SpecialStep(cost, key, index)
            }
        }
        layers += next
    }
    return layers.last()
        .filter { (key, _) ->
            key.holiday == targets.holidayWorkHours &&
                key.premium == targets.holidayPremiumHours &&
                key.sunday == targets.sundayHours &&
                key.paidHoliday == targets.paidHolidayHours
        }
        .map { (key, step) ->
            val days = mutableListOf<FillDay>()
            var current: SpecialKey? = key
            for (i in special.size downTo 1) {
                val back = layers[i].getValue(requireNotNull(current))
                optionsBySlot[i - 1][back.option].day?.let { days += it }
                current = back.previous
            }
            SpecialFinal(key, step.cost, days)
        }
}

// ---------------------------------------------------------------------------------------
// Ostatak običnih radnih dana: izostanci, kraći i produženi dani, zamena smene
// ---------------------------------------------------------------------------------------

private data class ResidualPlan(
    val absentDay: Int,
    val absentNight: Int,
    val shortDay: Int,
    val shortNight: Int,
    val extensionDay: Int,
    val extensionNight: Int,
    /** > 0: dnevni dani rađeni u trećoj smeni; < 0: dani treće smene rađeni danju. */
    val swap: Int
)

private class Candidate(
    val cost: Double,
    val order: Long,
    val blockPlan: BlockPlan,
    val special: SpecialFinal,
    val residual: ResidualPlan
)

/** Najboljih [MAX_CANDIDATES] kandidata; pri istoj ceni prednost ima ranije nađeni (stabilan redosled). */
private class CandidatePool(private val bound: Double = Double.MAX_VALUE) {
    private var counter = 0L
    private val queue = PriorityQueue<Candidate>(
        compareByDescending<Candidate> { it.cost }.thenByDescending { it.order }
    )

    /** Kandidat mora da bude jeftiniji od ovoga: od [bound] (već nađeno rešenje) i od najgoreg u punom skupu. */
    fun worstAccepted(): Double =
        if (queue.size < MAX_CANDIDATES) bound else min(bound, queue.peek()?.cost ?: Double.MAX_VALUE)

    fun offer(cost: Double, blockPlan: BlockPlan, special: SpecialFinal, residual: ResidualPlan) {
        if (cost >= worstAccepted()) return
        queue += Candidate(cost, counter++, blockPlan, special, residual)
        if (queue.size > MAX_CANDIDATES) queue.poll()
    }

    fun ordered(): List<Candidate> = generateSequence { queue.poll() }.toList().reversed()
}

private class ResidualContext(
    val freeDay: Int,
    val freeNight: Int,
    val targets: FillTargets,
    val baseCost: Double,
    val blockPlan: BlockPlan,
    val special: SpecialFinal,
    val pool: CandidatePool
) {
    fun offer(cost: Double, residual: ResidualPlan) = pool.offer(cost, blockPlan, special, residual)
}

/**
 * Sve jednačine su linearne: režija = 8·slobodni − 8·izostanci − skraćenja,
 * noćni = 8·slobodni treće − 8·izostanci treće − skraćenja treće + produženja treće ± zamene,
 * prekovremeni = produženja + posebni dani.
 */
private fun solveResidual(context: ResidualContext) {
    val targets = context.targets
    val key = context.special.key
    val shortage = DAY_HOURS * (context.freeDay + context.freeNight) + key.regular - targets.regularHours
    val extension = targets.overtimeHours - key.overtime
    if (shortage < 0 || extension < 0) return
    for (absentTotal in 0..shortage / DAY_HOURS) {
        for (absentNight in 0..minOf(absentTotal, context.freeNight)) {
            val absentDay = absentTotal - absentNight
            if (absentDay > context.freeDay) continue
            for (extensionNight in 0..extension) {
                solveSwaps(context, shortage, extension, absentDay, absentNight, extensionNight)
            }
        }
    }
}

private fun solveSwaps(
    context: ResidualContext,
    shortage: Int,
    extension: Int,
    absentDay: Int,
    absentNight: Int,
    extensionNight: Int
) {
    val key = context.special.key
    val shortTotal = shortage - DAY_HOURS * (absentDay + absentNight)
    val extensionDay = extension - extensionNight
    // Noćni: 8·slobodni treće − 8·izostanci treće − skraćenja treće + produženja treće + 8·zamene = cilj.
    val base = DAY_HOURS * context.freeNight + key.night - DAY_HOURS * absentNight + extensionNight - context.targets.nightHours
    for (swap in -MAX_SWAP_DAYS..MAX_SWAP_DAYS) {
        val shortNight = base + DAY_HOURS * swap
        if (shortNight < 0 || shortNight > shortTotal) continue
        val shortDay = shortTotal - shortNight
        val nightDays = absentNight + ceilDiv(shortNight, MAX_SHORTAGE_PER_DAY) +
            ceilDiv(extensionNight, MAX_EXTENSION_PER_DAY) + maxOf(0, -swap)
        val dayDays = absentDay + ceilDiv(shortDay, MAX_SHORTAGE_PER_DAY) +
            ceilDiv(extensionDay, MAX_EXTENSION_PER_DAY) + maxOf(0, swap)
        if (nightDays > context.freeNight || dayDays > context.freeDay) continue
        val anyAbsent = absentDay + absentNight > 0 || key.absent
        // Produženje radnim danom uz cele izostanke firma bi netirala (odrada), pa to nije verovatno.
        val anyExtension = extension > 0 || key.extended
        val cost = context.baseCost +
            (absentDay + absentNight) * COST_ABSENT_DAY +
            partialDaysCost(shortDay) + partialDaysCost(shortNight) +
            extensionDaysCost(extensionDay) + extensionDaysCost(extensionNight) +
            abs(swap) * COST_SWAP_DAY +
            if (anyAbsent && anyExtension) COST_ABSENT_WITH_EXTENSION else 0.0
        context.offer(cost, ResidualPlan(absentDay, absentNight, shortDay, shortNight, extensionDay, extensionNight, swap))
    }
}

// ---------------------------------------------------------------------------------------
// Sklapanje dana
// ---------------------------------------------------------------------------------------

private fun materialize(calendar: FillCalendar, group: List<FillSlot>, candidate: Candidate, blocks: List<FillLeaveBlock>): List<FillDay> {
    val days = mutableListOf<FillDay>()
    val freeDay = mutableListOf<FillSlot>()
    val freeNight = mutableListOf<FillSlot>()
    group.forEachIndexed { index, slot ->
        val blockIndex = candidate.blockPlan.assignment[index]
        if (blockIndex >= 0) {
            val block = blocks[blockIndex]
            days += FillDay(slot.date, block.kind, null, DAY_HOURS, templateShift = slot.template, sickRate = block.sickRate)
        } else if (slot.template == Shift.THIRD) {
            freeNight += slot
        } else {
            freeDay += slot
        }
    }
    val residual = candidate.residual
    days += assignFree(freeNight, residual.absentNight, residual.shortNight, residual.extensionNight, maxOf(0, -residual.swap), Shift.FIRST)
    days += assignFree(freeDay, residual.absentDay, residual.shortDay, residual.extensionDay, maxOf(0, residual.swap), Shift.THIRD)
    days += candidate.special.days
    check(days.map { it.date }.toSet().size == days.size) { "dupli dan u rasporedu za ${calendar.month}" }
    return days.sortedBy { it.date }
}

/**
 * Slobodni dani: izostanci, kraći dani, produženi dani i zamene smene su raspoređeni ravnomerno kroz
 * mesec (tipovi naizmenično), a ostali dani su redovan rad. Lista ne kaže koji su to dani, pa je
 * ravnomeran raspored prirodniji od svih neobičnih dana na početku meseca.
 */
private fun assignFree(slots: List<FillSlot>, absent: Int, shortage: Int, extension: Int, swaps: Int, swapShift: Shift): List<FillDay> {
    val absences = List(absent) { { slot: FillSlot -> FillDay(slot.date, FillDayKind.ABSENT, null, templateShift = slot.template) } }
    val shortDays = splitEvenly(shortage, MAX_SHORTAGE_PER_DAY).map { piece ->
        { slot: FillSlot -> FillDay(slot.date, FillDayKind.WORK, slot.template, DAY_HOURS - piece, templateShift = slot.template) }
    }
    val longDays = splitEvenly(extension, MAX_EXTENSION_PER_DAY).map { piece ->
        { slot: FillSlot -> FillDay(slot.date, FillDayKind.WORK, slot.template, DAY_HOURS + piece, templateShift = slot.template) }
    }
    val swapDays = List(swaps) { { slot: FillSlot -> FillDay(slot.date, FillDayKind.WORK, swapShift, DAY_HOURS, templateShift = slot.template) } }
    val events = interleave(listOf(absences, shortDays, longDays, swapDays))
    val step = if (events.isEmpty()) 0.0 else slots.size.toDouble() / events.size
    val eventAt = events.mapIndexed { index, event -> ((index + 0.5) * step).toInt() to event }.toMap()
    return slots.mapIndexed { index, slot ->
        eventAt[index]?.invoke(slot)
            ?: FillDay(slot.date, FillDayKind.WORK, slot.template, DAY_HOURS, templateShift = slot.template)
    }
}

/** Liste naizmenično: prvi iz svake, pa drugi iz svake... */
private fun <T> interleave(lists: List<List<T>>): List<T> {
    val result = mutableListOf<T>()
    var round = 0
    while (lists.any { round < it.size }) {
        lists.forEach { list -> list.getOrNull(round)?.let { result += it } }
        round++
    }
    return result
}

/**
 * Pretraga jednog meseca: obični radni dani ([group]) i blokovi na njima, pa posebni dani. Blokovi koji
 * pokrivaju posebne radne dane smanjuju blokove na običnim danima; plan blokova se pravi jednom po tom broju.
 */
private class MonthSearch(
    private val group: List<FillSlot>,
    private val targets: FillTargets,
    private val style: BlockStyle
) {
    private val totalNight = group.count { it.template == Shift.THIRD }
    private val blockDays = targets.blocks.sumOf { it.days }
    private val plansByUse = HashMap<Int, Map<Int, BlockPlan>>()

    private fun plansFor(key: SpecialKey): Map<Int, BlockPlan> = plansByUse.getOrPut(key.blockUse) {
        val reduced = targets.blocks.mapIndexed { i, block -> block.copy(days = block.days - key.blockUse.blockUseOf(i)) }
        planBlocks(group, reduced, style)
    }

    /** Sve kombinacije posebnih dana sa blokovima i ostatkom koje daju sate sa liste; jeftinije od [pool] granice. */
    fun collect(specialFinals: List<SpecialFinal>, pool: CandidatePool) {
        for (final in specialFinals.sortedBy { it.cost }) {
            val blockPlans = plansFor(final.key)
            if (blockPlans.isEmpty()) continue
            val groupBlockDays = blockDays - targets.blocks.indices.sumOf { final.key.blockUse.blockUseOf(it) }
            for ((coveredNight, blockPlan) in blockPlans.toSortedMap()) {
                val baseCost = blockPlan.cost + final.cost
                if (baseCost >= pool.worstAccepted()) continue
                val freeNight = totalNight - coveredNight
                val freeDay = group.size - totalNight - (groupBlockDays - coveredNight)
                solveResidual(ResidualContext(freeDay, freeNight, targets, baseCost, blockPlan, final, pool))
            }
        }
    }
}

/**
 * Traži raspored; [accept] proverava kandidata punim obračunom. Prvo se traže najprirodniji posebni dani,
 * pa sve neobičniji; prvi nivo koji da rešenje daje gornju granicu, a viši nivoi traže samo jeftinije
 * (prirodnije) od njega. Kandidati sa manje neobičnih dana proveravaju se prvi; vraća najjeftiniji prihvaćen ili null.
 */
internal fun solveMonthFill(
    calendar: FillCalendar,
    targets: FillTargets,
    hints: FillHints = FillHints(),
    diagnostics: FillDiagnostics? = null,
    accept: (List<FillDay>) -> Boolean
): List<FillDay>? {
    val group = calendar.slots.filter { it.isGroupDay() }
    val special = calendar.slots.filterNot { it.isGroupDay() }
    val search = MonthSearch(group, targets, blockStyle(calendar, group, hints))
    val allowBlockCover = targets.blocks.size * BLOCK_USE_BITS < Int.SIZE_BITS - 1 &&
        special.count { it.isWeekday } <= MAX_BLOCK_USE
    val tried = HashSet<List<FillDay>>()
    var anyFinals = false
    var anyCandidates = false
    var best: List<FillDay>? = null
    var bestCost = Double.MAX_VALUE
    for (tier in OptionTier.values()) {
        val costBound = if (best == null) Double.MAX_VALUE else bestCost + COST_BOUND_MARGIN
        val specialFinals = planSpecialDays(special, targets, tier, allowBlockCover, costBound, diagnostics)
        if (specialFinals.isEmpty()) continue
        anyFinals = true
        val pool = CandidatePool(bestCost)
        search.collect(specialFinals, pool)
        for (candidate in pool.ordered()) {
            anyCandidates = true
            val days = materialize(calendar, group, candidate, targets.blocks)
            if (tried.add(days) && accept(days)) {
                best = days
                bestCost = candidate.cost
                break
            }
        }
    }
    if (best == null) {
        diagnostics?.let {
            it.noSpecialCombination = !anyFinals && !it.tooManySpecialDays
            it.noResidualCandidate = anyFinals && !anyCandidates
        }
    }
    return best
}

/** Zašto popuna nije uspela (za poruku korisniku); popunjava ga [solveMonthFill]. */
internal class FillDiagnostics {
    /** Previše vikenda i praznika za tačnu pretragu. */
    var tooManySpecialDays = false

    /** Nijedna kombinacija praznika, nedelje i plaćenog praznika ne daje sate sa liste. */
    var noSpecialCombination = false

    /** Praznici i nedelja se slažu, ali režija, noćni ili prekovremeni ne mogu sa šablonom smena. */
    var noResidualCandidate = false
}
