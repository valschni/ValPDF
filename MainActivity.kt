package de.pdfapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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

/** Zeigt eine Seite (Basisbild + scharfer Ausschnitt beim Zoomen) und malt Suchtreffer darüber. */
class PageView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
    var tile: Bitmap? = null
    var tileRect: RectF? = null
    var zoom = 1f
    var panX = 0f
    var hits: List<RectF> = emptyList()
    var current: List<RectF> = emptyList()
    var dark = false
    private val back = Paint()
    private val yellow = Paint()
    private val orange = Paint()
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val invert = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    init {
        yellow.color = Color.argb(110, 255, 235, 59)
        orange.color = Color.argb(150, 255, 152, 0)
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
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val zW = vw * zoom
        val zH = height.toFloat()
        back.color = if (dark) Color.rgb(15, 15, 15) else Color.WHITE
        canvas.drawRect(0f, 0f, vw, zH, back)
        val paint = if (dark) invert else plain
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
        for (r in hits) canvas.drawRect(r.left * zW - panX, r.top * zH, r.right * zW - panX, r.bottom * zH, yellow)
        for (r in current) canvas.drawRect(r.left * zW - panX, r.top * zH, r.right * zW - panX, r.bottom * zH, orange)
    }
}

/** Seitenliste mit Zwei-Finger-Zoom, seitlichem Verschieben, Tippen und Doppeltippen. */
class ZoomRecycler(ctx: Context) : RecyclerView(ctx) {
    var allowPan: () -> Boolean = { false }
    var onZoom: (Float, Float, Float) -> Unit = { _, _, _ -> }
    var onPan: (Float) -> Unit = {}
    var onTap: () -> Unit = {}
    var onDoubleTapAt: (Float, Float) -> Unit = { _, _ -> }

    private var blocked = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastX = 0f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private val tapRunnable = Runnable { onTap() }

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
            downX = ev.x
            downY = ev.y
            downTime = ev.eventTime
            moved = false
            lastX = ev.x
        }
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) {
            moved = true
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
        if (a == MotionEvent.ACTION_MOVE) {
            if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) moved = true
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
        window.decorView.systemUiVisibility =
            if (darkMode) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)

        // Seitenliste
        pages = ZoomRecycler(this)
        pages.allowPan = { zoom > 1.05f }
        pages.onZoom = { f, x, y -> applyZoom(f, x, y) }
        pages.onPan = { dx -> applyPan(dx) }
        pages.onTap = { onPageTap() }
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
            pick("application/pdf", REQ_OPEN)
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("➕   PDFs zusammenfügen") {
            closeMenu()
            pick("application/pdf", REQ_MERGE)
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem("🖼   Bilder zu PDF") {
            closeMenu()
            pick("image/*", REQ_IMAGES)
        })
        menuPanel.addView(divider())
        menuPanel.addView(menuItem(if (darkMode) "☀   Heller Modus" else "🌙   Dunkelmodus") { toggleDark() })
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

        val root = FrameLayout(this)
        root.setBackgroundColor(if (darkMode) Color.rgb(8, 8, 8) else Color.rgb(200, 200, 200))
        root.addView(pages, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            emptyHint,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        root.addView(
            navBox,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
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
        searchExec.execute { closeFallback() }
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
    private fun openPdf(uri: Uri) {
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
            loadRenderer(target, name, token, null)
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
        if (req != REQ_OPEN) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        }
    }

    private fun writeResult(target: Uri) {
        val mode = pendingMode
        val uris = pendingUris
        setStatus("Erstelle PDF …")
        ioExec.execute {
            try {
                contentResolver.openOutputStream(target, "wt")!!.use { out ->
                    if (mode == 1) mergeTo(uris, out) else imagesTo(uris, out)
                }
                main.post {
                    toast("PDF gespeichert.")
                    openPdf(target)
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
