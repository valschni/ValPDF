package de.pdfapp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Path
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore as PdfRestore
import com.tom_roush.pdfbox.contentstream.operator.state.Save as PdfSave
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

private const val REQ_OPEN = 1
private const val REQ_MERGE = 2
private const val REQ_IMAGES = 3
private const val REQ_SAVE = 4
private const val REQ_SIGIMG = 5
private const val MAX_MATCHES = 3000

/** Ein Suchtreffer: Seite und die Markierungs-Rechtecke (0..1 der Seitengröße). */
class Match(val page: Int, val rects: List<RectF>)

/** Wird geworfen, um eine laufende Textauswertung abzubrechen. */
class SearchStop : RuntimeException()

/** Wird geworfen, wenn genug Treffer gesammelt sind. */
class MaxReached : RuntimeException()

/** Liest den Text jeder Seite samt Zeichenpositionen und meldet am Seitenende. */
class Collector : PDFTextStripper() {
    val sb = StringBuilder()
    val rs = ArrayList<RectF?>()
    var pw = 1f
    var ph = 1f
    var shouldStop: () -> Boolean = { false }
    var pageDone: (PDPage) -> Unit = {}

    override fun startPage(page: PDPage?) {
        sb.setLength(0)
        rs.clear()
        if (page != null) {
            val box = page.cropBox
            var w = box.width
            var h = box.height
            val rot = page.rotation
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            pw = if (w > 0f) w else 1f
            ph = if (h > 0f) h else 1f
        }
        super.startPage(page)
    }

    override fun endPage(page: PDPage?) {
        super.endPage(page)
        if (page != null) pageDone(page)
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (text == null) return
        if (shouldStop()) throw SearchStop()
        if (sb.isNotEmpty()) {
            sb.append(' ')
            rs.add(null)
        }
        val n = text.length
        val m = textPositions?.size ?: 0
        for (i in 0 until n) {
            sb.append(text[i])
            var r: RectF? = null
            if (m > 0 && textPositions != null) {
                val idx = if (m == n) i else minOf(m - 1, i * m / n)
                val tp = textPositions[idx]
                val left = tp.xDirAdj
                val base = tp.yDirAdj
                val top = base - tp.heightDir
                r = RectF(
                    left / pw,
                    top / ph,
                    (left + tp.widthDirAdj) / pw,
                    (base + tp.heightDir * 0.25f) / ph
                )
            }
            rs.add(r)
        }
    }
}

/** Eine Bearbeitung: kind 0 = freier Text, 1 = Formular-Text (mit weißer Fläche), 2 = Markierung (X), 3 = Signatur. Koordinaten 0..1 der Seite. */
class Edit(
    val page: Int,
    var left: Float,
    var top: Float,
    var right: Float,
    var bottom: Float,
    var text: String,
    var size: Float,
    val kind: Int,
    val fieldId: String?,
    val group: String?
) {
    var sig: Bitmap? = null
    var sigPath: String? = null
}

/** Ein Formularfeld der PDF. type: 0 Text, 1 Kontrollkästchen, 2 Auswahlknopf, 3 Auswahlliste. */
class FormField(val id: String, val group: String, val type: Int, val rect: RectF, val options: List<String>)

/** Findet die Positionen aller Bilder auf einer Seite (in PDF-Koordinaten). */
class ImageFinder : PDFStreamEngine() {
    val boxes = ArrayList<FloatArray>()

    init {
        addOperator(Concatenate())
        addOperator(PdfSave())
        addOperator(PdfRestore())
    }

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        if ("Do" == operator.name) {
            val name = if (operands.isNotEmpty()) operands[0] as? COSName else null
            if (name != null) {
                val xo = resources.getXObject(name)
                if (xo is PDImageXObject) {
                    val m = graphicsState.currentTransformationMatrix
                    val a = m.scaleX
                    val b = m.shearY
                    val c = m.shearX
                    val d = m.scaleY
                    val e = m.translateX
                    val f = m.translateY
                    val xs = floatArrayOf(e, a + e, c + e, a + c + e)
                    val ys = floatArrayOf(f, b + f, d + f, b + d + f)
                    boxes.add(floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()))
                } else if (xo is PDFormXObject) {
                    showForm(xo)
                }
            }
        } else {
            super.processOperator(operator, operands)
        }
    }
}

