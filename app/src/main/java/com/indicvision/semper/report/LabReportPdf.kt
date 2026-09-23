// PDF rendering code: page coordinates and paint sizes are literal by nature
// and read clearest inline, as in PdfLayoutEngine, so MagicNumber is
// suppressed for this file.
@file:Suppress("MagicNumber", "TooManyFunctions")

package com.indicvision.semper.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.toColorInt
import java.io.OutputStream

/**
 * Draws a [LabReport.Document] as a journal sheet: a ruled page border, the
 * experiment and title centred at the top, underlined section headings, a
 * gridded observation table with its margin brackets, the worked calculation,
 * the graphs and ruled lines for the conclusion. A4 at 300 dpi, the same page
 * as [PdfLayoutEngine]; its own paints, since a lab journal is not the navy
 * metrology report.
 *
 * Blocks never split across a page except the table, which repeats its header.
 */
class LabReportPdf(
    /** The reference photo and what to draw on it; null leaves the figure out. */
    private val figure: FigureImage?,
    /** Rendered plots, keyed by [LabReport.Block.Graph.id]. */
    private val graphs: Map<String, Bitmap>,
) {
    /** The reference photo, with the analysed region and any marks in image pixels. */
    class FigureImage(
        val bitmap: Bitmap,
        val imageWidth: Int,
        val imageHeight: Int,
        val roi: RectF?,
        val marks: List<PointF> = emptyList(),
        val ring: Pair<PointF, Float>? = null,
    )

    private val pageWidth = 2480f
    private val pageHeight = 3508f
    private val margin = 170f
    private val left = margin + 40f
    private val right = pageWidth - margin - 40f
    private val width = right - left
    private val bottom = pageHeight - margin - 60f
    private val rowH = 78f

    /** The table leaves the right fifth of the line for its margin brackets. */
    private val tableWidth = width * 0.8f

    private val ink = "#1F2A44".toColorInt()
    private val rule = "#9FB7C9".toColorInt()
    private val fitInk = "#B71C1C".toColorInt()

    private val titlePaint = paint(58f, bold = true, align = Paint.Align.CENTER)
    private val headingPaint = paint(48f, bold = true)
    private val graphTitlePaint = paint(48f, bold = true, align = Paint.Align.CENTER)
    private val bodyPaint = paint(40f)
    private val boldPaint = paint(40f, bold = true)
    private val cellPaint = paint(36f, align = Paint.Align.CENTER)
    private val cellHeadPaint = paint(36f, bold = true, align = Paint.Align.CENTER)
    private val captionPaint = paint(34f, align = Paint.Align.CENTER).apply { textSkewX = -0.2f }
    private val linePaint = Paint().apply {
        color = rule
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var document: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNumber = 0

    fun write(doc: LabReport.Document, out: OutputStream) {
        val pdf = PdfDocument()
        document = pdf
        try {
            newPage()
            drawHeader(doc)
            doc.blocks.forEach { draw(it) }
            finishPage()
            pdf.writeTo(out)
        } finally {
            finishPage()
            pdf.close()
            document = null
        }
    }

    // ── pages ───────────────────────────────────────────────────────────────

    private fun newPage() {
        finishPage()
        pageNumber++
        val info = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create()
        val started = requireNotNull(document).startPage(info)
        page = started
        val c = started.canvas
        canvas = c
        c.drawColor(Color.WHITE)
        c.drawRect(margin, margin, pageWidth - margin, pageHeight - margin, gridPaint)
        // The journal's page-number box, top right.
        val box = RectF(pageWidth - margin - 260f, margin - 130f, pageWidth - margin, margin - 20f)
        c.drawRect(box, gridPaint)
        c.drawText("$pageNumber", box.centerX(), box.centerY() + 16f, cellPaint)
        y = margin + 80f
    }

    private fun finishPage() {
        page?.let { document?.finishPage(it) }
        page = null
        canvas = null
    }

    /** Starts a new page unless [height] still fits on this one. */
    private fun ensure(height: Float) {
        if (y + height > bottom) newPage()
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    private fun drawHeader(doc: LabReport.Document) {
        val c = canvas ?: return
        c.drawText(doc.experiment, pageWidth / 2f, y, titlePaint)
        y += 80f
        wrap(doc.title, titlePaint, width).forEach { line ->
            c.drawText(line, pageWidth / 2f, y, titlePaint)
            y += 72f
        }
        y += 10f
        c.drawText("${LabReportText.NAME}: ____________________", left, y, bodyPaint)
        c.drawText("${LabReportText.DATE}: ____________", left + width * 0.62f, y, bodyPaint)
        y += 50f
        c.drawLine(left, y, right, y, linePaint)
        y += 40f
    }

    private fun draw(block: LabReport.Block) {
        when (block) {
            is LabReport.Block.Heading -> heading(block.text)
            is LabReport.Block.Paragraph -> paragraph(block.text, indent = 60f)
            is LabReport.Block.Steps -> block.items.forEachIndexed { i, item ->
                paragraph("(${'a' + i}) $item", indent = 0f)
            }
            is LabReport.Block.Field -> field(block)
            is LabReport.Block.Figure -> figure(block.caption)
            is LabReport.Block.Table -> table(block)
            is LabReport.Block.Calculation -> block.lines.forEach { paragraph(it, indent = 60f) }
            is LabReport.Block.Graph -> graph(block)
            is LabReport.Block.RuledLines -> ruled(block.count)
        }
    }

    private fun heading(text: String) {
        ensure(160f)
        y += 30f
        val label = "$text:"
        canvas?.drawText(label, left, y + 48f, headingPaint)
        val underline = y + 60f
        canvas?.drawLine(left, underline, left + headingPaint.measureText(label), underline, gridPaint)
        y += 100f
    }

    private fun paragraph(text: String, indent: Float) {
        val lines = wrap(text, bodyPaint, width - indent)
        lines.forEachIndexed { i, line ->
            ensure(58f)
            canvas?.drawText(line, left + if (i == 0) indent else 0f, y + 40f, bodyPaint)
            y += 58f
        }
        y += 12f
    }

    private fun field(block: LabReport.Block.Field) {
        ensure(70f)
        val label = "${block.label} = "
        canvas?.drawText(label, left, y + 40f, bodyPaint)
        val x = left + bodyPaint.measureText(label)
        val value = block.value
        if (value == null) {
            canvas?.drawLine(x, y + 46f, minOf(x + 520f, right), y + 46f, gridPaint)
        } else {
            canvas?.drawText(value, x, y + 40f, boldPaint)
        }
        y += 70f
    }

    private fun figure(caption: String) {
        val image = figure ?: return
        val bmp = image.bitmap
        val maxH = 1100f
        val scale = minOf(width * 0.8f / bmp.width, maxH / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        ensure(h + 110f)
        val dest = RectF(pageWidth / 2f - w / 2f, y + 20f, pageWidth / 2f + w / 2f, y + 20f + h)
        val c = canvas ?: return
        c.drawBitmap(bmp, null, dest, bitmapPaint)
        c.drawRect(dest, gridPaint)
        val sx = w / image.imageWidth
        val sy = h / image.imageHeight
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fitInk
            strokeWidth = 5f
            style = Paint.Style.STROKE
        }
        image.roi?.let { roi ->
            val box = RectF(
                dest.left + roi.left * sx,
                dest.top + roi.top * sy,
                dest.left + roi.right * sx,
                dest.top + roi.bottom * sy,
            )
            c.drawRect(box, markPaint)
        }
        image.marks.forEach { m ->
            val cx = dest.left + m.x * sx
            val cy = dest.top + m.y * sy
            c.drawLine(cx - 24f, cy, cx + 24f, cy, markPaint)
            c.drawLine(cx, cy - 24f, cx, cy + 24f, markPaint)
        }
        image.ring?.let { (centre, radiusPx) ->
            c.drawCircle(dest.left + centre.x * sx, dest.top + centre.y * sy, maxOf(radiusPx * sx, 8f), markPaint)
        }
        y = dest.bottom + 60f
        c.drawText(caption, pageWidth / 2f, y, captionPaint)
        y += 50f
    }

    /** A gridded table, split across pages with its header repeated. */
    private fun table(block: LabReport.Block.Table) {
        val cols = block.weights.map { it * tableWidth }
        var row = 0
        do {
            if (row > 0) newPage() else ensure(rowH * 3)
            y += 20f
            tableRow(block.headers, cols, cellHeadPaint)
            val pageTop = y
            val fromRow = row
            while (row < block.rows.size && y + rowH <= bottom) {
                tableRow(block.rows[row], cols, cellPaint)
                row++
            }
            brackets(block.brackets, fromRow until row, pageTop)
            y += 30f
        } while (row < block.rows.size)
    }

    private fun tableRow(cells: List<String>, cols: List<Float>, paint: Paint) {
        val c = canvas ?: return
        var x = left
        cells.forEachIndexed { i, cell ->
            val w = cols.getOrElse(i) { 0f }
            c.drawRect(x, y, x + w, y + rowH, gridPaint)
            c.drawText(cell, x + w / 2f, y + rowH / 2f + 13f, paint)
            x += w
        }
        y += rowH
    }

    /** The "}"-style margin labels of the handwritten table, clipped to the [rows] on this page. */
    private fun brackets(brackets: List<LabReport.Bracket>, rows: IntRange, top: Float) {
        val c = canvas ?: return
        brackets.forEach { b ->
            val first = maxOf(b.first, rows.first)
            val last = minOf(b.last, rows.last)
            if (first > last) return@forEach
            val y0 = top + (first - rows.first) * rowH + 10f
            val y1 = top + (last - rows.first + 1) * rowH - 10f
            val bx = left + tableWidth + 30f
            c.drawLine(bx, y0, bx + 20f, y0, gridPaint)
            c.drawLine(bx + 20f, y0, bx + 20f, y1, gridPaint)
            c.drawLine(bx, y1, bx + 20f, y1, gridPaint)
            c.drawLine(bx + 20f, (y0 + y1) / 2f, bx + 40f, (y0 + y1) / 2f, gridPaint)
            c.drawText(b.label, bx + 55f, (y0 + y1) / 2f + 13f, bodyPaint)
        }
    }

    private fun graph(block: LabReport.Block.Graph) {
        val bmp = graphs[block.id] ?: return
        val scale = width / bmp.width
        val h = minOf(bmp.height * scale, 1500f)
        val w = bmp.width * (h / bmp.height)
        ensure(h + 170f)
        val c = canvas ?: return
        y += 20f
        c.drawText(block.title, pageWidth / 2f, y + 48f, graphTitlePaint)
        y += 90f
        val dest = RectF(pageWidth / 2f - w / 2f, y, pageWidth / 2f + w / 2f, y + h)
        c.drawBitmap(bmp, null, dest, bitmapPaint)
        block.annotation?.let { text ->
            val tw = boldPaint.measureText(text)
            val box = RectF(dest.right - tw - 110f, dest.top + 40f, dest.right - 50f, dest.top + 130f)
            c.drawRect(box, Paint().apply { color = Color.WHITE })
            c.drawRect(box, gridPaint)
            c.drawText(text, box.left + 30f, box.centerY() + 14f, boldPaint)
        }
        y = dest.bottom + 50f
    }

    private fun ruled(count: Int) {
        repeat(count) {
            ensure(80f)
            y += 80f
            canvas?.drawLine(left, y, right, y, linePaint)
        }
        y += 30f
    }

    // ── text ────────────────────────────────────────────────────────────────

    private fun paint(size: Float, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = size
            textAlign = align
            typeface = Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /** Greedy word wrap; a single word wider than [maxWidth] gets its own line. */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        text.split(' ').forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }
}
