package com.pacemckinney.tally.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.pacemckinney.tally.engine.Report
import java.io.File
import java.io.OutputStream

/** Draws the report with Android's built-in PdfDocument (US Letter, 72 points per inch). */
private class PdfSurface(private val doc: PdfDocument) : Surface {
    override val width = 612f
    override val height = 792f
    private var pageNo = 0
    private var page: PdfDocument.Page = start()
    private val canvas: Canvas get() = page.canvas

    private val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun start(): PdfDocument.Page {
        pageNo++
        return doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
    }

    private fun paint(st: Style) = textPaint.apply {
        typeface = if (st.bold) bold else regular
        textSize = st.size
        color = st.color
    }

    override fun text(s: String, x: Float, y: Float, style: Style) { canvas.drawText(s, x, y, paint(style)) }
    override fun measure(s: String, style: Style): Float = paint(style).measureText(s)
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        stroke.color = color; stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        canvas.drawRect(x, y, x + w, y + h, fill)
    }
    override fun newPage() { doc.finishPage(page); page = start() }
    fun finish() = doc.finishPage(page)
}

object PdfExporter {
    fun write(report: Report, options: ReportOptions, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val surface = PdfSurface(doc)
            ReportRenderer(surface, report, options).render()
            surface.finish()
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Writes into the app's private cache (shared through FileProvider), replacing older exports. */
    fun writeToCache(context: Context, report: Report, options: ReportOptions): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, ReportRenderer.fileName(report.generated))
        f.outputStream().use { write(report, options, it) }
        return f
    }
}
