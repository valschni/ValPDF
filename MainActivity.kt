package de.pdfapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

/** Text einer Seite (klein geschrieben) plus Position jedes Zeichens (0..1 der Seitengröße). */
class PageText(val low: String, val rects: Array<RectF?>)

/** Ein Suchtreffer: Seite und die Markierungs-Rechtecke. */
class Match(val page: Int, val rects: List<RectF>)

/** Liest den Text einer Seite samt Positionen. */
class Collector : PDFTextStripper() {
    val sb = StringBuilder()
    val rs = ArrayList<RectF?>()
    var pw = 1f
    var ph = 1f

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (text == null) return
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

/** Zeigt eine Seite und malt Suchtreffer darüber. */
class PageView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
    var hits: List<RectF> = emptyList()
    var current: List<RectF> = emptyList()
    private val white = Paint()
    private val yellow = Paint()
    private val orange = Paint()
    private val dst = RectF()

    init {
        white.color = Color.WHITE
        yellow.color = Color.argb(110, 255, 235, 59)
        orange.color = Color.argb(150, 255, 152, 0)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, white)
        val b = bmp
        if (b != null) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(b, null, dst, null)
        }
        for (r in hits) canvas.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, yellow)
        for (r in current) canvas.drawRect(r.left * w, r.top * h, r.right * w, r.bottom * h, orange)
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

    @Volatile private var index: List<PageText>? = null
    private var matches: List<Match> = emptyList()
    private var currentMatch = 0
    private var highlights: Map<Int, List<RectF>> = emptyMap()

    private var pendingUris: List<Uri> = emptyList()
    private var pendingMode = 0

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    private lateinit var status: TextView
    private lateinit var searchBox: EditText
    private lateinit var navRow: LinearLayout
    private lateinit var counter: TextView
    private lateinit var pages: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var adapter: PageAdapter

    // ------------------------------------------------------------ Oberfläche

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        screenW = resources.displayMetrics.widthPixels
        val den = resources.displayMetrics.density
        val pad = (8 * den).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.setPadding(pad, pad, pad, 0)
        row1.addView(btn("Öffnen", 1f) { pick("application/pdf", REQ_OPEN) })
        row1.addView(btn("Zusammenfügen", 1f) { pick("application/pdf", REQ_MERGE) })
        row1.addView(btn("Bilder → PDF", 1f) { pick("image/*", REQ_IMAGES) })
        root.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER_VERTICAL
        row2.setPadding(pad, 0, pad, 0)
        searchBox = EditText(this)
        searchBox.hint = "In der PDF suchen"
        searchBox.setSingleLine(true)
        searchBox.imeOptions = EditorInfo.IME_ACTION_SEARCH
        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else {
                false
            }
        }
        searchBox.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row2.addView(searchBox)
        row2.addView(btn("Suchen", 0f) { doSearch() })
        root.addView(row2)

        navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = Gravity.CENTER_VERTICAL
        navRow.setPadding(pad, 0, pad, 0)
        counter = TextView(this)
        counter.gravity = Gravity.CENTER
        counter.textSize = 16f
        counter.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        navRow.addView(btn("◀ Zurück", 0f) { step(-1) })
        navRow.addView(counter)
        navRow.addView(btn("Weiter ▶", 0f) { step(1) })
        navRow.visibility = View.GONE
        root.addView(navRow)

        status = TextView(this)
        status.textSize = 13f
        status.setTextColor(Color.DKGRAY)
        status.setPadding(pad * 2, pad / 2, pad * 2, pad / 2)
        status.text = "Tippe oben auf „Öffnen“, um eine PDF zu wählen."
        root.addView(status)

        pages = RecyclerView(this)
        lm = LinearLayoutManager(this)
        pages.layoutManager = lm
        adapter = PageAdapter()
        pages.adapter = adapter
        pages.setBackgroundColor(Color.rgb(200, 200, 200))
        pages.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(pages)

        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        loadToken++
        synchronized(lock) {
            try {
                renderer?.close()
            } catch (e: Exception) {
            }
            renderer = null
        }
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.data
        if (i != null && i.action == Intent.ACTION_VIEW && u != null) openPdf(u)
    }

    private fun btn(label: String, weight: Float, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        val w = if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        b.layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        return b
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun hideKeyboard() {
        val m = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        m.hideSoftInputFromWindow(searchBox.windowToken, 0)
    }

    // ------------------------------------------------------------ Seitenanzeige

    inner class PageAdapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(PageView(this@MainActivity))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val wPx = screenW
            val hPx = (wPx * pageH[position] / pageW[position]).toInt()
            val lp = RecyclerView.LayoutParams(wPx, hPx)
            lp.bottomMargin = 6
            holder.v.layoutParams = lp
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

    private fun openPdf(uri: Uri) {
        loadToken++
        val token = loadToken
        status.text = "Öffne PDF …"
        navRow.visibility = View.GONE
        matches = emptyList()
        highlights = emptyMap()
        index = null
        val name = nameOf(uri)
        ioExec.execute {
            val f = File(cacheDir, "pdf_$token.pdf")
            try {
                contentResolver.openInputStream(uri)!!.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
                val r = PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
                val n = r.pageCount
                val w = FloatArray(n)
                val h = FloatArray(n)
                for (i in 0 until n) {
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
                        pageW = w
                        pageH = h
                        pageCount = n
                        adapter.notifyDataSetChanged()
                        lm.scrollToPosition(0)
                        status.text = "$name – $n Seiten – Text wird indexiert …"
                    }
                }
                cacheDir.listFiles()?.forEach {
                    if (it.name.startsWith("pdf_") && it != f) it.delete()
                }
            } catch (e: Throwable) {
                main.post {
                    if (token == loadToken) {
                        status.text = "PDF konnte nicht geöffnet werden (Passwortschutz oder defekte Datei?)"
                    }
                }
                return@execute
            }
            buildIndex(f, token, name)
        }
    }

    // ------------------------------------------------------------ Volltext-Index

    private fun buildIndex(f: File, token: Int, name: String) {
        try {
            PDDocument.load(f).use { doc ->
                val n = doc.numberOfPages
                val c = Collector()
                val out = ArrayList<PageText>(n)
                var chars = 0
                for (i in 1..n) {
                    if (token != loadToken) return
                    val page = doc.getPage(i - 1)
                    val box = page.cropBox
                    var w = box.width
                    var h = box.height
                    val rot = page.rotation
                    if (rot == 90 || rot == 270) {
                        val t = w
                        w = h
                        h = t
                    }
                    c.sb.setLength(0)
                    c.rs.clear()
                    c.pw = if (w > 0f) w else 1f
                    c.ph = if (h > 0f) h else 1f
                    c.startPage = i
                    c.endPage = i
                    c.getText(doc)
                    val s = c.sb.toString()
                    chars += s.trim().length
                    val lowChars = CharArray(s.length) { s[it].lowercaseChar() }
                    out.add(PageText(String(lowChars), c.rs.toTypedArray()))
                    if (i % 5 == 0 || i == n) {
                        main.post {
                            if (token == loadToken) status.text = "$name – Indexiere Seite $i/$n …"
                        }
                    }
                }
                if (token != loadToken) return
                index = out
                val scan = chars < n * 10
                main.post {
                    if (token == loadToken) {
                        status.text = if (scan) {
                            "$name – $n Seiten. Kaum Text gefunden: vermutlich ein Scan. Die Suche funktioniert dafür erst mit der Texterkennung (kommt in Version 2)."
                        } else {
                            "$name – $n Seiten – Suche bereit"
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            main.post {
                if (token == loadToken) status.text = "$name – Textsuche für diese Datei nicht möglich."
            }
        }
    }

    // ------------------------------------------------------------ Suche

    private fun mergeRects(rs: Array<RectF?>, s: Int, e: Int): List<RectF> {
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
        val idx = index
        if (idx == null) {
            toast("Der Text wird noch indexiert – bitte kurz warten.")
            return
        }
        val q = String(CharArray(raw.length) { raw[it].lowercaseChar() })
        val found = ArrayList<Match>()
        for (p in idx.indices) {
            val pt = idx[p]
            var from = 0
            while (true) {
                val i = pt.low.indexOf(q, from)
                if (i < 0) break
                found.add(Match(p, mergeRects(pt.rects, i, i + q.length)))
                from = i + q.length
            }
        }
        if (found.isEmpty()) {
            matches = emptyList()
            highlights = emptyMap()
            navRow.visibility = View.GONE
            adapter.notifyDataSetChanged()
            toast("Keine Treffer für „$raw“")
            return
        }
        val map = HashMap<Int, ArrayList<RectF>>()
        for (m in found) map.getOrPut(m.page) { ArrayList() }.addAll(m.rects)
        matches = found
        highlights = map
        navRow.visibility = View.VISIBLE
        goTo(0)
    }

    private fun step(d: Int) {
        if (matches.isEmpty()) return
        goTo((currentMatch + d + matches.size) % matches.size)
    }

    private fun goTo(k: Int) {
        currentMatch = k
        counter.text = "Treffer ${k + 1} von ${matches.size}"
        adapter.notifyDataSetChanged()
        val m = matches[k]
        val pageHeightPx = screenW * pageH[m.page] / pageW[m.page]
        val y = (m.rects.firstOrNull()?.top ?: 0f) * pageHeightPx
        lm.scrollToPositionWithOffset(m.page, -(y - 300f).toInt())
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
        status.text = "Erstelle PDF …"
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
                    status.text = "Fehler beim Erstellen: ${e.message}"
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
