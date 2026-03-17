package com.rafad.indicvisiondic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument

class PdfLayoutEngine(private val pdfDocument: PdfDocument) {
    val pageWidth = 2480f
    val pageHeight = 3508f
    val margin = 150f
    val contentWidth = pageWidth - (margin * 2)

    private var currentPage: PdfDocument.Page? = null
    var canvas: Canvas? = null
        private set
    var cursorY = 0f
        private set
    private var pageNumber = 0

    // Typography
    private val h1Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 35, 126); textSize = 100f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val h2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 60f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
    private val boldBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; strokeWidth = 4f }
    private val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 38f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    fun newPage(): Canvas {
        currentPage?.let { pdfDocument.finishPage(it) }
        pageNumber++
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create()
        val page = pdfDocument.startPage(pageInfo)
        currentPage = page
        canvas = page.canvas
        cursorY = margin + 100f
        return page.canvas
    }

    fun finishCurrentPage() {
        currentPage?.let { pdfDocument.finishPage(it) }
        currentPage = null
        canvas = null
    }

    fun ensureSpace(requiredHeight: Float) {
        if (cursorY + requiredHeight > pageHeight - margin) newPage()
    }

    fun drawTitle(title: String) {
        ensureSpace(150f)
        canvas?.drawText(title, margin, cursorY, h1Paint)
        cursorY += 80f
        canvas?.drawLine(margin, cursorY, pageWidth - margin, cursorY, linePaint)
        cursorY += 80f
    }

    fun drawSectionHeader(title: String) {
        ensureSpace(120f)
        canvas?.drawText(title, margin, cursorY, h2Paint)
        cursorY += 80f
    }

    fun drawKeyValue(key: String, value: String) {
        ensureSpace(60f)
        canvas?.drawText(key, margin, cursorY, boldBodyPaint)
        canvas?.drawText(value, margin + 650f, cursorY, bodyPaint)
        cursorY += 60f
    }

    fun drawImage(bitmap: Bitmap) {
        val availableHeight = pageHeight - cursorY - margin
        val scale = contentWidth / bitmap.width
        var drawHeight = bitmap.height * scale

        if (drawHeight > availableHeight) {
            newPage()
            drawHeight = bitmap.height * scale
        }

        val destRect = RectF(margin, cursorY, margin + contentWidth, cursorY + drawHeight)
        canvas?.drawBitmap(bitmap, null, destRect, null)
        canvas?.drawRect(destRect, Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f })
        cursorY += drawHeight + 80f
    }

    fun drawTable(headers: List<String>, rows: List<List<String>>, colWeights: List<Float>) {
        val rowHeight = 80f
        ensureSpace(rowHeight * (rows.size + 1))

        val colWidths = colWeights.map { it * contentWidth }
        var currentX = margin

        // Draw Header
        canvas?.drawRect(margin, cursorY - 60f, pageWidth - margin, cursorY + 20f, Paint().apply { color = Color.rgb(26, 35, 126) })
        for ((i, header) in headers.withIndex()) {
            canvas?.drawText(header, currentX + 20f, cursorY - 10f, tableHeaderPaint)
            currentX += colWidths[i]
        }
        cursorY += 40f

        // Draw Rows
        val rowBgPaint = Paint().apply { color = Color.rgb(245, 245, 250) }
        for ((rowIndex, row) in rows.withIndex()) {
            currentX = margin
            if (rowIndex % 2 == 0) canvas?.drawRect(margin, cursorY - 40f, pageWidth - margin, cursorY + 40f, rowBgPaint)

            for ((colIndex, cell) in row.withIndex()) {
                canvas?.drawText(cell, currentX + 20f, cursorY + 15f, bodyPaint)
                currentX += colWidths[colIndex]
            }
            cursorY += rowHeight
        }
        cursorY += 40f
    }

    fun advanceY(amount: Float) { cursorY += amount }
}