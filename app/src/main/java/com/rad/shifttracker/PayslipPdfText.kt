package com.rad.shifttracker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** Jedan znak sa stranice: levi rub, bazna linija, širina i veličina fonta (u tačkama). */
internal data class PdfGlyph(
    val x: Float,
    val y: Float,
    val width: Float,
    val fontSize: Float,
    val text: String
)

/**
 * Čita PDF listić izabran preko Storage Access Framework-a i računa osnovicu.
 * Sve ostaje u memoriji: PDF se ne kešira na disk, a tekst se nigde ne loguje.
 */
internal fun readPayslip(context: Context, uri: Uri): PayslipParseResult =
    readPayslipFrom(context) { context.contentResolver.openInputStream(uri) }

/** Isto, za PDF prilog preuzet iz Gmail-a (samo u memoriji). */
internal fun readPayslip(context: Context, bytes: ByteArray): PayslipParseResult =
    readPayslipFrom(context) { bytes.inputStream() }

private fun readPayslipFrom(context: Context, open: () -> InputStream?): PayslipParseResult =
    readPayslipLines(context, open)?.let { lines -> runPayslipSafely { parsePayslip(lines) } }
        ?: PayslipParseResult.Failure(PayslipPayBaseResult.InvalidPdf)

/**
 * Sve što se radi sa listićem (čitanje PDF-a, poređenje, popunjavanje meseca) prolazi kroz ovo:
 * greška, pa i nedostatak memorije ili preduboko ugnežđen PDF, daje null umesto pada aplikacije.
 * Otkazivanje korutine se ne guta.
 */
internal inline fun <T> runPayslipSafely(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
} catch (e: StackOverflowError) {
    null
}

/** Ime fajla za poruku o grešci; ako nije dostupno, koristi se opšti naziv. */
internal fun payslipDisplayName(context: Context, uri: Uri): String = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (e: Exception) {
    null
} ?: "PDF fajl"

/** Listić je PDF od par stotina KB i jedne-dve strane; veće se ne čita. */
private const val MAX_PAYSLIP_PDF_BYTES = 10 * 1024 * 1024
private const val MAX_PAYSLIP_PAGES = 10

// Oštećen, šifrovan, prevelik ili nečitljiv fajl daje null; sadržaj greške se namerno ne loguje.
private fun readPayslipLines(context: Context, open: () -> InputStream?): List<String>? = runPayslipSafely {
    PDFBoxResourceLoader.init(context.applicationContext)
    open()?.use { input ->
        readAtMost(input, MAX_PAYSLIP_PDF_BYTES)?.let { bytes -> extractPayslipTextLines(bytes.inputStream()) }
    }
}

/** Ceo sadržaj toka, ili null ako je duži od [limit] bajtova. */
internal fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        if (output.size() + read > limit) return null
        output.write(buffer, 0, read)
    }
}

/**
 * Tekst listića kao redovi, složeni po baznoj liniji znakova.
 *
 * Ugrađeno `sortByPosition` u PdfBox-u ovde ne radi: font listića prijavljuje visinu
 * znaka od ~50 pt (na slovu od 8 pt), pa se susedni redovi tabele stapaju i slova se
 * prepliću. Zato se redovi grade isključivo od y-koordinate bazne linije.
 */
internal fun extractPayslipTextLines(input: InputStream): List<String> {
    return PDDocument.load(input, MemoryUsageSetting.setupMainMemoryOnly()).use { doc ->
        val lines = mutableListOf<String>()
        for (page in 1..minOf(doc.numberOfPages, MAX_PAYSLIP_PAGES)) {
            val collector = GlyphCollector()
            collector.startPage = page
            collector.endPage = page
            collector.getText(doc)
            lines += buildPdfTextLines(collector.glyphs)
        }
        lines
    }
}

private class GlyphCollector : PDFTextStripper() {
    val glyphs = mutableListOf<PdfGlyph>()

    // Ne zove super: PdfBox-ovo grupisanje u redove je upravo ono što se zaobilazi.
    override fun processTextPosition(text: TextPosition) {
        val unicode = text.unicode ?: return
        glyphs += PdfGlyph(
            x = text.xDirAdj,
            y = text.yDirAdj,
            width = text.widthDirAdj,
            fontSize = text.fontSizeInPt,
            text = unicode
        )
    }
}

private const val LINE_Y_TOLERANCE_PT = 2.5f
private const val WORD_GAP_FONT_RATIO = 0.2f
private const val DUPLICATE_OFFSET_PT = 1.0f

/**
 * Znakovi sa (skoro) istom baznom linijom čine red; u redu se sortiraju po x,
 * a razmak se umeće kad je praznina između znakova veća od dela veličine fonta.
 * Ponovljen isti znak na istom mestu (lažni bold) se preskače.
 */
internal fun buildPdfTextLines(glyphs: List<PdfGlyph>): List<String> {
    val rows = mutableListOf<MutableList<PdfGlyph>>()
    for (glyph in glyphs.sortedBy { it.y }) {
        val row = rows.lastOrNull()
        if (row != null && abs(row.first().y - glyph.y) <= LINE_Y_TOLERANCE_PT) {
            row += glyph
        } else {
            rows += mutableListOf(glyph)
        }
    }
    return rows.mapNotNull { row ->
        val builder = StringBuilder()
        var previous: PdfGlyph? = null
        for (glyph in row.sortedBy { it.x }) {
            val prev = previous
            if (prev != null) {
                val isDuplicate = glyph.text == prev.text &&
                    abs(glyph.x - prev.x) < DUPLICATE_OFFSET_PT &&
                    abs(glyph.y - prev.y) < DUPLICATE_OFFSET_PT
                if (isDuplicate) continue
                val gap = glyph.x - (prev.x + prev.width)
                if (gap > glyph.fontSize * WORD_GAP_FONT_RATIO && !builder.endsWith(" ")) {
                    builder.append(' ')
                }
            }
            builder.append(glyph.text)
            previous = glyph
        }
        builder.toString().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }
}
