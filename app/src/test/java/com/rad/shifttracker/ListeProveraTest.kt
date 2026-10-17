package com.rad.shifttracker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.rad.shifttracker.listici.PayslipParseResult
import com.rad.shifttracker.listici.PayslipPayBaseResult
import com.rad.shifttracker.listici.PayslipSideRunKind
import com.rad.shifttracker.listici.drivePayslipFileName
import com.rad.shifttracker.listici.readPayslip

/**
 * Provera skupljenih lista (`alati/proveri-liste.ps1`): svaki PDF iz foldera [ENV_DIR] se čita istim
 * kodom kao u aplikaciji, a izveštaj ide u [ENV_OUT]. Bez tih promenljivih se preskače.
 */
@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ListeProveraTest {
    @Test
    fun proveriListe() {
        val dir = System.getenv(ENV_DIR)?.let(::File)
        val out = System.getenv(ENV_OUT)?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && out != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdfs = dir!!.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        val report = StringBuilder()
        var problems = 0
        for (pdf in pdfs) {
            val result = readPayslip(context, pdf.readBytes())
            val (summary, warnings) = describe(result)
            if (warnings.isNotEmpty()) problems++
            report.appendLine("${if (warnings.isEmpty()) "OK " else "!! "} ${pdf.name}")
            report.appendLine("    $summary")
            warnings.forEach { report.appendLine("    !! $it") }
        }
        report.appendLine()
        report.appendLine("Lista: ${pdfs.size}, sa problemom: $problems")
        out!!.writeText(report.toString())
    }

    private fun describe(result: PayslipParseResult): Pair<String, List<String>> {
        val warnings = mutableListOf<String>()
        val summary = when (result) {
            is PayslipParseResult.Failure -> {
                warnings += "nije pročitana kao lista (${result.reason.javaClass.simpleName})"
                "—"
            }
            is PayslipParseResult.SideRun -> {
                if (result.paidNet == null) warnings += "nema „Ukupno uplaćeno“"
                if (result.kind == PayslipSideRunKind.OTHER) warnings += "nepoznat obračun"
                if (result.kind == PayslipSideRunKind.TRANSPORT) {
                    val daily = result.transportDaily
                    if (daily == null) warnings += "prevoz po danu nije pročitan"
                    else if (daily.stripTrailingZeros().scale() > 0) warnings += "prevoz po danu nije ceo broj ($daily)"
                }
                buildString {
                    append("${result.kind} ${month(result.month.year, result.month.month)} ${result.runLabel.orEmpty()}")
                    append(", uplaćeno ${result.paidNet.text()}")
                    append(", isplata ${result.paymentMonth?.let { month(it.year, it.month) } ?: "?"}")
                    result.transportDaily?.let { append(", prevoz/dan ${it.text()}") }
                }
            }
            is PayslipParseResult.Success -> {
                val slip = result.payslip
                if (slip.items.isEmpty()) warnings += "nema stavki"
                if (slip.netTotal == null) warnings += "nema neta"
                if (slip.grossTotal == null) warnings += "nema bruta"
                if (slip.fundHours == null) warnings += "nema fonda sati"
                if (slip.payBase !is PayslipPayBaseResult.Success) warnings += "osnovica: ${slip.payBase.javaClass.simpleName}"
                buildString {
                    append("KONAČNA ${month(slip.month.year, slip.month.month)} ${slip.runLabel.orEmpty()}")
                    append(", fond ${slip.fundHours ?: "?"} h, bruto ${slip.grossTotal.text()}, neto ${slip.netTotal.text()}")
                    append(", uplaćeno ${slip.paidNet.text()}, stavki ${slip.items.size}")
                }
            }
        }
        return "$summary → Drive: ${drivePayslipFileName(result) ?: "ne šalje se"}" to warnings
    }

    private fun month(year: Int, month: Int) = "${month.toString().padStart(2, '0')}/$year"

    private fun BigDecimal?.text() = this?.toPlainString() ?: "?"

    private companion object {
        const val ENV_DIR = "RAD_LISTE_DIR"
        const val ENV_OUT = "RAD_LISTE_OUT"
    }
}
