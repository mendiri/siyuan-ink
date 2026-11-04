package com.siyuanink

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.net.Uri
import android.os.Bundle
import android.text.InputType

import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var prefs: Prefs
    private var defaultUserAgent = ""
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val bridgeJs by lazy { assets.open("siyuan-ink.js").bufferedReader().use { it.readText() } }

    private val inkLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK) onInkResult()
        }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
            fileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        web = findViewById(R.id.web)
        setupWebView()

        findViewById<Button>(R.id.btnInk).setOnClickListener { requestInk() }
        findViewById<Button>(R.id.btnText).setOnClickListener { openTextMode() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else if (prefs.serverUrl.isEmpty()) {
            showSettings(firstRun = true)
        } else {
            loadServer()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        defaultUserAgent = web.settings.userAgentString
        applyUserAgent()
        web.addJavascriptInterface(Bridge(), "SiYuanInkNative")

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(bridgeJs, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                val server = Uri.parse(prefs.serverUrl)
                if (target.host == server.host && target.port == server.port) return false
                // Enlaces externos: al navegador del sistema.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, target))
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
    }

    private fun applyUserAgent() {
        web.settings.userAgentString = if (prefs.desktopUi) {
            // SiYuan elige la interfaz móvil o de escritorio según el User-Agent.
            val chrome = Regex("Chrome/[0-9.]+").find(defaultUserAgent)?.value ?: "Chrome/120.0.0.0"
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else {
            defaultUserAgent
        }
    }

    private fun loadServer() {
        val url = prefs.serverUrl
        if (url.isNotEmpty()) web.loadUrl(url)
    }

    // ---- Botones ------------------------------------------------------------------------------

    private fun requestInk() {
        web.evaluateJavascript(
            "(function(){ if (!window.__siyuanInk) return 'no'; window.__siyuanInk.requestInk(); return 'ok'; })()"
        ) { r ->
            if (r?.contains("ok") != true) toast("SiYuan aún no ha cargado")
        }
    }

    private fun openTextMode() {
        InkSession.reset(InkSession.MODE_TEXT)
        inkLauncher.launch(Intent(this, InkActivity::class.java))
    }

    private fun openInkEditor(blockId: String, json: String) {
        InkSession.reset(InkSession.MODE_INK)
        InkSession.blockId = blockId
        if (json.isNotBlank()) {
            try {
                InkSession.initial = Drawing.fromJson(json)
                InkSession.editing = true
            } catch (e: Exception) {
                toast("No se pudo leer el dibujo: ${e.message}")
                return
            }
        }
        inkLauncher.launch(Intent(this, InkActivity::class.java))
    }

    private fun onInkResult() {
        val result = InkSession.result ?: return
        InkSession.result = null
        InkSession.background = null
        if (InkSession.mode == InkSession.MODE_PDF) {
            savePdfInk(result.drawing ?: Drawing())
            return
        }
        if (InkSession.mode == InkSession.MODE_TEXT) {
            if (result.text.isBlank()) return
            web.evaluateJavascript("window.__siyuanInk && window.__siyuanInk.insertText(${JSONObject.quote(result.text)})", null)
        } else {
            val payload = JSONObject()
                .put("blockId", InkSession.blockId)
                .put("editing", InkSession.editing)
                .put("svg", result.svg)
                .put("json", result.json)
                .put("text", result.text)
            toast("Guardando en SiYuan…")
            web.evaluateJavascript("window.__siyuanInk && window.__siyuanInk.save($payload)", null)
        }
    }

    // ---- Anotar PDF ---------------------------------------------------------------------------

    /**
     * [ctxJson] viene de siyuan-ink.js: clave del PDF y páginas visibles con su posición en
     * píxeles CSS y sus trazos guardados (coordenadas normalizadas: el ancho de la página = 1000).
     */
    private fun openPdfEditor(ctxJson: String) {
        val ctx = try { JSONObject(ctxJson) } catch (e: Exception) { toast("Datos del PDF no válidos"); return }
        val density = resources.displayMetrics.density
        val pages = ArrayList<PdfPage>()
        val initial = Drawing()
        val arr = ctx.getJSONArray("pages")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pg = PdfPage(
                o.getString("n"), o.getDouble("left").toFloat(), o.getDouble("top").toFloat(),
                o.getDouble("width").toFloat(), o.getDouble("height").toFloat()
            )
            pages.add(pg)
            val strokes = o.optJSONArray("strokes") ?: continue
            val scale = pg.width / 1000f
            for (j in 0 until strokes.length()) {
                val so = strokes.getJSONObject(j)
                val pts = so.getJSONArray("p")
                val list = ArrayList<InkPoint>(pts.length())
                for (k in 0 until pts.length()) {
                    val p = pts.getJSONArray(k)
                    list.add(
                        InkPoint(
                            (pg.left + p.getDouble(0).toFloat() * scale) * density,
                            (pg.top + p.getDouble(1).toFloat() * scale) * density,
                            p.optDouble(2, 0.5).toFloat(), 0L
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    initial.strokes.add(Stroke((so.getDouble("w").toFloat() * scale * density).coerceAtLeast(1f), list))
                }
            }
        }

        captureWebView { bmp ->
            // Ya tenemos la captura sin anotaciones: las volvemos a mostrar en SiYuan.
            web.evaluateJavascript("window.__siyuanInk && window.__siyuanInk.showLayers()", null)
            InkSession.reset(InkSession.MODE_PDF)
            InkSession.pdfKey = ctx.getString("key")
            InkSession.pdfPages = pages
            InkSession.background = bmp
            InkSession.initial = initial
            inkLauncher.launch(Intent(this, InkActivity::class.java))
        }
    }

    /** Captura lo que se ve en la vista web (incluidas las páginas del PDF) para usarlo de fondo. */
    private fun captureWebView(done: (Bitmap?) -> Unit) {
        val w = web.width
        val h = web.height
        if (w <= 0 || h <= 0) { done(null); return }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val fallback = {
            try { web.draw(Canvas(bmp)); done(bmp) } catch (e: Exception) { done(null) }
        }
        try {
            val loc = IntArray(2)
            web.getLocationInWindow(loc)
            PixelCopy.request(
                window, Rect(loc[0], loc[1], loc[0] + w, loc[1] + h), bmp,
                { res -> if (res == PixelCopy.SUCCESS) done(bmp) else fallback() },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            fallback()
        }
    }

    /** Reparte los trazos entre las páginas visibles y los envía a SiYuan normalizados. */
    private fun savePdfInk(drawing: Drawing) {
        val pages = InkSession.pdfPages
        if (pages.isEmpty()) return
        val density = resources.displayMetrics.density
        val perPage = LinkedHashMap<String, JSONArray>()
        for (pg in pages) perPage[pg.n] = JSONArray()
        for (s in drawing.strokes) {
            val cx = s.bounds.centerX() / density
            val cy = s.bounds.centerY() / density
            val pg = pages.firstOrNull { cx >= it.left && cx <= it.left + it.width && cy >= it.top && cy <= it.top + it.height }
                ?: pages.minByOrNull { minOf(abs(cy - it.top), abs(cy - (it.top + it.height))) }
                ?: continue
            val k = 1000f / pg.width
            val pts = JSONArray()
            for (p in s.points) {
                pts.put(
                    JSONArray().put(r1((p.x / density - pg.left) * k)).put(r1((p.y / density - pg.top) * k))
                        .put(Math.round(p.pressure * 100.0) / 100.0)
                )
            }
            perPage[pg.n]!!.put(JSONObject().put("w", r1(s.width / density * k)).put("p", pts))
        }
        val pagesObj = JSONObject()
        for ((n, a) in perPage) pagesObj.put(n, a)
        val payload = JSONObject().put("key", InkSession.pdfKey).put("pages", pagesObj)
        toast("Guardando anotación…")
        web.evaluateJavascript("window.__siyuanInk && window.__siyuanInk.savePdf($payload)", null)
    }

    private fun r1(v: Float): Double = Math.round(v * 10.0) / 10.0

    // ---- Ajustes ------------------------------------------------------------------------------

    private fun showSettings(firstRun: Boolean = false) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(this).apply {
            text = "Dirección de tu servidor SiYuan (por ejemplo http://192.168.1.50:6806)."
        }
        val url = EditText(this).apply {
            setText(prefs.serverUrl.ifEmpty { "http://" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val desktop = CheckBox(this).apply {
            text = "Usar la interfaz de escritorio de SiYuan"
            isChecked = prefs.desktopUi
        }
        val status = TextView(this).apply {
            text = "\nReconocimiento de escritura: ${Recognizer.status}\n" +
                "Si tu SiYuan tiene código de acceso, te lo pedirá la propia página la primera vez."
            textSize = 13f
        }
        layout.addView(label)
        layout.addView(url)
        layout.addView(desktop)
        layout.addView(status)

        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "Conectar con SiYuan" else "Ajustes")
            .setView(layout)
            .setCancelable(!firstRun)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.serverUrl = url.text.toString()
                prefs.desktopUi = desktop.isChecked
                applyUserAgent()
                loadServer()
            }
            .apply { if (!firstRun) setNegativeButton("Cancelar", null) }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- Puente JavaScript ↔ Android --------------------------------------------------------

    inner class Bridge {
        @JavascriptInterface
        fun openEditor(blockId: String?, json: String?) {
            runOnUiThread { openInkEditor(blockId ?: "", json ?: "") }
        }

        @JavascriptInterface
        fun openPdfInk(ctxJson: String?) {
            runOnUiThread { if (ctxJson != null) openPdfEditor(ctxJson) }
        }

        @JavascriptInterface
        fun notify(ok: Boolean, message: String?) {
            runOnUiThread { toast(if (ok) (message ?: "Hecho") else "Error: ${message ?: "desconocido"}") }
        }

        @JavascriptInterface
        fun recognizerStatus(): String = Recognizer.status
    }

    override fun onDestroy() {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

}