/** Zeigt eine Seite (Basisbild + scharfer Ausschnitt beim Zoomen), Suchtreffer und Bearbeitungen. */
class PageView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
    var tile: Bitmap? = null
    var tileRect: RectF? = null
    var zoom = 1f
    var panX = 0f
    var hits: List<RectF> = emptyList()
    var current: List<RectF> = emptyList()
    var dark = false
    var sepia = false
    var imgRects: List<RectF> = emptyList()
    var edits: List<Edit> = emptyList()
    var fields: List<FormField> = emptyList()
    var editOn = false
    var pageWpt = 595f
    var pageHpt = 842f
    private val back = Paint()
    private val yellow = Paint()
    private val orange = Paint()
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val invert = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint()
    private val markFill = Paint()
    private val markLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sigPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val sigLight = LightingColorFilter(0x000000, 0xEBEBEB)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val sigDst = RectF()
    private val dens = ctx.resources.displayMetrics.density

    init {
        yellow.color = Color.argb(110, 255, 235, 59)
        orange.color = Color.argb(150, 255, 152, 0)
        markLine.style = Paint.Style.STROKE
        markLine.strokeWidth = 1.5f * dens
        tp.typeface = Typeface.SANS_SERIF
        invert.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 232f,
                    0f, -0.85f, 0f, 0f, 232f,
                    0f, 0f, -0.85f, 0f, 232f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        // warmer Papierton für den Lesemodus
        paper.colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    0.97f, 0f, 0f, 0f, 0f,
                    0f, 0.92f, 0f, 0f, 0f,
                    0f, 0f, 0.80f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    private fun drawBitmaps(canvas: Canvas, zW: Float, zH: Float, paint: Paint?) {
        val b = bmp
        if (b != null) {
            dst.set(-panX, 0f, zW - panX, zH)
            canvas.drawBitmap(b, null, dst, paint)
        }
        val t = tile
        val tr = tileRect
        if (t != null && tr != null && zoom > 1.05f) {
            dst.set(tr.left * zW - panX, tr.top * zH, tr.right * zW - panX, tr.bottom * zH)
            canvas.drawBitmap(t, null, dst, paint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val zW = vw * zoom
        val zH = height.toFloat()
        back.color = if (dark) Color.rgb(15, 15, 15) else if (sepia) Color.rgb(247, 235, 204) else Color.WHITE
        canvas.drawRect(0f, 0f, vw, zH, back)
        val pp = if (dark) invert else if (sepia) paper else plain
        drawBitmaps(canvas, zW, zH, pp)
        if (dark) {
            // Bilder behalten im Dunkelmodus ihre Originalfarben
            for (r in imgRects) {
                canvas.save()
                canvas.clipRect(r.left * zW - panX, r.top * zH, r.right * zW - panX, r.bottom * zH)
                drawBitmaps(canvas, zW, zH, plain)
                canvas.restore()
            }
        }
        for (r in hits) canvas.drawRect(r.left * zW - panX, r.top * zH, r.right * zW - panX, r.bottom * zH, yellow)
        for (r in current) canvas.drawRect(r.left * zW - panX, r.top * zH, r.right * zW - panX, r.bottom * zH, orange)
        if (editOn) {
            for (f in fields) {
                mark(canvas, f.rect.left * zW - panX, f.rect.top * zH, f.rect.right * zW - panX, f.rect.bottom * zH, Color.rgb(33, 150, 243))
            }
        }
        for (e in edits) drawEdit(canvas, e, zW, zH)
    }

    /** Farbige Markierung (Füllung + Rahmen), nur in der Anzeige, nicht in der gespeicherten PDF. */
    private fun mark(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        markFill.color = Color.argb(55, Color.red(color), Color.green(color), Color.blue(color))
        markLine.color = Color.argb(230, Color.red(color), Color.green(color), Color.blue(color))
        c.drawRect(l, t, r, b, markFill)
        c.drawRect(l, t, r, b, markLine)
    }

    private fun drawEdit(c: Canvas, e: Edit, zW: Float, zH: Float) {
        val sc = zW / pageWpt
        tp.color = if (dark) Color.rgb(235, 235, 235) else Color.BLACK
        tp.typeface = Typeface.SANS_SERIF
        when (e.kind) {
            0 -> {
                tp.textSize = e.size * sc
                val lines = e.text.split("\n")
                val l = e.left * zW - panX
                val t = e.top * zH
                if (editOn) {
                    var maxW = 0f
                    for (s in lines) maxW = maxOf(maxW, tp.measureText(s))
                    val pad = 3f * dens
                    mark(c, l - pad, t - pad, l + maxW + pad, t + lines.size * 1.2f * e.size * sc + pad, Color.rgb(255, 160, 0))
                }
                for (i in lines.indices) {
                    val y = t + (0.8f * e.size + i * 1.2f * e.size) * sc
                    c.drawText(lines[i], l, y, tp)
                }
            }
            1 -> {
                val l = e.left * zW - panX
                val t = e.top * zH
                val r = e.right * zW - panX
                val b = e.bottom * zH
                boxPaint.color = if (dark) Color.rgb(15, 15, 15) else Color.WHITE
                c.drawRect(l, t, r, b, boxPaint)
                if (editOn) mark(c, l, t, r, b, Color.rgb(46, 160, 67))
                tp.textSize = e.size * sc
                val lines = e.text.split("\n")
                val h = (e.bottom - e.top) * zH
                for (i in lines.indices) {
                    val y = if (lines.size == 1) t + h / 2f + e.size * sc * 0.35f
                    else t + (0.9f * e.size + i * 1.15f * e.size + 1.5f) * sc
                    c.drawText(lines[i], l + 2f * sc, y, tp)
                }
            }
            3 -> {
                val l = e.left * zW - panX
                val t = e.top * zH
                val r = e.right * zW - panX
                val b = e.bottom * zH
                val s = e.sig
                if (s != null) {
                    sigPaint.colorFilter = if (dark) sigLight else null
                    sigDst.set(l, t, r, b)
                    c.drawBitmap(s, null, sigDst, sigPaint)
                }
                if (editOn) mark(c, l, t, r, b, Color.rgb(156, 39, 176))
            }
            else -> {
                val w = (e.right - e.left) * zW
                val h = (e.bottom - e.top) * zH
                tp.typeface = Typeface.DEFAULT_BOLD
                tp.textSize = minOf(w, h) * 0.9f
                val cx = (e.left + e.right) / 2f * zW - panX
                val cy = (e.top + e.bottom) / 2f * zH
                c.drawText("X", cx - tp.measureText("X") / 2f, cy + tp.textSize * 0.35f, tp)
            }
        }
    }
}

/** Seitenliste mit Zwei-Finger-Zoom, seitlichem Verschieben, Tippen, Doppeltippen und Ziehen von Bearbeitungen. */
class ZoomRecycler(ctx: Context) : RecyclerView(ctx) {
    var allowPan: () -> Boolean = { false }
    var onZoom: (Float, Float, Float) -> Unit = { _, _, _ -> }
    var onPan: (Float) -> Unit = {}
    var onTapAt: (Float, Float) -> Unit = { _, _ -> }
    var onDoubleTapAt: (Float, Float) -> Unit = { _, _ -> }
    var onDragStart: (Float, Float) -> Boolean = { _, _ -> false }
    var onDragMove: (Float, Float) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}

    private var blocked = false
    private var dragCandidate = false
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastX = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val tapRunnable = Runnable { onTapAt(lastTapX, lastTapY) }

    private val scaleDetector = ScaleGestureDetector(
        ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                onZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    init {
        scaleDetector.isQuickScaleEnabled = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            blocked = false
            dragging = false
            downX = ev.x
            downY = ev.y
            downTime = ev.eventTime
            moved = false
            lastX = ev.x
            dragCandidate = onDragStart(ev.x, ev.y)
        }
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) {
            moved = true
            if (dragging) {
                dragging = false
                onDragEnd()
            }
            dragCandidate = false
            if (!blocked) {
                blocked = true
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
            }
            return true
        }
        if (blocked) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) blocked = false
            return true
        }
        if (dragging) {
            if (a == MotionEvent.ACTION_MOVE) {
                onDragMove(ev.x, ev.y)
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                dragging = false
                dragCandidate = false
                onDragEnd()
            }
            return true
        }
        if (a == MotionEvent.ACTION_MOVE) {
            if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) moved = true
            if (moved && dragCandidate) {
                // Eine Bearbeitung (Text/Signatur) wird verschoben: Liste nicht scrollen
                dragging = true
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(c)
                c.recycle()
                onDragMove(ev.x, ev.y)
                return true
            }
            if (moved && allowPan()) onPan(lastX - ev.x)
            lastX = ev.x
        } else if (a == MotionEvent.ACTION_UP && !moved && ev.eventTime - downTime < 350) {
            val now = ev.eventTime
            if (now - lastTapTime < 300 && abs(ev.x - lastTapX) < 80 && abs(ev.y - lastTapY) < 80) {
                removeCallbacks(tapRunnable)
                lastTapTime = 0L
                onDoubleTapAt(ev.x, ev.y)
            } else {
                lastTapTime = now
                lastTapX = ev.x
                lastTapY = ev.y
                postDelayed(tapRunnable, 300)
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** Zeichenfläche für eine neue Signatur (Finger). */
class SignaturePad(ctx: Context) : View(ctx) {
    private val strokes = ArrayList<Path>()
    private var cur: Path? = null
    private var lastX = 0f
    private var lastY = 0f
    private val dens = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guide = Paint()

    init {
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f * dens
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        guide.color = Color.rgb(205, 205, 205)
        guide.strokeWidth = 1f * dens
        setBackgroundColor(Color.rgb(250, 250, 250))
    }

    fun clear() {
        strokes.clear()
        cur = null
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val p = Path()
                p.moveTo(x, y)
                p.lineTo(x + 0.1f, y)
                strokes.add(p)
                cur = p
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                val p = cur ?: return true
                p.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cur = null
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        val gy = height * 0.72f
        c.drawLine(16f * dens, gy, width - 16f * dens, gy, guide)
        for (p in strokes) c.drawPath(p, paint)
    }

    /** Schwarze Tinte auf transparentem Grund, auf die Strichgrenzen zugeschnitten. */
    fun toBitmap(): Bitmap? {
        if (strokes.isEmpty()) return null
        val r = RectF()
        val tmp = RectF()
        var first = true
        for (p in strokes) {
            p.computeBounds(tmp, true)
            if (first) {
                r.set(tmp)
                first = false
            } else {
                r.union(tmp)
            }
        }
        val pad = paint.strokeWidth
        r.inset(-pad, -pad)
        val scale = 2f
        val w = maxOf(1, (r.width() * scale).toInt())
        val h = maxOf(1, (r.height() * scale).toInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(scale, scale)
        c.translate(-r.left, -r.top)
        for (p in strokes) c.drawPath(p, paint)
        return b
    }
}

class VH(val v: PageView) : RecyclerView.ViewHolder(v)

class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newSingleThreadExecutor()
    private val renderExec = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile private var loadToken = 0
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var pageW = FloatArray(0)
    private var pageH = FloatArray(0)
    private var screenW = 1080

    private var currentFile: File? = null
    private var currentName = ""
    private val searchExec = Executors.newSingleThreadExecutor()
    @Volatile private var searchToken = 0
    private var searchRunning = false
    private var searchPercent = 0
    @Volatile private var sawText = false
    @Volatile private var nativeState = 0
    private var nativeMethod: java.lang.reflect.Method? = null
    private var fbDoc: PDDocument? = null
    private var fbPath: String? = null
    private var fbIndex: java.util.IdentityHashMap<Any, Int>? = null
    private var collector: Collector? = null
    private var matches: List<Match> = emptyList()
    private var currentMatch = 0
    private var highlights: Map<Int, List<RectF>> = emptyMap()

    private var pendingUris: List<Uri> = emptyList()
    private var pendingMode = 0

    @Volatile private var zoom = 1f
    private var panX = 0f
    private val tileRunnable = Runnable { requestTiles() }

    private var darkMode = false
    private var statusText = ""
    private var readingMode = false
    private lateinit var pageInd: TextView
    @Volatile private var viewPos = 0
    @Volatile private var prefetchFor = -1
    private var barTx = 0f
    private var barTy = 0f
    private var dragEdit: Edit? = null
    private var dragView: PageView? = null
    private var dragX0 = 0f
    private var dragY0 = 0f
    private var dragL0 = 0f
    private var dragT0 = 0f
    private var dragR0 = 0f
    private var dragB0 = 0f

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var topBox: LinearLayout
    private lateinit var menuPanel: LinearLayout
    private lateinit var navBox: LinearLayout
    private lateinit var counter: TextView
    private lateinit var emptyHint: TextView
    private lateinit var pages: ZoomRecycler
    private lateinit var lm: LinearLayoutManager
    private lateinit var adapter: PageAdapter

    // ------------------------------------------------------------ Farben & Hilfen

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun bgBar(): Int = if (darkMode) Color.rgb(32, 33, 36) else Color.WHITE
    private fun fg(): Int = if (darkMode) Color.rgb(235, 235, 235) else Color.rgb(25, 25, 25)
    private fun sub(): Int = if (darkMode) Color.rgb(165, 165, 165) else Color.rgb(90, 90, 90)
    private fun chipBg(): Int = if (darkMode) Color.rgb(60, 64, 67) else Color.rgb(228, 231, 236)
    private fun fieldBg(): Int = if (darkMode) Color.rgb(48, 49, 52) else Color.rgb(245, 246, 248)

    private fun toast(s: String, long: Boolean = true) {
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(s: String) {
        statusText = s
        if (this::status.isInitialized) status.text = s
    }

    private fun chip(label: String, action: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 16f
        t.setTextColor(fg())
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        val g = GradientDrawable()
        g.setColor(chipBg())
        g.cornerRadius = dp(10).toFloat()
        t.background = g
        t.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(3), 0, dp(3), 0)
        t.layoutParams = lp
        return t
    }

    private fun menuItem(label: String, action: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 17f
        t.setTextColor(fg())
        t.setPadding(dp(20), dp(15), dp(20), dp(15))
        t.setOnClickListener { action() }
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return t
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(if (darkMode) Color.rgb(70, 72, 75) else Color.rgb(220, 222, 226))
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        return v
    }

    private fun hideKeyboard() {
        if (!this::searchBox.isInitialized) return
        val m = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        m.hideSoftInputFromWindow(searchBox.windowToken, 0)
        searchBox.clearFocus()
    }

    // ------------------------------------------------------------ Oberfläche

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        screenW = resources.displayMetrics.widthPixels
        darkMode = getSharedPreferences("einstellungen", MODE_PRIVATE).getBoolean("dark", false)
        readingMode = getSharedPreferences("einstellungen", MODE_PRIVATE).getBoolean("reading", false)
        statusText = "Noch keine PDF geöffnet."
        buildUi()
        openMenu()
        val first = intent
        if (savedInstanceState == null && first != null && first.action == Intent.ACTION_VIEW && first.data != null) {
            handleIntent(first)
        } else {
            restoreLast()
        }
    }

    private fun buildUi() {
        val oldQuery = if (this::searchBox.isInitialized) searchBox.text.toString() else ""
        val pad = dp(8)

        window.statusBarColor = bgBar()
        window.navigationBarColor = if (darkMode) Color.rgb(18, 18, 18) else Color.WHITE
        applySystemUi()

        // Seitenliste
        pages = ZoomRecycler(this)
        pages.allowPan = { zoom > 1.05f }
        pages.onZoom = { f, x, y -> applyZoom(f, x, y) }
        pages.onPan = { dx -> applyPan(dx) }
        pages.onDragStart = { x, y -> dragStart(x, y) }
        pages.onDragMove = { x, y -> dragMove(x, y) }
        pages.onDragEnd = { dragEnd() }
        pages.onTapAt = { x, y -> onTapAt(x, y) }
        pages.onDoubleTapAt = { x, y ->
            val target = if (zoom > 1.5f) 1f else 2.5f
            applyZoom(target / zoom, x, y)
        }
        lm = LinearLayoutManager(this)
        pages.layoutManager = lm
        adapter = PageAdapter()
        pages.adapter = adapter
        pages.setBackgroundColor(if (darkMode) Color.rgb(8, 8, 8) else Color.rgb(200, 200, 200))
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideBar()
                if (newState == RecyclerView.SCROLL_STATE_IDLE) scheduleTiles()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val fp = lm.findFirstVisibleItemPosition()
                if (fp >= 0) viewPos = fp
                updatePageInd()
            }
        })

        // Hinweis, solange keine PDF offen ist
        emptyHint = TextView(this)
        emptyHint.text = "Noch keine PDF geöffnet.\n\nTippe oben auf „☰ Menü“ und dann auf „PDF öffnen“."
        emptyHint.gravity = Gravity.CENTER
        emptyHint.textSize = 17f
        emptyHint.setTextColor(sub())
        emptyHint.setPadding(dp(32), 0, dp(32), 0)
        emptyHint.visibility = if (pageCount == 0) View.VISIBLE else View.GONE

        // Obere Leiste (Menü + Suche + Status + Menü-Liste)
        topBox = LinearLayout(this)
        topBox.orientation = LinearLayout.VERTICAL
        topBox.setBackgroundColor(bgBar())
        topBox.elevation = dp(6).toFloat()

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(pad, pad, pad, pad / 2)
        row.addView(chip("☰ Menü") { toggleMenu() })

        searchBox = EditText(this)
        searchBox.hint = "Im Dokument suchen"
        searchBox.setHintTextColor(sub())
        searchBox.setTextColor(fg())
        searchBox.textSize = 16f
        searchBox.setSingleLine(true)
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8))
        val gb = GradientDrawable()
        gb.setColor(fieldBg())
        gb.cornerRadius = dp(10).toFloat()
        gb.setStroke(dp(1), sub())
        searchBox.background = gb
        searchBox.setText(oldQuery)
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        val lpS = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpS.setMargins(dp(4), 0, dp(4), 0)
        row.addView(searchBox, lpS)
        row.addView(chip("Suchen") { doSearch() })
        topBox.addView(row)

        status = TextView(this)
        status.textSize = 12f
        status.setTextColor(sub())
        status.setPadding(pad * 2, 0, pad * 2, pad)
        status.text = statusText
        topBox.addView(status)

        menuPanel = LinearLayout(this)
        menuPanel.orientation = LinearLayout.VERTICAL
        menuPanel.visibility = View.GONE
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("📂   PDF öffnen") {
            closeMenu()
            confirmDiscard { pick("application/pdf", REQ_OPEN) }
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("➕   PDFs zusammenfügen") {
            closeMenu()
            confirmDiscard { pick("application/pdf", REQ_MERGE) }
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("🖼   Bilder zu PDF") {
            closeMenu()
            confirmDiscard { pick("image/*", REQ_IMAGES) }
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem(if (darkMode) "☀   Heller Modus" else "🌙   Dunkelmodus") { toggleDark() })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem(if (readingMode) "📖   Lesemodus beenden" else "📖   Lesemodus") { toggleReading() })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("✏   Bearbeiten (Text, Formulare)") {
            closeMenu()
            setEditMode(true)
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("💾   Bearbeitete PDF speichern") {
            closeMenu()
            saveEditsClicked()
        })
        topBox.addView(menuPanel)

        // Untere Leiste für Treffer-Navigation
        navBox = LinearLayout(this)
        navBox.orientation = LinearLayout.HORIZONTAL
        navBox.gravity = Gravity.CENTER_VERTICAL
        navBox.setPadding(pad, pad, pad, pad)
        navBox.setBackgroundColor(if (darkMode) Color.argb(240, 32, 33, 36) else Color.argb(240, 255, 255, 255))
        navBox.elevation = dp(6).toFloat()
        navBox.addView(chip("◀") { step(-1) })
        counter = TextView(this)
        counter.gravity = Gravity.CENTER
        counter.textSize = 15f
        counter.setTextColor(fg())
        counter.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        updateCounter()
        navBox.addView(counter)
        navBox.addView(chip("▶") { step(1) })
        navBox.addView(chip("✕") { clearSearch() })
        navBox.visibility = if (matches.isEmpty() && !searchRunning) View.GONE else View.VISIBLE

        // Schwebende, verschiebbare Symbolleiste für den Bearbeiten-Modus
        editBar = LinearLayout(this)
        editBar.orientation = LinearLayout.HORIZONTAL
        editBar.gravity = Gravity.CENTER_VERTICAL
        editBar.setPadding(dp(6), dp(2), dp(6), dp(2))
        val gbar = GradientDrawable()
        gbar.setColor(if (darkMode) Color.argb(245, 48, 49, 52) else Color.argb(245, 255, 255, 255))
        gbar.cornerRadius = dp(28).toFloat()
        gbar.setStroke(dp(1), sub())
        editBar.background = gbar
        editBar.elevation = dp(8).toFloat()
        val grip = iconButton("⋮", "Leiste verschieben", 28) { }
        var gx = 0f
        var gy = 0f
        grip.setOnTouchListener { _, ev ->
            val par = editBar.parent as? View
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                gx = ev.rawX - editBar.translationX
                gy = ev.rawY - editBar.translationY
            } else if (ev.actionMasked == MotionEvent.ACTION_MOVE && par != null) {
                val minX = -editBar.left.toFloat()
                val maxX = maxOf(minX, (par.width - editBar.right).toFloat())
                val minY = -editBar.top.toFloat()
                val maxY = maxOf(minY, (par.height - editBar.bottom).toFloat())
                barTx = (ev.rawX - gx).coerceIn(minX, maxX)
                barTy = (ev.rawY - gy).coerceIn(minY, maxY)
                editBar.translationX = barTx
                editBar.translationY = barTy
            }
            true
        }
        editBar.addView(grip)
        editBar.addView(iconButton("↩", "Rückgängig") { undoEdit() })
        editBar.addView(iconButton("✍", "Signatur einfügen") { showSignatureChooser() })
        editBar.addView(iconButton("💾", "Speichern") { saveEditsClicked() })
        editBar.addView(iconButton("✓", "Fertig") { setEditMode(false) })
        editBar.translationX = barTx
        editBar.translationY = barTy
        editBar.visibility = if (editMode) View.VISIBLE else View.GONE

        // Seitenzähler (Lesemodus)
        pageInd = TextView(this)
        pageInd.textSize = 13f
        pageInd.setTextColor(Color.WHITE)
        pageInd.setPadding(dp(10), dp(5), dp(10), dp(5))
        val gind = GradientDrawable()
        gind.setColor(Color.argb(170, 0, 0, 0))
        gind.cornerRadius = dp(14).toFloat()
        pageInd.background = gind
        pageInd.visibility = if (readingMode && pageCount > 0) View.VISIBLE else View.GONE

        val bottomBox = LinearLayout(this)
        bottomBox.orientation = LinearLayout.VERTICAL
        bottomBox.addView(navBox)

        val root = FrameLayout(this)
        root.setBackgroundColor(if (darkMode) Color.rgb(8, 8, 8) else Color.rgb(200, 200, 200))
        root.addView(pages, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            emptyHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        root.addView(
            bottomBox,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        val lpBar = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        lpBar.bottomMargin = dp(28)
        root.addView(editBar, lpBar)
        val lpInd = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        lpInd.topMargin = dp(14)
        lpInd.rightMargin = dp(10)
        root.addView(pageInd, lpInd)
        root.addView(
            topBox,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )
        setContentView(root)
    }

    private fun showBar() {
        topBox.visibility = View.VISIBLE
    }

    private fun hideBar() {
        hideKeyboard()
        menuPanel.visibility = View.GONE
        topBox.visibility = View.GONE
    }

    private fun openMenu() {
        topBox.visibility = View.VISIBLE
        menuPanel.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menuPanel.visibility = View.GONE
    }

    private fun toggleMenu() {
        if (menuPanel.visibility == View.VISIBLE) closeMenu() else openMenu()
    }

    private fun onPageTap() {
        if (menuPanel.visibility == View.VISIBLE) {
            hideBar()
        } else if (topBox.visibility == View.VISIBLE) {
            hideBar()
        } else {
            showBar()
        }
    }

    private fun toggleDark() {
        val pos = lm.findFirstVisibleItemPosition()
        darkMode = !darkMode
        getSharedPreferences("einstellungen", MODE_PRIVATE).edit().putBoolean("dark", darkMode).apply()
        buildUi()
        if (pos > 0) lm.scrollToPosition(pos)
        openMenu()
        viewPos = pos.coerceAtLeast(0)
        startImagePrefetch()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        main.removeCallbacks(tileRunnable)
        synchronized(lock) {
            try {
                renderer?.close()
            } catch (e: Exception) {
            }
            renderer = null
        }
    }

    override fun onPause() {
        super.onPause()
        saveState()
    }

    /** Merkt sich geöffnete PDF, Seite, Zoom und Suchbegriff, damit nichts verloren geht, wenn Android die App beendet. */
    private fun saveState() {
        val f = currentFile ?: return
        if (pageCount == 0) return
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return
        val off = pages.getChildAt(0)?.top ?: 0
        val e = getSharedPreferences("einstellungen", MODE_PRIVATE).edit()
        e.putString("lastPath", f.absolutePath)
        e.putString("lastName", currentName)
        e.putInt("lastPos", first)
        e.putInt("lastOff", off)
        e.putFloat("lastZoom", zoom)
        e.putFloat("lastPanX", panX)
        e.putString("lastQuery", searchBox.text.toString())
        e.putInt(docKey(currentName, f.length()), first)
        e.apply()
    }

    private fun docKey(name: String, size: Long): String = "p_" + (name + "|" + size).hashCode()

    private fun handleIntent(i: Intent?) {
        val u = i?.data
        if (i != null && i.action == Intent.ACTION_VIEW && u != null) openPdf(u)
    }

    // ------------------------------------------------------------ Seitenanzeige

    inner class PageAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(PageView(this@MainActivity))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val lp = RecyclerView.LayoutParams(screenW, pageHeightPx(position))
            lp.bottomMargin = 6
            holder.v.layoutParams = lp
            if (holder.v.tag != position) {
                holder.v.tile = null
                holder.v.tileRect = null
            }
            holder.v.zoom = zoom
            holder.v.panX = panX
            holder.v.dark = darkMode
            holder.v.hits = highlights[position] ?: emptyList()
            holder.v.current =
                if (matches.isNotEmpty() && matches[currentMatch].page == position) matches[currentMatch].rects
                else emptyList()
            holder.v.tag = position
            applyOverlay(holder.v, position)
            val cached = cache.get(position)
            holder.v.bmp = cached
            holder.v.invalidate()
            if (cached == null) renderAsync(position, holder.v)
        }
    }

    private fun renderAsync(pos: Int, view: PageView) {
        val r = renderer ?: return
        val token = loadToken
        renderExec.execute {
            var result: Bitmap? = null
            try {
                synchronized(lock) {
                    if (token != loadToken || renderer !== r) return@execute
                    val page = r.openPage(pos)
                    val w = screenW
                    val h = (w * page.height.toFloat() / page.width).toInt()
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    result = b
                }
            } catch (e: Exception) {
            }
            val done = result ?: return@execute
            cache.put(pos, done)
            main.post {
                if (view.tag == pos && token == loadToken) {
                    view.bmp = done
                    view.invalidate()
                }
            }
        }
    }

    // ------------------------------------------------------------ PDF öffnen

    private class Restore(val pos: Int, val off: Int, val zoom: Float, val panX: Float)

    private fun nameOf(u: Uri): String {
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
        }
        return u.lastPathSegment ?: "PDF"
    }

    private fun nextDocFile(): File =
        File(filesDir, if (currentFile?.name == "doc_0.pdf") "doc_1.pdf" else "doc_0.pdf")

    private fun errText(e: Throwable): String = e.javaClass.simpleName + ": " + (e.message ?: "")

    private fun beginLoad(msg: String) {
        loadToken++
        searchToken++
        searchRunning = false
        setStatus(msg)
        matches = emptyList()
        highlights = emptyMap()
        currentMatch = 0
        navBox.visibility = View.GONE
        edits.clear()
        formFields.clear()
        formPending.clear()
        imgRects.clear()
        imgPending.clear()
        editMode = false
        editBar.visibility = View.GONE
        searchExec.execute { closeFallback() }
        auxExec.execute { closeAux() }
    }

    private fun failLoad(token: Int, e: Throwable) {
        main.post {
            if (token == loadToken) {
                val msg = "PDF konnte nicht geöffnet werden (Passwortschutz oder defekte Datei?)"
                setStatus(msg)
                toast(msg)
            }
        }
    }

    /** Kopiert die gewählte PDF in den App-Speicher (damit sie dauerhaft verfügbar bleibt) und öffnet sie. */
    private fun openPdf(uri: Uri, keep: Restore? = null) {
        val name = nameOf(uri)
        val target = nextDocFile()
        beginLoad("Öffne PDF …")
        val token = loadToken
        ioExec.execute {
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            } catch (e: Throwable) {
                failLoad(token, e)
                return@execute
            }
            loadRenderer(target, name, token, keep)
        }
    }

    /** Öffnet beim Start die zuletzt gelesene PDF an der alten Stelle. */
    private fun restoreLast() {
        val p = getSharedPreferences("einstellungen", MODE_PRIVATE)
        val path = p.getString("lastPath", null) ?: return
        val f = File(path)
        if (!f.exists()) return
        val name = p.getString("lastName", "PDF") ?: "PDF"
        val rs = Restore(p.getInt("lastPos", 0), p.getInt("lastOff", 0), p.getFloat("lastZoom", 1f), p.getFloat("lastPanX", 0f))
        searchBox.setText(p.getString("lastQuery", "") ?: "")
        beginLoad("Öffne $name …")
        val token = loadToken
        ioExec.execute { loadRenderer(f, name, token, rs) }
    }

    private fun loadRenderer(f: File, name: String, token: Int, rs: Restore?) {
        try {
            val r = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
            val n = r.pageCount
            val w = FloatArray(n)
            val h = FloatArray(n)
            for (i in 0 until n) {
                if (token != loadToken) {
                    r.close()
                    return
                }
                val p = r.openPage(i)
                w[i] = p.width.toFloat()
                h[i] = p.height.toFloat()
                p.close()
            }
            main.post {
                if (token != loadToken) {
                    r.close()
                } else {
                    synchronized(lock) {
                        try {
                            renderer?.close()
                        } catch (e: Exception) {
                        }
                        renderer = r
                    }
                    cache.evictAll()
                    currentFile = f
                    currentName = name
                    pageW = w
                    pageH = h
                    pageCount = n
                    val saved = getSharedPreferences("einstellungen", MODE_PRIVATE).getInt(docKey(name, f.length()), -1)
                    if (rs != null) {
                        zoom = rs.zoom.coerceIn(1f, 5f)
                        panX = rs.panX
                    } else {
                        zoom = 1f
                        panX = 0f
                    }
                    clampPan()
                    adapter.notifyDataSetChanged()
                    if (rs != null) {
                        lm.scrollToPositionWithOffset(rs.pos.coerceIn(0, n - 1), rs.off)
                    } else {
                        lm.scrollToPositionWithOffset(if (saved in 0 until n) saved else 0, 0)
                    }
                    emptyHint.visibility = View.GONE
                    setStatus("$name – $n Seiten")
                    viewPos = (if (rs != null) rs.pos else if (saved in 0 until n) saved else 0).coerceIn(0, n - 1)
                    pageInd.visibility = if (readingMode) View.VISIBLE else View.GONE
                    pageInd.text = "${viewPos + 1} / $n"
                    startImagePrefetch()
                    hideBar()
                    filesDir.listFiles()?.forEach {
                        if (it.name.startsWith("doc_") && it != f) it.delete()
                    }
                    if (zoom > 1.05f) scheduleTiles()
                    if (rs == null && saved > 0) toast("Weiter bei Seite ${saved + 1}", false)
                }
            }
        } catch (e: Throwable) {
            failLoad(token, e)
        }
    }

    // ------------------------------------------------------------ Suche

    private fun mergeRects(rs: List<RectF?>, s: Int, e: Int): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (k in s until e) {
            val r = rs[k] ?: continue
            val c = cur
            if (c != null && abs(r.top - c.top) < (c.bottom - c.top) * 0.6f && r.left <= c.right + 0.03f) {
                c.left = minOf(c.left, r.left)
                c.top = minOf(c.top, r.top)
                c.right = maxOf(c.right, r.right)
                c.bottom = maxOf(c.bottom, r.bottom)
            } else {
                val n = RectF(r)
                out.add(n)
                cur = n
            }
        }
        return out
    }

    private fun doSearch() {
        hideKeyboard()
        val raw = searchBox.text.toString().trim().replace(Regex("\\s+"), " ")
        if (raw.isEmpty()) return
        val f = currentFile
        if (pageCount == 0 || f == null) {
            toast("Bitte zuerst eine PDF öffnen.")
            return
        }
        searchToken++
        val st = searchToken
        val token = loadToken
        val n = pageCount
        val start = lm.findFirstVisibleItemPosition().coerceIn(0, n - 1)
        matches = emptyList()
        highlights = emptyMap()
        currentMatch = 0
        searchRunning = true
        searchPercent = 0
        adapter.notifyDataSetChanged()
        navBox.visibility = View.VISIBLE
        updateCounter()
        hideBar()
        searchExec.execute { runSearch(raw, st, token, f, start, n) }
    }

    private fun findNativeMethod(): java.lang.reflect.Method? = try {
        Class.forName("android.graphics.pdf.PdfRenderer\$Page").getMethod("searchText", String::class.java)
    } catch (e: Throwable) {
        null
    }

    /** Suche über die in Android 15+ eingebaute PDF-Engine (nativ, schnell). Gibt null zurück, wenn sie nicht verfügbar ist. */
    private fun nativeSearchPage(m: java.lang.reflect.Method, q: String, p: Int, r: PdfRenderer): List<Match>? {
        var result: List<Match>? = null
        try {
            synchronized(lock) {
                if (renderer === r) {
                    val page = r.openPage(p)
                    try {
                        val res = m.invoke(page, q) as? List<*>
                        val out = ArrayList<Match>()
                        if (res != null) {
                            for (item in res) {
                                if (item == null) continue
                                val bl = item.javaClass.getMethod("getBounds").invoke(item) as? List<*>
                                if (bl == null) continue
                                val rects = ArrayList<RectF>()
                                for (b in bl) {
                                    if (b is RectF) {
                                        rects.add(RectF(b.left / pageW[p], b.top / pageH[p], b.right / pageW[p], b.bottom / pageH[p]))
                                    }
                                }
                                if (rects.isNotEmpty()) out.add(Match(p, rects))
                            }
                        }
                        result = out
                    } finally {
                        page.close()
                    }
                }
            }
        } catch (e: Throwable) {
            result = null
        }
        return result
    }

    private fun closeFallback() {
        try {
            fbDoc?.close()
        } catch (e: Exception) {
        }
        fbDoc = null
        fbPath = null
        fbIndex = null
    }

    private fun openFallback(f: File): PDDocument {
        val cur = fbDoc
        if (cur != null && fbPath == f.absolutePath) return cur
        closeFallback()
        val d = PDDocument.load(f)
        val m = java.util.IdentityHashMap<Any, Int>()
        var i = 0
        for (pg in d.pages) {
            m[pg.cosObject] = i
            i++
        }
        fbDoc = d
        fbPath = f.absolutePath
        fbIndex = m
        return d
    }

    /** Ersatzsuche mit PDFBox: ein einziger Durchlauf ab der aktuellen Seite, Treffer erscheinen laufend. */
    private fun fallbackSearch(
        q: String, st: Int, token: Int, f: File, start: Int, n: Int,
        found: ArrayList<Match>, progress: (Int) -> Unit
    ) {
        val doc = openFallback(f)
        val idx = fbIndex ?: return
        val c = collector ?: Collector()
        collector = c
        c.shouldStop = { st != searchToken || token != loadToken }
        c.pageDone = { page ->
            val p = idx[page.cosObject]
            if (p != null) {
                val low = String(CharArray(c.sb.length) { c.sb[it].lowercaseChar() })
                if (low.isNotBlank()) sawText = true
                var from = 0
                while (true) {
                    val i = low.indexOf(q, from)
                    if (i < 0) break
                    found.add(Match(p, mergeRects(c.rs, i, i + q.length)))
                    from = i + q.length
                }
                val done = if (p >= start) p - start + 1 else (n - start) + p + 1
                progress(done * 100 / n)
                if (found.size >= MAX_MATCHES) throw MaxReached()
            }
        }
        c.startPage = start + 1
        c.endPage = n
        c.getText(doc)
        if (start > 0) {
            c.startPage = 1
            c.endPage = start
            c.getText(doc)
        }
    }

    private fun runSearch(raw: String, st: Int, token: Int, f: File, start: Int, n: Int) {
        val q = String(CharArray(raw.length) { raw[it].lowercaseChar() })
        val found = ArrayList<Match>()
        var lastPost = 0L
        sawText = false
        var usedNative = false

        fun alive(): Boolean = st == searchToken && token == loadToken
        fun post(percent: Int, done: Boolean, note: String) {
            val snap = ArrayList<Match>(found)
            main.post { if (alive()) applyMatches(snap, percent, done, note) }
        }
        fun maybePost(percent: Int) {
            val now = System.currentTimeMillis()
            if (now - lastPost > 400) {
                lastPost = now
                post(percent, false, "")
            }
        }

        val r = renderer
        if (r != null && Build.VERSION.SDK_INT >= 35 && nativeState >= 0) {
            val m = nativeMethod ?: findNativeMethod()
            nativeMethod = m
            if (m == null) {
                nativeState = -1
            } else {
                var fails = 0
                var ok = false
                var i = 0
                while (i < n) {
                    if (!alive()) return
                    val p = (start + i) % n
                    val res = nativeSearchPage(m, q, p, r)
                    if (res == null) {
                        fails++
                        if (!ok && fails >= 3) {
                            nativeState = -1
                            found.clear()
                            break
                        }
                    } else {
                        ok = true
                        nativeState = 1
                        found.addAll(res)
                    }
                    i++
                    if (found.size >= MAX_MATCHES) break
                    maybePost(i * 100 / n)
                }
                if (nativeState != -1) usedNative = true
            }
        }

        if (!usedNative) {
            try {
                fallbackSearch(q, st, token, f, start, n, found) { pct -> maybePost(pct) }
            } catch (e: SearchStop) {
                return
            } catch (e: MaxReached) {
                // genug Treffer gesammelt
            } catch (e: Throwable) {
                val msg = errText(e)
                main.post {
                    if (alive()) {
                        searchRunning = false
                        navBox.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
                        updateCounter()
                        toast("Textsuche nicht möglich: $msg")
                    }
                }
                return
            }
        }
        if (!alive()) return
        var note = ""
        if (!usedNative && !sawText) note = "Die PDF enthält vermutlich keinen durchsuchbaren Text (Scan)."
        if (found.size >= MAX_MATCHES) note = "Mehr als $MAX_MATCHES Treffer – Anzeige gekürzt, bitte genauer suchen."
        post(100, true, note)
    }

    private fun updateCounter() {
        counter.text = when {
            matches.isEmpty() -> if (searchRunning) "Suche läuft … $searchPercent %" else ""
            searchRunning -> "Treffer ${currentMatch + 1} von ${matches.size}+  ·  Suche $searchPercent %"
            else -> "Treffer ${currentMatch + 1} von ${matches.size}"
        }
    }

    private fun applyMatches(list: List<Match>, percent: Int, done: Boolean, note: String) {
        val wasEmpty = matches.isEmpty()
        val selected = if (currentMatch in matches.indices) matches[currentMatch] else null
        val ordered = if (done) list.sortedBy { it.page } else list
        matches = ordered
        val map = HashMap<Int, ArrayList<RectF>>()
        for (m in ordered) map.getOrPut(m.page) { ArrayList() }.addAll(m.rects)
        highlights = map
        searchPercent = percent
        searchRunning = !done
        if (ordered.isEmpty()) {
            currentMatch = 0
            if (done) {
                navBox.visibility = View.GONE
                toast("Keine Treffer für „${searchBox.text.toString().trim()}“. $note".trim())
            }
            updateCounter()
            adapter.notifyDataSetChanged()
            return
        }
        navBox.visibility = View.VISIBLE
        if (wasEmpty) {
            goTo(0)
        } else {
            val k = if (selected != null) ordered.indexOf(selected) else -1
            currentMatch = if (k >= 0) k else 0
            updateCounter()
            adapter.notifyDataSetChanged()
        }
        if (done && note.isNotBlank()) toast(note)
    }

    private fun clearSearch() {
        searchToken++
        searchRunning = false
        matches = emptyList()
        highlights = emptyMap()
        currentMatch = 0
        navBox.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun step(d: Int) {
        if (matches.isEmpty()) return
        goTo((currentMatch + d + matches.size) % matches.size)
    }

    private fun goTo(k: Int) {
        currentMatch = k
        updateCounter()
        val m = matches[k]
        val first = m.rects.firstOrNull()
        if (zoom > 1.05f && first != null) {
            panX = first.centerX() * screenW * zoom - screenW / 2f
            clampPan()
        }
        adapter.notifyDataSetChanged()
        val y = (first?.top ?: 0f) * pageHeightPx(m.page)
        lm.scrollToPositionWithOffset(m.page, -(y - 300f).toInt())
        scheduleTiles()
    }

    // ------------------------------------------------------------ Bearbeiten & Bilder im Dunkelmodus

    private var editMode = false
    private val edits = ArrayList<Edit>()
    private val formFields = HashMap<Int, List<FormField>>()
    private val formPending = HashSet<Int>()
    private val imgRects = java.util.concurrent.ConcurrentHashMap<Int, List<RectF>>()
    private val imgPending = HashSet<Int>()
    private val auxExec = Executors.newSingleThreadExecutor()
    private var auxDoc: PDDocument? = null
    private var auxPath: String? = null
    private var auxFields: java.util.IdentityHashMap<Any, PDField>? = null
    private lateinit var editBar: LinearLayout

    private fun editsOf(p: Int): List<Edit> = edits.filter { it.page == p }

    private fun applyOverlay(v: PageView, p: Int) {
        v.edits = editsOf(p)
        v.editOn = editMode
        v.fields = if (editMode) (formFields[p] ?: emptyList()) else emptyList()
        v.imgRects = if (darkMode) (imgRects[p] ?: emptyList()) else emptyList()
        v.sepia = readingMode && !darkMode
        if (p < pageW.size) {
            v.pageWpt = pageW[p]
            v.pageHpt = pageH[p]
        }
        if (editMode) requestFields(p)
        if (darkMode) requestImages(p)
    }

    private fun refreshVisible() {
        for (i in 0 until pages.childCount) {
            val v = pages.getChildAt(i) as? PageView ?: continue
            val p = v.tag as? Int ?: continue
            applyOverlay(v, p)
            v.invalidate()
        }
    }

    private fun refreshPage(p: Int) {
        for (i in 0 until pages.childCount) {
            val v = pages.getChildAt(i) as? PageView ?: continue
            if (v.tag == p) {
                applyOverlay(v, p)
                v.invalidate()
            }
        }
    }

    private fun setEditMode(on: Boolean) {
        if (on && pageCount == 0) {
            toast("Bitte zuerst eine PDF öffnen.")
            return
        }
        editMode = on
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            hideBar()
            toast("Bearbeiten: Tippe auf eine Stelle, um Text einzufügen. Blau = leeres Formularfeld, Grün = ausgefüllt, Gelb = freier Text, Lila = Signatur. Texte und Signaturen kannst du mit dem Finger verschieben; antippen zum Ändern oder Löschen.")
        }
        refreshVisible()
    }

    private fun undoEdit() {
        if (edits.isEmpty()) {
            toast("Nichts zum Rückgängigmachen.", false)
            return
        }
        edits.removeAt(edits.size - 1)
        refreshVisible()
    }

    private fun confirmDiscard(then: () -> Unit) {
        if (edits.isEmpty()) {
            then()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Nicht gespeicherte Änderungen")
            .setMessage("Deine Bearbeitungen gehen verloren, wenn du eine andere Datei öffnest. Trotzdem fortfahren?")
            .setPositiveButton("Verwerfen") { _, _ -> then() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun onTapAt(x: Float, y: Float) {
        if (!editMode) {
            onPageTap()
            return
        }
        if (topBox.visibility == View.VISIBLE) {
            hideBar()
            return
        }
        val v = pages.findChildViewUnder(x, y) as? PageView ?: return
        val p = v.tag as? Int ?: return
        if (v.height <= 0) return
        val fx = (x - v.left + panX) / (screenW * zoom)
        val fy = (y - v.top) / v.height.toFloat()
        editTap(p, fx, fy)
    }

    private fun editTap(p: Int, fx: Float, fy: Float) {
        for (e in edits.asReversed()) {
            if (e.page == p && hitEdit(e, fx, fy)) {
                editExisting(e)
                return
            }
        }
        val fl = formFields[p]
        if (fl != null) {
            for (ff in fl) {
                if (ff.rect.contains(fx, fy)) {
                    fillField(ff, p)
                    return
                }
            }
        }
        textDialog("Text einfügen", "", 12f, true, null) { text, size ->
            edits.add(Edit(p, fx, fy, 0f, 0f, text, size, 0, null, null))
            refreshVisible()
        }
    }

    private fun hitEdit(e: Edit, fx: Float, fy: Float): Boolean {
        if (e.kind != 0) return fx >= e.left && fx <= e.right && fy >= e.top && fy <= e.bottom
        val pw = pageW[e.page]
        val ph = pageH[e.page]
        val lines = e.text.split("\n")
        val tp = Paint()
        tp.textSize = e.size
        var maxW = 0f
        for (l in lines) maxW = maxOf(maxW, tp.measureText(l))
        val w = maxW / pw
        val h = lines.size * 1.2f * e.size / ph
        return fx >= e.left - 0.01f && fx <= e.left + w + 0.01f && fy >= e.top - 0.01f && fy <= e.top + h + 0.01f
    }

    private fun editExisting(e: Edit) {
        val del: () -> Unit = {
            edits.remove(e)
            refreshVisible()
        }
        if (e.kind == 0) {
            textDialog("Text bearbeiten", e.text, e.size, true, del) { text, size ->
                e.text = text
                e.size = size
                refreshVisible()
            }
        } else if (e.kind == 1) {
            val ff = formFields[e.page]?.firstOrNull { it.id == e.fieldId }
            if (ff != null) {
                fillField(ff, e.page)
            } else {
                textDialog("Feld bearbeiten", e.text, 0f, false, del) { text, _ ->
                    e.text = text
                    refreshVisible()
                }
            }
        } else if (e.kind == 3) {
            editSignature(e)
        } else {
            del()
        }
    }

    private fun markEdit(ff: FormField, p: Int): Edit =
        Edit(p, ff.rect.left, ff.rect.top, ff.rect.right, ff.rect.bottom, "X", 0f, 2, ff.id, ff.group)

    private fun fitSize(ff: FormField, p: Int, text: String): Float {
        val hPt = ff.rect.height() * pageH[p]
        val wPt = ff.rect.width() * pageW[p] - 4f
        var size = minOf(12f, maxOf(6f, hPt * 0.7f))
        val tp = Paint()
        tp.textSize = size
        var maxW = 0f
        for (l in text.split("\n")) maxW = maxOf(maxW, tp.measureText(l))
        if (maxW > wPt && maxW > 0f && wPt > 0f) size = maxOf(5f, size * wPt / maxW)
        return size
    }

    private fun setFieldText(ff: FormField, p: Int, text: String) {
        edits.removeAll { it.fieldId == ff.id }
        if (text.isNotBlank()) {
            val size = fitSize(ff, p, text)
            edits.add(Edit(p, ff.rect.left, ff.rect.top, ff.rect.right, ff.rect.bottom, text, size, 1, ff.id, ff.group))
        }
        refreshVisible()
    }

    private fun fillField(ff: FormField, p: Int) {
        val existing = edits.firstOrNull { it.fieldId == ff.id }
        if (ff.type == 0) {
            val del: () -> Unit = {
                edits.removeAll { it.fieldId == ff.id }
                refreshVisible()
            }
            textDialog(ff.group, existing?.text ?: "", 0f, false, if (existing != null) del else null) { text, _ ->
                setFieldText(ff, p, text)
            }
        } else if (ff.type == 1) {
            if (existing != null) edits.remove(existing) else edits.add(markEdit(ff, p))
            refreshVisible()
        } else if (ff.type == 2) {
            if (existing != null) {
                edits.remove(existing)
            } else {
                edits.removeAll { it.kind == 2 && it.group == ff.group }
                edits.add(markEdit(ff, p))
            }
            refreshVisible()
        } else {
            val opts = ff.options
            if (opts.isEmpty()) {
                toast("Dieses Feld hat keine Auswahlmöglichkeiten.")
            } else {
                val items = Array<CharSequence>(opts.size) { opts[it] }
                AlertDialog.Builder(this)
                    .setTitle(ff.group)
                    .setItems(items) { _, i -> setFieldText(ff, p, opts[i]) }
                    .show()
            }
        }
    }

    private fun textDialog(
        title: String, initial: String, initialSize: Float, showSize: Boolean,
        onDelete: (() -> Unit)?, onOk: (String, Float) -> Unit
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val et = EditText(this)
        et.setText(initial)
        et.setSelection(et.text.length)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.minLines = 1
        et.maxLines = 6
        box.addView(et)
        var sizeBox: EditText? = null
        if (showSize) {
            val label = TextView(this)
            label.text = "Schriftgröße (pt)"
            label.setPadding(0, dp(12), 0, 0)
            box.addView(label)
            val sb = EditText(this)
            sb.inputType = InputType.TYPE_CLASS_NUMBER
            sb.setText(initialSize.toInt().toString())
            box.addView(sb)
            sizeBox = sb
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val t = et.text.toString()
                if (t.isNotBlank()) {
                    val sz = (sizeBox?.text?.toString()?.toFloatOrNull() ?: initialSize).coerceIn(6f, 72f)
                    onOk(t, sz)
                }
            }
            .setNegativeButton("Abbrechen", null)
        if (onDelete != null) b.setNeutralButton("Löschen") { _, _ -> onDelete() }
        val d = b.create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    private fun saveEditsClicked() {
        if (edits.isEmpty()) {
            toast("Es gibt noch nichts zu speichern.")
            return
        }
        pendingMode = 3
        val base = if (currentName.endsWith(".pdf", true)) currentName.dropLast(4) else currentName
        askSave(base + "_bearbeitet.pdf")
    }

    // ---- Hilfsdokument (eigene Instanz) für Bild- und Formularerkennung

    private fun closeAux() {
        try {
            auxDoc?.close()
        } catch (e: Exception) {
        }
        auxDoc = null
        auxPath = null
        auxFields = null
    }

    private fun openAux(f: File): PDDocument {
        val cur = auxDoc
        if (cur != null && auxPath == f.absolutePath) return cur
        closeAux()
        val d = PDDocument.load(f)
        val m = java.util.IdentityHashMap<Any, PDField>()
        try {
            val form = d.documentCatalog.acroForm
            if (form != null) {
                for (fl in form.fieldTree) {
                    if (fl is PDTerminalField) {
                        for (w in fl.widgets) m[w.cosObject] = fl
                    }
                }
            }
        } catch (e: Throwable) {
        }
        auxDoc = d
        auxPath = f.absolutePath
        auxFields = m
        return d
    }

    private fun toDisplayRect(page: PDPage, x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val box = page.cropBox
        val w = if (box.width > 0f) box.width else 1f
        val h = if (box.height > 0f) box.height else 1f
        val rot = ((page.rotation % 360) + 360) % 360
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        val xs = floatArrayOf(x1, x2, x1, x2)
        val ys = floatArrayOf(y1, y1, y2, y2)
        for (i in 0 until 4) {
            val u = (xs[i] - box.lowerLeftX) / w
            val v = (ys[i] - box.lowerLeftY) / h
            val dx: Float
            val dy: Float
            when (rot) {
                90 -> {
                    dx = v
                    dy = u
                }
                180 -> {
                    dx = 1f - u
                    dy = v
                }
                270 -> {
                    dx = 1f - v
                    dy = 1f - u
                }
                else -> {
                    dx = u
                    dy = 1f - v
                }
            }
            minX = minOf(minX, dx)
            minY = minOf(minY, dy)
            maxX = maxOf(maxX, dx)
            maxY = maxOf(maxY, dy)
        }
        return RectF(maxOf(0f, minX), maxOf(0f, minY), minOf(1f, maxX), minOf(1f, maxY))
    }

    private fun detectImages(f: File, p: Int): List<RectF> {
        val doc = openAux(f)
        val page = doc.getPage(p)
        val finder = ImageFinder()
        finder.processPage(page)
        val out = ArrayList<RectF>()
        for (b in finder.boxes) {
            val r = toDisplayRect(page, b[0], b[1], b[2], b[3])
            val area = r.width() * r.height()
            // ganzseitige Bilder (Scans) werden wie normaler Seiteninhalt abgedunkelt
            if (area > 0.85f || area < 0.0002f) continue
            out.add(r)
        }
        return out
    }

    private fun detectFields(f: File, p: Int): List<FormField> {
        val doc = openAux(f)
        val map = auxFields ?: return emptyList()
        if (map.isEmpty()) return emptyList()
        val page = doc.getPage(p)
        val out = ArrayList<FormField>()
        var k = 0
        for (a in page.annotations) {
            if (a !is PDAnnotationWidget) continue
            val fld = map[a.cosObject] ?: continue
            if (fld.isReadOnly) continue
            val r = a.rectangle ?: continue
            val type: Int
            var options: List<String> = emptyList()
            if (fld is PDTextField) {
                type = 0
            } else if (fld is PDCheckBox) {
                type = 1
            } else if (fld is PDRadioButton) {
                type = 2
            } else if (fld is PDComboBox || fld is PDListBox) {
                type = 3
                options = (fld as PDChoice).options
            } else {
                continue
            }
            val rect = toDisplayRect(page, r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
            val name = fld.fullyQualifiedName ?: "Feld"
            out.add(FormField("$name@$p#$k", name, type, rect, options))
            k++
        }
        return out
    }

    private fun requestImages(p: Int) {
        if (prefetchFor == loadToken) return
        if (imgRects.containsKey(p) || !imgPending.add(p)) return
        val f = currentFile
        if (f == null) {
            imgPending.remove(p)
            return
        }
        val token = loadToken
        auxExec.execute {
            var res: List<RectF> = emptyList()
            try {
                if (token == loadToken) res = detectImages(f, p)
            } catch (e: Throwable) {
            }
            main.post {
                if (token == loadToken) {
                    imgPending.remove(p)
                    imgRects[p] = res
                    refreshPage(p)
                }
            }
        }
    }

    private fun requestFields(p: Int) {
        if (formFields.containsKey(p) || !formPending.add(p)) return
        val f = currentFile
        if (f == null) {
            formPending.remove(p)
            return
        }
        val token = loadToken
        auxExec.execute {
            var res: List<FormField> = emptyList()
            try {
                if (token == loadToken) res = detectFields(f, p)
            } catch (e: Throwable) {
            }
            main.post {
                if (token == loadToken) {
                    formPending.remove(p)
                    formFields[p] = res
                    refreshPage(p)
                }
            }
        }
    }

    // ---- Speichern: Bearbeitungen in die PDF schreiben

    private fun winAnsi(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            val c = ch.code
            if (c in 32..126 || c in 160..255) {
                sb.append(ch)
            } else if (ch == '€') {
                sb.append("EUR")
            } else if (ch == '„' || ch == '“' || ch == '”') {
                sb.append('"')
            } else if (ch == '‚' || ch == '‘' || ch == '’') {
                sb.append('\'')
            } else if (ch == '–' || ch == '—') {
                sb.append('-')
            } else if (ch == '\t') {
                sb.append("  ")
            } else if (ch != '\r') {
                sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun saveEdited(src: File, list: List<Edit>, dw: FloatArray, dh: FloatArray, out: OutputStream) {
        val doc = PDDocument.load(src)
        try {
            val byPage = list.groupBy { it.page }
            val imgCache = HashMap<String, PDImageXObject>()
            for ((p, es) in byPage) {
                val page = doc.getPage(p)
                val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                try {
                    for (e in es) drawEditPdf(doc, cs, e, page, dw[p], dh[p], imgCache)
                } finally {
                    cs.close()
                }
            }
            doc.save(out)
        } finally {
            doc.close()
        }
    }

    private fun putText(cs: PDPageContentStream, font: PDType1Font, text: String, size: Float, pos: FloatArray, rot: Int) {
        if (text.isEmpty()) return
        cs.beginText()
        cs.setFont(font, size)
        cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(rot.toDouble()), pos[0], pos[1]))
        cs.showText(text)
        cs.endText()
    }

    private fun drawEditPdf(
        doc: PDDocument, cs: PDPageContentStream, e: Edit, page: PDPage, dispW: Float, dispH: Float,
        imgCache: HashMap<String, PDImageXObject>
    ) {
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360

        // Anzeige-Koordinaten (0..1, oben links) -> PDF-Koordinaten
        fun user(fx: Float, fy: Float): FloatArray {
            val u: Float
            val v: Float
            when (rot) {
                90 -> {
                    u = fy
                    v = fx
                }
                180 -> {
                    u = 1f - fx
                    v = fy
                }
                270 -> {
                    u = 1f - fy
                    v = 1f - fx
                }
                else -> {
                    u = fx
                    v = 1f - fy
                }
            }
            return floatArrayOf(box.lowerLeftX + u * box.width, box.lowerLeftY + v * box.height)
        }

        val font = PDType1Font.HELVETICA
        val bold = PDType1Font.HELVETICA_BOLD
        if (e.kind == 0) {
            val lines = e.text.split("\n")
            for (i in lines.indices) {
                val by = e.top + (0.8f * e.size + i * 1.2f * e.size) / dispH
                putText(cs, font, winAnsi(lines[i]), e.size, user(e.left, by), rot)
            }
        } else if (e.kind == 1) {
            val a = user(e.left, e.top)
            val b = user(e.right, e.bottom)
            cs.setNonStrokingColor(255, 255, 255)
            cs.addRect(minOf(a[0], b[0]), minOf(a[1], b[1]), Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]))
            cs.fill()
            cs.setNonStrokingColor(0, 0, 0)
            val boxW = (e.right - e.left) * dispW
            val boxH = (e.bottom - e.top) * dispH
            val lines = e.text.split("\n").map { winAnsi(it) }
            var size = e.size
            var maxW = 0f
            for (l in lines) maxW = maxOf(maxW, font.getStringWidth(l) / 1000f * size)
            if (maxW > boxW - 4f && maxW > 0f) size = maxOf(5f, size * (boxW - 4f) / maxW)
            for (i in lines.indices) {
                val by = if (lines.size == 1) e.top + (boxH / 2f + size * 0.35f) / dispH
                else e.top + (0.9f * size + i * 1.15f * size + 1.5f) / dispH
                putText(cs, font, lines[i], size, user(e.left + 2f / dispW, by), rot)
            }
        } else if (e.kind == 3) {
            val bm = e.sig ?: return
            val key = e.sigPath ?: ("sig@" + System.identityHashCode(bm))
            val img: PDImageXObject = imgCache[key]
                ?: LosslessFactory.createFromImage(doc, bm).also { imgCache[key] = it }
            val o = user(e.left, e.bottom)
            val xr = user(e.right, e.bottom)
            val yt = user(e.left, e.top)
            cs.saveGraphicsState()
            cs.transform(com.tom_roush.pdfbox.util.Matrix(xr[0] - o[0], xr[1] - o[1], yt[0] - o[0], yt[1] - o[1], o[0], o[1]))
            cs.drawImage(img, 0f, 0f, 1f, 1f)
            cs.restoreGraphicsState()
        } else {
            val boxW = (e.right - e.left) * dispW
            val boxH = (e.bottom - e.top) * dispH
            val size = minOf(boxW, boxH) * 0.9f
            val w = bold.getStringWidth("X") / 1000f * size
            val cx = (e.left + e.right) / 2f
            val cy = (e.top + e.bottom) / 2f
            putText(cs, bold, "X", size, user(cx - (w / 2f) / dispW, cy + (size * 0.35f) / dispH), rot)
        }
    }

    // ------------------------------------------------------------ Symbolleiste, Lesemodus, Bilder-Vorlauf, Signaturen

    private fun iconButton(sym: String, desc: String, w: Int = 44, action: () -> Unit): TextView {
        val t = TextView(this)
        t.text = sym
        t.textSize = 22f
        t.setTextColor(fg())
        t.gravity = Gravity.CENTER
        t.contentDescription = desc
        t.layoutParams = LinearLayout.LayoutParams(dp(w), dp(44))
        t.setOnClickListener { action() }
        return t
    }

    // ---- Verschieben von Texten und Signaturen

    private fun dragStart(x: Float, y: Float): Boolean {
        dragEdit = null
        if (!editMode || topBox.visibility == View.VISIBLE) return false
        val v = pages.findChildViewUnder(x, y) as? PageView ?: return false
        val p = v.tag as? Int ?: return false
        if (v.height <= 0) return false
        val fx = (x - v.left + panX) / (screenW * zoom)
        val fy = (y - v.top) / v.height.toFloat()
        for (e in edits.asReversed()) {
            if (e.page == p && (e.kind == 0 || e.kind == 3) && hitEdit(e, fx, fy)) {
                dragEdit = e
                dragView = v
                dragX0 = x
                dragY0 = y
                dragL0 = e.left
                dragT0 = e.top
                dragR0 = e.right
                dragB0 = e.bottom
                return true
            }
        }
        return false
    }

    private fun dragMove(x: Float, y: Float) {
        val e = dragEdit ?: return
        val v = dragView ?: return
        if (v.height <= 0) return
        val dx = (x - dragX0) / (screenW * zoom)
        val dy = (y - dragY0) / v.height.toFloat()
        if (e.kind == 0) {
            e.left = (dragL0 + dx).coerceIn(0f, 0.97f)
            e.top = (dragT0 + dy).coerceIn(0f, 0.97f)
        } else {
            val w = dragR0 - dragL0
            val h = dragB0 - dragT0
            e.left = (dragL0 + dx).coerceIn(0f, maxOf(0f, 1f - w))
            e.top = (dragT0 + dy).coerceIn(0f, maxOf(0f, 1f - h))
            e.right = e.left + w
            e.bottom = e.top + h
        }
        v.invalidate()
    }

    private fun dragEnd() {
        dragEdit = null
        dragView = null
    }

    // ---- Lesemodus

    private fun applySystemUi() {
        var f = if (darkMode) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        if (readingMode) {
            f = f or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.decorView.systemUiVisibility = f
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && readingMode) applySystemUi()
    }

    private fun toggleReading() {
        readingMode = !readingMode
        getSharedPreferences("einstellungen", MODE_PRIVATE).edit().putBoolean("reading", readingMode).apply()
        applySystemUi()
        pageInd.visibility = if (readingMode && pageCount > 0) View.VISIBLE else View.GONE
        updatePageInd()
        refreshVisible()
        closeMenu()
        hideBar()
        if (readingMode) {
            toast("Lesemodus an: Vollbild, Bildschirm bleibt an, warmer Papierton (im hellen Modus). Tippe auf die Seite, um die Leiste zu holen.")
        }
    }

    private fun updatePageInd() {
        if (!this::pageInd.isInitialized || pageCount == 0) return
        var p = -1
        val c = pages.findChildViewUnder(pages.width / 2f, pages.height / 2f)
        if (c != null) p = pages.getChildAdapterPosition(c)
        if (p < 0) p = lm.findFirstVisibleItemPosition()
        if (p < 0) return
        pageInd.text = "${p + 1} / $pageCount"
    }

    // ---- Bilder im Dunkelmodus: alle Seiten im Hintergrund vorab auswerten (sichtbare Seite zuerst)

    private fun nextUndetected(seen: HashSet<Int>): Int {
        val n = pageCount
        if (n == 0) return -1
        val c = viewPos.coerceIn(0, n - 1)
        for (d in 0 until n) {
            val a = c + d
            if (a < n && !imgRects.containsKey(a) && !seen.contains(a)) return a
            val b = c - d
            if (d > 0 && b >= 0 && !imgRects.containsKey(b) && !seen.contains(b)) return b
        }
        return -1
    }

    private fun startImagePrefetch() {
        if (!darkMode || pageCount == 0) return
        val f = currentFile ?: return
        val token = loadToken
        if (prefetchFor == token) return
        prefetchFor = token
        auxExec.execute {
            var fails = 0
            val seen = HashSet<Int>()
            try {
                while (token == loadToken && darkMode && fails < 3) {
                    val p = nextUndetected(seen)
                    if (p < 0) break
                    seen.add(p)
                    var res: List<RectF> = emptyList()
                    try {
                        res = detectImages(f, p)
                    } catch (e: Throwable) {
                        fails++
                    }
                    if (token != loadToken) break
                    main.post {
                        if (token == loadToken) {
                            imgRects[p] = res
                            refreshPage(p)
                        }
                    }
                }
            } finally {
                if (prefetchFor == token) prefetchFor = -1
            }
        }
    }

    // ---- Signaturen

    private fun sigDir(): File {
        val d = File(filesDir, "signatures")
        d.mkdirs()
        return d
    }

    private fun sigFiles(): List<File> {
        val l = sigDir().listFiles { f -> f.name.endsWith(".png") } ?: return emptyList()
        return l.sortedByDescending { it.lastModified() }
    }

    private fun saveSignature(b: Bitmap) {
        val f = File(sigDir(), "sig_" + System.currentTimeMillis() + ".png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun showSignatureChooser() {
        if (pageCount == 0) {
            toast("Bitte zuerst eine PDF öffnen.")
            return
        }
        val files = sigFiles()
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        scroll.addView(box)
        val info = TextView(this)
        info.text = if (files.isEmpty()) "Noch keine Signatur gespeichert. Zeichne eine neue oder nimm ein Foto deiner Unterschrift (dunkler Stift auf weißem Papier)."
        else "Antippen = einfügen · Lange drücken = löschen"
        info.textSize = 13f
        box.addView(info)
        var dlg: AlertDialog? = null
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val g = GradientDrawable()
            g.setColor(Color.WHITE)
            g.setStroke(dp(1), Color.rgb(190, 190, 190))
            g.cornerRadius = dp(8).toFloat()
            iv.background = g
            iv.setPadding(dp(8), dp(8), dp(8), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90))
            lp.topMargin = dp(10)
            iv.layoutParams = lp
            iv.setOnClickListener {
                dlg?.dismiss()
                placeSignature(f)
            }
            iv.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Signatur löschen?")
                    .setPositiveButton("Löschen") { _, _ ->
                        f.delete()
                        dlg?.dismiss()
                        showSignatureChooser()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                true
            }
            box.addView(iv)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle("Signatur wählen")
            .setView(scroll)
            .setPositiveButton("Neu zeichnen") { _, _ -> showSignaturePad() }
            .setNeutralButton("Aus Foto") { _, _ -> pick("image/*", REQ_SIGIMG) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showSignaturePad() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val hint = TextView(this)
        hint.text = "Unterschreibe mit dem Finger in der Fläche."
        hint.textSize = 13f
        box.addView(hint)
        val pad = SignaturePad(this)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
        lp.topMargin = dp(8)
        box.addView(pad, lp)
        val d = AlertDialog.Builder(this)
            .setTitle("Neue Signatur")
            .setView(box)
            .setPositiveButton("Speichern", null)
            .setNeutralButton("Neu beginnen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val b = pad.toBitmap()
            if (b == null) {
                toast("Bitte zuerst unterschreiben.", false)
            } else {
                saveSignature(b)
                d.dismiss()
                toast("Signatur gespeichert.", false)
                showSignatureChooser()
            }
        }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
    }

    /** Foto einer Unterschrift: Papier wird transparent, die Tinte schwarz. */
    private fun signatureFromPhoto(src: Bitmap): Bitmap? {
        var bm = src
        val maxSide = 1400
        val big = maxOf(bm.width, bm.height)
        if (big > maxSide) {
            val s = maxSide.toFloat() / big
            bm = Bitmap.createScaledBitmap(bm, maxOf(1, (bm.width * s).toInt()), maxOf(1, (bm.height * s).toInt()), true)
        }
        val w = bm.width
        val h = bm.height
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val l = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt().coerceIn(0, 255)
            lum[i] = l
            hist[l]++
        }
        var acc = 0
        var paperL = 255
        val target = (w * h * 0.8f).toInt()
        for (i in 0..255) {
            acc += hist[i]
            if (acc >= target) {
                paperL = i
                break
            }
        }
        val hi = paperL * 0.88f
        val lo = paperL * 0.55f
        if (hi - lo < 8f) return null
        val alpha = IntArray(w * h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = ((hi - lum[i]) / (hi - lo) * 255f).toInt().coerceIn(0, 255)
                alpha[i] = a
                if (a > 60) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val pad = 6
        minX = maxOf(0, minX - pad)
        minY = maxOf(0, minY - pad)
        maxX = minOf(w - 1, maxX + pad)
        maxY = minOf(h - 1, maxY + pad)
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        if (cw < 20 || ch < 8) return null
        val out = IntArray(cw * ch)
        var ink = 0
        for (y in 0 until ch) {
            for (x in 0 until cw) {
                val a = alpha[(y + minY) * w + (x + minX)]
                out[y * cw + x] = a shl 24
                if (a > 60) ink++
            }
        }
        if (ink < 30) return null
        return Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
    }

    private fun importSignatureImage(u: Uri) {
        toast("Verarbeite Foto …", false)
        ioExec.execute {
            var result: Bitmap? = null
            try {
                val src = loadBitmap(u)
                if (src != null) result = signatureFromPhoto(src)
            } catch (e: Throwable) {
            }
            main.post {
                val b = result
                if (b == null) {
                    toast("Keine Unterschrift erkannt. Fotografiere sie mit dunklem Stift auf weißem Papier bei guter Beleuchtung.")
                } else {
                    saveSignature(b)
                    toast("Signatur gespeichert.", false)
                    showSignatureChooser()
                }
            }
        }
    }

    /** Fügt die gewählte Signatur in der Mitte der sichtbaren Seite ein; danach mit dem Finger verschiebbar. */
    private fun placeSignature(f: File) {
        val bmp = BitmapFactory.decodeFile(f.absolutePath)
        if (bmp == null) {
            toast("Signatur konnte nicht geladen werden.")
            return
        }
        var best: PageView? = null
        var bestVis = 0
        for (i in 0 until pages.childCount) {
            val v = pages.getChildAt(i) as? PageView ?: continue
            val vis = Rect()
            if (!v.getLocalVisibleRect(vis)) continue
            if (vis.height() > bestVis) {
                bestVis = vis.height()
                best = v
            }
        }
        val v = best ?: return
        val p = v.tag as? Int ?: return
        if (v.height <= 0 || p >= pageW.size) return
        val vis = Rect()
        v.getLocalVisibleRect(vis)
        val wFrac = 0.30f
        val hFrac = (wFrac * bmp.height / bmp.width * pageW[p] / pageH[p]).coerceAtMost(0.5f)
        val cx = (panX + screenW / 2f) / (screenW * zoom)
        val cy = (vis.top + vis.bottom) / 2f / v.height.toFloat()
        val left = (cx - wFrac / 2f).coerceIn(0f, 1f - wFrac)
        val top = (cy - hFrac / 2f).coerceIn(0f, 1f - hFrac)
        val e = Edit(p, left, top, left + wFrac, top + hFrac, "", 0f, 3, null, null)
        e.sig = bmp
        e.sigPath = f.absolutePath
        edits.add(e)
        refreshVisible()
        toast("Signatur eingefügt: mit dem Finger verschieben, antippen für Größe oder Löschen.")
    }

    private fun scaleSignature(e: Edit, factor: Float) {
        val ow = e.right - e.left
        val oh = e.bottom - e.top
        if (ow <= 0f) return
        val cx = (e.left + e.right) / 2f
        val cy = (e.top + e.bottom) / 2f
        val w = (ow * factor).coerceIn(0.05f, 0.9f)
        val h = (oh * (w / ow)).coerceAtMost(0.9f)
        e.left = (cx - w / 2f).coerceIn(0f, maxOf(0f, 1f - w))
        e.top = (cy - h / 2f).coerceIn(0f, maxOf(0f, 1f - h))
        e.right = e.left + w
        e.bottom = e.top + h
        refreshVisible()
    }

    private fun editSignature(e: Edit) {
        val d = AlertDialog.Builder(this)
            .setTitle("Signatur")
            .setMessage("Mit dem Finger auf der Seite verschieben. Hier die Größe ändern:")
            .setPositiveButton("Größer", null)
            .setNegativeButton("Kleiner", null)
            .setNeutralButton("Löschen") { _, _ ->
                edits.remove(e)
                refreshVisible()
            }
            .create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { scaleSignature(e, 1.2f) }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { scaleSignature(e, 1f / 1.2f) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screenW = resources.displayMetrics.widthPixels
        barTx = 0f
        barTy = 0f
        if (this::editBar.isInitialized) {
            editBar.translationX = 0f
            editBar.translationY = 0f
        }
        cache.evictAll()
        clampPan()
        if (pageCount > 0) {
            adapter.notifyDataSetChanged()
            scheduleTiles()
        }
    }

    // ------------------------------------------------------------ Zoom

    private fun pageHeightPx(pos: Int): Int = (screenW * zoom * pageH[pos] / pageW[pos]).toInt()

    private fun clampPan() {
        panX = panX.coerceIn(0f, maxOf(0f, (zoom - 1f) * screenW))
    }

    private fun applyZoom(factor: Float, fx: Float, fy: Float) {
        if (pageCount == 0) return
        var nz = (zoom * factor).coerceIn(1f, 5f)
        if (nz < 1.03f) nz = 1f
        if (abs(nz - zoom) < 0.001f) return
        val child = pages.findChildViewUnder(fx, fy)
        var pos = -1
        var frac = 0f
        if (child != null && child.height > 0) {
            pos = pages.getChildAdapterPosition(child)
            frac = (fy - child.top) / child.height.toFloat()
        }
        val f = nz / zoom
        panX = (panX + fx) * f - fx
        zoom = nz
        if (zoom == 1f) panX = 0f
        clampPan()
        adapter.notifyDataSetChanged()
        if (pos >= 0) lm.scrollToPositionWithOffset(pos, (fy - frac * pageHeightPx(pos)).toInt())
        scheduleTiles()
    }

    private fun applyPan(dx: Float) {
        val old = panX
        panX += dx
        clampPan()
        if (panX == old) return
        for (i in 0 until pages.childCount) {
            val v = pages.getChildAt(i) as? PageView ?: continue
            v.panX = panX
            v.invalidate()
        }
        scheduleTiles()
    }

    private fun scheduleTiles() {
        main.removeCallbacks(tileRunnable)
        main.postDelayed(tileRunnable, 180)
    }

    /** Rendert für die gerade sichtbaren Seitenausschnitte eine scharfe Version in der aktuellen Vergrößerung. */
    private fun requestTiles() {
        if (pageCount == 0) return
        if (zoom <= 1.05f) {
            for (i in 0 until pages.childCount) {
                val v = pages.getChildAt(i) as? PageView ?: continue
                v.tile = null
                v.tileRect = null
                v.invalidate()
            }
            return
        }
        val r = renderer ?: return
        val token = loadToken
        val z = zoom
        val zW = screenW * z
        for (i in 0 until pages.childCount) {
            val v = pages.getChildAt(i) as? PageView ?: continue
            val pos = v.tag as? Int ?: continue
            val vis = Rect()
            if (!v.getLocalVisibleRect(vis)) continue
            if (vis.height() < 20 || v.height <= 0) continue
            val top = maxOf(0, vis.top - 120)
            val bottom = minOf(v.height, vis.bottom + 120)
            val th = bottom - top
            if (th < 20) continue
            val zH = v.height.toFloat()
            val leftPx = panX
            val frac = RectF(leftPx / zW, top / zH, (leftPx + screenW) / zW, bottom / zH)
            renderExec.execute {
                var result: Bitmap? = null
                try {
                    synchronized(lock) {
                        if (token != loadToken || renderer !== r || abs(zoom - z) > 0.02f) return@execute
                        val page = r.openPage(pos)
                        val s = zW / page.width
                        val b = Bitmap.createBitmap(screenW, th, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        val mx = Matrix()
                        mx.postScale(s, s)
                        mx.postTranslate(-leftPx, -top.toFloat())
                        page.render(b, null, mx, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        result = b
                    }
                } catch (e: Throwable) {
                }
                val done = result ?: return@execute
                main.post {
                    if (token == loadToken && v.tag == pos && abs(zoom - z) < 0.02f) {
                        v.tile = done
                        v.tileRect = frac
                        v.invalidate()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ Auswahl & Speichern

    private fun pick(mime: String, req: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        if (req == REQ_MERGE || req == REQ_IMAGES) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, req)
    }

    private fun askSave(defaultName: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_TITLE, defaultName)
        startActivityForResult(i, REQ_SAVE)
    }

    private fun urisOf(data: Intent): List<Uri> {
        val cd = data.clipData
        if (cd != null) return (0 until cd.itemCount).map { cd.getItemAt(it).uri }
        val u = data.data
        return if (u != null) listOf(u) else emptyList()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_OPEN -> data.data?.let { openPdf(it) }
            REQ_MERGE -> {
                val list = urisOf(data)
                if (list.size < 2) {
                    toast("Bitte mindestens 2 PDFs auswählen.")
                } else {
                    pendingUris = list
                    pendingMode = 1
                    askSave("Zusammengefuegt.pdf")
                }
            }
            REQ_IMAGES -> {
                val list = urisOf(data)
                if (list.isEmpty()) {
                    toast("Keine Bilder ausgewählt.")
                } else {
                    pendingUris = list
                    pendingMode = 2
                    askSave("Bilder.pdf")
                }
            }
            REQ_SAVE -> data.data?.let { writeResult(it) }
            REQ_SIGIMG -> data.data?.let { importSignatureImage(it) }
        }
    }

    private fun writeResult(target: Uri) {
        val mode = pendingMode
        val uris = pendingUris
        val src = currentFile
        val snap = ArrayList<Edit>(edits)
        val dw = pageW
        val dh = pageH
        val keep = Restore(
            lm.findFirstVisibleItemPosition().coerceAtLeast(0),
            pages.getChildAt(0)?.top ?: 0,
            zoom,
            panX
        )
        setStatus("Erstelle PDF …")
        ioExec.execute {
            try {
                contentResolver.openOutputStream(target, "wt")!!.use { out ->
                    if (mode == 1) {
                        mergeTo(uris, out)
                    } else if (mode == 3) {
                        if (src != null) saveEdited(src, snap, dw, dh, out)
                    } else {
                        imagesTo(uris, out)
                    }
                    out.flush()
                }
                main.post {
                    toast("PDF gespeichert.")
                    if (mode == 3) openPdf(target, keep) else openPdf(target)
                }
            } catch (e: Throwable) {
                main.post {
                    setStatus("Fehler beim Erstellen: ${e.message}")
                    toast("Fehler beim Erstellen: ${e.message}")
                }
            }
        }
    }

    private fun mergeTo(uris: List<Uri>, out: OutputStream) {
        val m = PDFMergerUtility()
        for (u in uris) m.addSource(contentResolver.openInputStream(u))
        m.setDestinationStream(out)
        m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }

    private fun loadBitmap(u: Uri): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > 2400) s *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = s
        val bmp = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
            ?: return null
        var rot = 0f
        try {
            contentResolver.openInputStream(u)?.use {
                val ori = ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                rot = when (ori) {
                    6 -> 90f
                    3 -> 180f
                    8 -> 270f
                    else -> 0f
                }
            }
        } catch (e: Exception) {
        }
        if (rot == 0f) return bmp
        val mx = Matrix()
        mx.postRotate(rot)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
    }

    private fun imagesTo(uris: List<Uri>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            var pageNo = 0
            for (u in uris) {
                val bmp = loadBitmap(u) ?: continue
                pageNo++
                val pw = 595
                val ph = maxOf(1, (595f * bmp.height / bmp.width).toInt())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), null)
                doc.finishPage(page)
                bmp.recycle()
            }
            if (pageNo == 0) throw IllegalStateException("Keine Bilder lesbar")
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }
}
