package com.siyuanink

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
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
import org.json.JSONObject

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
