package com.rad.shifttracker

import android.util.Log
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Holidays"

data class Holiday(
    val month: Int,
    val day: Int,
    val name: String,
    val category: HolidayCategory,
    val isSubstitute: Boolean
)

enum class HolidayCategory(val csvValue: String) {
    STATE("drzavni"),
    RELIGIOUS("verski"),
    STATE_SUBSTITUTE("drzavni_preneti"),
    STATE_AND_RELIGIOUS("drzavni+verski");

    companion object {
        fun fromCsv(value: String): HolidayCategory {
            return values().firstOrNull { it.csvValue == value } ?: STATE
        }
    }
}

enum class HolidayEntrySource {
    OFFICIAL,
    MANUAL
}

data class HolidayEntry(
    val year: Int,
    val month: Int,
    val day: Int,
    val name: String,
    val isActive: Boolean,
    val source: HolidayEntrySource
)

private data class YearHolidayData(
    val list: List<Holiday>,
    val dateIndex: Set<LocalDate>
)

private val holidayCache = ConcurrentHashMap<Int, YearHolidayData>()
private val loggedEmptyHolidayYears = ConcurrentHashMap.newKeySet<Int>()
private val embeddedHolidayMap: Map<Int, List<Holiday>> by lazy {
    EMBEDDED_HOLIDAYS_CSV
        .trimIndent()
        .lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .map(::parseEmbeddedHolidayCsvLine)
        .groupBy { it.date.year }
        .mapValues { (_, rows) ->
            rows.map { row ->
                Holiday(
                    month = row.date.monthValue,
                    day = row.date.dayOfMonth,
                    name = row.name,
                    category = row.category,
                    isSubstitute = row.isSubstitute
                )
            }.sortedWith(compareBy({ it.month }, { it.day }))
        }
}

private data class EmbeddedHolidayRow(
    val date: LocalDate,
    val name: String,
    val category: HolidayCategory,
    val weekday: String,
    val isSubstitute: Boolean
)

/**
 * Calculates Orthodox Easter date using the Meeus Julian algorithm.
 * The algorithm computes the date on the Julian calendar, then converts to Gregorian.
 *
 * The Julian-to-Gregorian offset varies by century:
 * - 1900-2099: 13 days
 * - 2100-2199: 14 days
 * - etc.
 */
fun orthodoxEasterSunday(year: Int): LocalDate {
    // Meeus Julian algorithm for computing Easter on Julian calendar
    val a = year % 4
    val b = year % 7
    val c = year % 19
    val d = (19 * c + 15) % 30
    val e = (2 * a + 4 * b - d + 34) % 7
    val month = (d + e + 114) / 31
    val day = ((d + e + 114) % 31) + 1

    // Julian Easter date (interpreted as Gregorian for LocalDate)
    val julianEaster = LocalDate.of(year, month, day)

    // Calculate Julian-to-Gregorian offset for the given year
    // Formula: century - century/4 - 2 (where century = year/100)
    val century = year / 100
    val julianToGregorianOffset = (century - century / 4 - 2).toLong()

    return julianEaster.plusDays(julianToGregorianOffset)
}

fun getSerbianHolidays(year: Int): List<Holiday> = cachedHolidayData(year).list

fun isSerbianHoliday(year: Int, month: Int, day: Int): Boolean {
    val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return false
    return date in cachedHolidayData(year).dateIndex
}

fun getHolidayName(year: Int, month: Int, day: Int): String? {
    return getSerbianHolidays(year).firstOrNull { it.month == month && it.day == day }?.name
}

private fun cachedHolidayData(year: Int): YearHolidayData {
    holidayCache[year]?.let { return it }

    val holidays = embeddedHolidayMap[year].orEmpty()
    if (holidays.isEmpty() && loggedEmptyHolidayYears.add(year)) {
        Log.w(TAG, "Nema kalendara praznika za godinu $year (ugrađeni CSV pokriva 2025–2035).")
    }
    val dateIndex = holidays.mapTo(HashSet(holidays.size)) { LocalDate.of(year, it.month, it.day) }
    val data = YearHolidayData(list = holidays, dateIndex = dateIndex)

    // Ograničenje keša na 5 godina - izbaci najstariju ako prelazi limit
    if (holidayCache.size >= 5 && !holidayCache.containsKey(year)) {
        val oldest = holidayCache.keys.minOrNull()
        if (oldest != null) {
            holidayCache.remove(oldest)
        }
    }
    holidayCache[year] = data
    return data
}

private fun parseEmbeddedHolidayCsvLine(line: String): EmbeddedHolidayRow {
    val fields = parseCsvLine(line)
    require(fields.size == EMBEDDED_HOLIDAY_FIELD_COUNT) {
        "Neispravan red praznika: $line"
    }
    val date = LocalDate.parse(fields[0])
    val weekday = fields[3]
    require(weekday == SERBIAN_WEEKDAY_ABBREVIATIONS[date.dayOfWeek.ordinal]) {
        "Neispravan dan u nedelji za praznik: $line"
    }
    return EmbeddedHolidayRow(
        date = date,
        name = fields[1],
        category = HolidayCategory.fromCsv(fields[2]),
        weekday = weekday,
        isSubstitute = fields[4].toBooleanStrictOrNull() ?: false
    )
}

private fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                fields += current.toString()
                current.setLength(0)
            }
            else -> current.append(char)
        }
        index += 1
    }

    fields += current.toString()
    return fields
}

private const val EMBEDDED_HOLIDAY_FIELD_COUNT = 5

private val SERBIAN_WEEKDAY_ABBREVIATIONS = listOf("pon", "uto", "sre", "čet", "pet", "sub", "ned")

private const val EMBEDDED_HOLIDAYS_CSV = """
date,name,category,weekday,is_substitute
2025-01-01,"Nova godina",drzavni,sre,false
2025-01-02,"Nova godina",drzavni,čet,false
2025-01-07,"Božić (pravoslavni)",verski,uto,false
2025-02-15,"Sretenje / Dan državnosti",drzavni,sub,false
2025-02-16,"Sretenje / Dan državnosti",drzavni,ned,false
2025-02-17,"Sretenje / Dan državnosti (preneti neradni dan)",drzavni_preneti,pon,true
2025-04-18,"Veliki petak",verski,pet,false
2025-04-19,"Velika subota",verski,sub,false
2025-04-20,"Vaskrs",verski,ned,false
2025-04-21,"Vaskršnji ponedeljak",verski,pon,false
2025-05-01,"Praznik rada",drzavni,čet,false
2025-05-02,"Praznik rada",drzavni,pet,false
2025-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,uto,false
2026-01-01,"Nova godina",drzavni,čet,false
2026-01-02,"Nova godina",drzavni,pet,false
2026-01-07,"Božić (pravoslavni)",verski,sre,false
2026-02-15,"Sretenje / Dan državnosti",drzavni,ned,false
2026-02-16,"Sretenje / Dan državnosti",drzavni,pon,false
2026-02-17,"Sretenje / Dan državnosti (preneti neradni dan)",drzavni_preneti,uto,true
2026-04-10,"Veliki petak",verski,pet,false
2026-04-11,"Velika subota",verski,sub,false
2026-04-12,"Vaskrs",verski,ned,false
2026-04-13,"Vaskršnji ponedeljak",verski,pon,false
2026-05-01,"Praznik rada",drzavni,pet,false
2026-05-02,"Praznik rada",drzavni,sub,false
2026-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,sre,false
2027-01-01,"Nova godina",drzavni,pet,false
2027-01-02,"Nova godina",drzavni,sub,false
2027-01-07,"Božić (pravoslavni)",verski,čet,false
2027-02-15,"Sretenje / Dan državnosti",drzavni,pon,false
2027-02-16,"Sretenje / Dan državnosti",drzavni,uto,false
2027-04-30,"Veliki petak",verski,pet,false
2027-05-01,"Praznik rada + Velika subota",drzavni+verski,sub,false
2027-05-02,"Praznik rada + Vaskrs",drzavni+verski,ned,false
2027-05-03,"Vaskršnji ponedeljak",verski,pon,false
2027-05-04,"Praznik rada (preneti neradni dan)",drzavni_preneti,uto,true
2027-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,čet,false
2028-01-01,"Nova godina",drzavni,sub,false
2028-01-02,"Nova godina",drzavni,ned,false
2028-01-03,"Nova godina (preneti neradni dan)",drzavni_preneti,pon,true
2028-01-07,"Božić (pravoslavni)",verski,pet,false
2028-02-15,"Sretenje / Dan državnosti",drzavni,uto,false
2028-02-16,"Sretenje / Dan državnosti",drzavni,sre,false
2028-04-14,"Veliki petak",verski,pet,false
2028-04-15,"Velika subota",verski,sub,false
2028-04-16,"Vaskrs",verski,ned,false
2028-04-17,"Vaskršnji ponedeljak",verski,pon,false
2028-05-01,"Praznik rada",drzavni,pon,false
2028-05-02,"Praznik rada",drzavni,uto,false
2028-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,sub,false
2029-01-01,"Nova godina",drzavni,pon,false
2029-01-02,"Nova godina",drzavni,uto,false
2029-01-07,"Božić (pravoslavni)",verski,ned,false
2029-02-15,"Sretenje / Dan državnosti",drzavni,čet,false
2029-02-16,"Sretenje / Dan državnosti",drzavni,pet,false
2029-04-06,"Veliki petak",verski,pet,false
2029-04-07,"Velika subota",verski,sub,false
2029-04-08,"Vaskrs",verski,ned,false
2029-04-09,"Vaskršnji ponedeljak",verski,pon,false
2029-05-01,"Praznik rada",drzavni,uto,false
2029-05-02,"Praznik rada",drzavni,sre,false
2029-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,ned,false
2029-11-12,"Dan primirja u Prvom svetskom ratu (preneti neradni dan)",drzavni_preneti,pon,true
2030-01-01,"Nova godina",drzavni,uto,false
2030-01-02,"Nova godina",drzavni,sre,false
2030-01-07,"Božić (pravoslavni)",verski,pon,false
2030-02-15,"Sretenje / Dan državnosti",drzavni,pet,false
2030-02-16,"Sretenje / Dan državnosti",drzavni,sub,false
2030-04-26,"Veliki petak",verski,pet,false
2030-04-27,"Velika subota",verski,sub,false
2030-04-28,"Vaskrs",verski,ned,false
2030-04-29,"Vaskršnji ponedeljak",verski,pon,false
2030-05-01,"Praznik rada",drzavni,sre,false
2030-05-02,"Praznik rada",drzavni,čet,false
2030-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,pon,false
2031-01-01,"Nova godina",drzavni,sre,false
2031-01-02,"Nova godina",drzavni,čet,false
2031-01-07,"Božić (pravoslavni)",verski,uto,false
2031-02-15,"Sretenje / Dan državnosti",drzavni,sub,false
2031-02-16,"Sretenje / Dan državnosti",drzavni,ned,false
2031-02-17,"Sretenje / Dan državnosti (preneti neradni dan)",drzavni_preneti,pon,true
2031-04-11,"Veliki petak",verski,pet,false
2031-04-12,"Velika subota",verski,sub,false
2031-04-13,"Vaskrs",verski,ned,false
2031-04-14,"Vaskršnji ponedeljak",verski,pon,false
2031-05-01,"Praznik rada",drzavni,čet,false
2031-05-02,"Praznik rada",drzavni,pet,false
2031-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,uto,false
2032-01-01,"Nova godina",drzavni,čet,false
2032-01-02,"Nova godina",drzavni,pet,false
2032-01-07,"Božić (pravoslavni)",verski,sre,false
2032-02-15,"Sretenje / Dan državnosti",drzavni,ned,false
2032-02-16,"Sretenje / Dan državnosti",drzavni,pon,false
2032-02-17,"Sretenje / Dan državnosti (preneti neradni dan)",drzavni_preneti,uto,true
2032-04-30,"Veliki petak",verski,pet,false
2032-05-01,"Praznik rada + Velika subota",drzavni+verski,sub,false
2032-05-02,"Praznik rada + Vaskrs",drzavni+verski,ned,false
2032-05-03,"Vaskršnji ponedeljak",verski,pon,false
2032-05-04,"Praznik rada (preneti neradni dan)",drzavni_preneti,uto,true
2032-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,čet,false
2033-01-01,"Nova godina",drzavni,sub,false
2033-01-02,"Nova godina",drzavni,ned,false
2033-01-03,"Nova godina (preneti neradni dan)",drzavni_preneti,pon,true
2033-01-07,"Božić (pravoslavni)",verski,pet,false
2033-02-15,"Sretenje / Dan državnosti",drzavni,uto,false
2033-02-16,"Sretenje / Dan državnosti",drzavni,sre,false
2033-04-22,"Veliki petak",verski,pet,false
2033-04-23,"Velika subota",verski,sub,false
2033-04-24,"Vaskrs",verski,ned,false
2033-04-25,"Vaskršnji ponedeljak",verski,pon,false
2033-05-01,"Praznik rada",drzavni,ned,false
2033-05-02,"Praznik rada",drzavni,pon,false
2033-05-03,"Praznik rada (preneti neradni dan)",drzavni_preneti,uto,true
2033-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,pet,false
2034-01-01,"Nova godina",drzavni,ned,false
2034-01-02,"Nova godina",drzavni,pon,false
2034-01-03,"Nova godina (preneti neradni dan)",drzavni_preneti,uto,true
2034-01-07,"Božić (pravoslavni)",verski,sub,false
2034-02-15,"Sretenje / Dan državnosti",drzavni,sre,false
2034-02-16,"Sretenje / Dan državnosti",drzavni,čet,false
2034-04-07,"Veliki petak",verski,pet,false
2034-04-08,"Velika subota",verski,sub,false
2034-04-09,"Vaskrs",verski,ned,false
2034-04-10,"Vaskršnji ponedeljak",verski,pon,false
2034-05-01,"Praznik rada",drzavni,pon,false
2034-05-02,"Praznik rada",drzavni,uto,false
2034-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,sub,false
2035-01-01,"Nova godina",drzavni,pon,false
2035-01-02,"Nova godina",drzavni,uto,false
2035-01-07,"Božić (pravoslavni)",verski,ned,false
2035-02-15,"Sretenje / Dan državnosti",drzavni,čet,false
2035-02-16,"Sretenje / Dan državnosti",drzavni,pet,false
2035-04-27,"Veliki petak",verski,pet,false
2035-04-28,"Velika subota",verski,sub,false
2035-04-29,"Vaskrs",verski,ned,false
2035-04-30,"Vaskršnji ponedeljak",verski,pon,false
2035-05-01,"Praznik rada",drzavni,uto,false
2035-05-02,"Praznik rada",drzavni,sre,false
2035-11-11,"Dan primirja u Prvom svetskom ratu",drzavni,ned,false
2035-11-12,"Dan primirja u Prvom svetskom ratu (preneti neradni dan)",drzavni_preneti,pon,true
"""
