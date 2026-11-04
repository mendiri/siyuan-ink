package com.siyuanink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lienzo de escritura a pantalla completa con el SDK de Boox (TouchHelper "raw drawing"):
 * el trazo se pinta directamente en la pantalla e-ink, con la misma latencia que la app Notas.
 */
class InkActivity : AppCompatActivity() {

    private lateinit var surface: SurfaceView
    private lateinit var btnPen: Button
    private lateinit var btnEraser: Button
    private lateinit var btnWidth: Button
    private lateinit var btnDone: Button

    private var touchHelper: TouchHelper? = null
    private val drawing = Drawing()
    private val undoStack = ArrayDeque<List<Stroke>>()

    private var inkBitmap: Bitmap? = null
    private var inkCanvas: Canvas? = null

    private var eraser = false
    private var widthIndex = 1
    private var busy = false

    private val density by lazy { resources.displayMetrics.density }
    private val widthsDp = floatArrayOf(1.2f, 2f, 3.5f)
    private val widthNames = arrayOf("fino", "medio", "grueso")
    private val strokeWidthPx get() = widthsDp[widthIndex] * density
    private val eraserRadiusPx get() = 10f * density
    val lineSpacingPx get() = 56f * density

    private val inkPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    /** Presión máxima que da el lápiz de este dispositivo (para normalizarla de 0 a 1). */
    private val maxPressure: Float by lazy {
        val m = try { EpdController.getMaxTouchPressure() } catch (t: Throwable) { 0f }
        if (m > 1f) m else 4096f
    }
    private val guidePaint = Paint().apply {
        color = Color.rgb(200, 200, 200)
        strokeWidth = 1f
    }

    private val isTextMode get() = InkSession.mode == InkSession.MODE_TEXT
    private val isPdfMode get() = InkSession.mode == InkSession.MODE_PDF
    private val background: Bitmap? = InkSession.background

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ink)
        surface = findViewById(R.id.surface)
        btnPen = findViewById(R.id.btnPen)
        btnEraser = findViewById(R.id.btnEraser)
        btnWidth = findViewById(R.id.btnWidth)
        btnDone = findViewById(R.id.btnDone)

        InkSession.initial?.let { drawing.strokes.addAll(it.strokes) }
        if (isPdfMode) InkSession.initial = null
        if (isTextMode) btnDone.text = "✓ Convertir a texto"

        findViewById<Button>(R.id.btnCancel).setOnClickListener { confirmDiscard() }
        btnPen.setOnClickListener { paused { eraser = false } }
        btnEraser.setOnClickListener { paused { eraser = true } }
        btnWidth.setOnClickListener { paused { widthIndex = (widthIndex + 1) % widthsDp.size } }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { paused { undo() } }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            if (!drawing.isEmpty()) paused { pushUndo(); drawing.strokes.clear(); rebuildBitmap() }
        }
        btnDone.setOnClickListener { finishDrawing() }
        updateButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscard()
        })

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (width > 0 && height > 0) onSurfaceReady(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun onSurfaceReady(w: Int, h: Int) {
        if (inkBitmap == null || inkBitmap!!.width != w || inkBitmap!!.height != h) {
            inkBitmap?.recycle()
            inkBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            inkCanvas = Canvas(inkBitmap!!)
            // Si editamos un dibujo existente, lo colocamos arriba a la izquierda, en el primer renglón.
            // (En un PDF no: los trazos van en su posición exacta sobre la página.)
            if (!isPdfMode && InkSession.initial != null && !drawing.isEmpty()) {
                drawing.moveTo(24f * density, lineSpacingPx * 0.5f)
                InkSession.initial = null
            }
            rebuildBitmap()
        }
        renderToSurface()
        if (touchHelper == null) {
            try {
                val th = TouchHelper.create(surface, rawCallback)
                th.setStrokeWidth(strokeWidthPx)
                    .setLimitRect(Rect(0, 0, w, h), ArrayList<Rect>())
                    .openRawDrawing()
                touchHelper = th
                applyPenState()
                th.setRawDrawingEnabled(true)
            } catch (t: Throwable) {
                Log.e(App.TAG, "No se pudo iniciar el lápiz de Boox", t)
                AlertDialog.Builder(this)
                    .setMessage("No se pudo iniciar el lápiz de Boox: ${t.message}")
                    .setPositiveButton("Cerrar") { _, _ -> finish() }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        touchHelper?.let { it.setRawDrawingEnabled(true); applyPenState() }
    }

    override fun onPause() {
        touchHelper?.setRawDrawingEnabled(false)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            touchHelper?.closeRawDrawing()
        } catch (t: Throwable) {
            Log.w(App.TAG, "closeRawDrawing", t)
        }
        touchHelper = null
        inkBitmap?.recycle()
        inkBitmap = null
        super.onDestroy()
    }

    // ---- Lápiz -------------------------------------------------------------------------------

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {}
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {}
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {}

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            val pts = toInkPoints(list)
            runOnUiThread { if (eraser) eraseAlong(pts) else addStroke(pts) }
        }

        // Goma del propio lápiz (botón lateral o extremo trasero).
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {}
        override fun onEndRawErasing(b: Boolean, p: TouchPoint) {}
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}
        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            val pts = toInkPoints(list)
            runOnUiThread { eraseAlong(pts) }
        }
    }

    private fun toInkPoints(list: TouchPointList): List<InkPoint> {
        val out = ArrayList<InkPoint>()
        var lx = Float.NaN
        var ly = Float.NaN
        for (tp in list.points) {
            // Descarta puntos casi duplicados para aligerar el JSON y el SVG.
            if (!lx.isNaN() && Math.abs(tp.x - lx) < 0.8f && Math.abs(tp.y - ly) < 0.8f) continue
            // Algunos firmwares ya dan la presión de 0 a 1; otros en unidades del digitalizador.
            val pr = if (tp.pressure <= 1f) tp.pressure else tp.pressure / maxPressure
            out.add(InkPoint(tp.x, tp.y, pr.coerceIn(0f, 1f), tp.timestamp))
            lx = tp.x
            ly = tp.y
        }
        return out
    }

    private fun addStroke(points: List<InkPoint>) {
        if (points.isEmpty() || busy) return
        pushUndo()
        val s = Stroke(strokeWidthPx, points)
        drawing.strokes.add(s)
        drawStroke(s)
        // El trazo ya está en pantalla (lo pinta el SDK); actualizamos el buffer de la vista
        // para que siga ahí cuando se refresque la pantalla.
        renderToSurface()
    }

    private fun eraseAlong(points: List<InkPoint>) {
        if (points.isEmpty() || busy) return
        val r = eraserRadiusPx
        val toRemove = drawing.strokes.filter { s -> points.any { p -> s.isNear(p.x, p.y, r) } }
        if (toRemove.isEmpty()) {
            refreshScreen()
            return
        }
        pushUndo()
        drawing.strokes.removeAll(toRemove.toSet())
        rebuildBitmap()
        renderToSurface()
        refreshScreen()
    }

    private fun pushUndo() {
        undoStack.addLast(drawing.strokes.toList())
        while (undoStack.size > 50) undoStack.removeFirst()
    }

    private fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        drawing.strokes.clear()
        drawing.strokes.addAll(prev)
        rebuildBitmap()
    }

    // ---- Dibujo en pantalla -------------------------------------------------------------------

    private fun drawStroke(s: Stroke) {
        val c = inkCanvas ?: return
        c.drawPath(s.toPath(), inkPaint)
    }

    private fun rebuildBitmap() {
        val bmp = inkBitmap ?: return
        bmp.eraseColor(Color.TRANSPARENT)
        for (s in drawing.strokes) drawStroke(s)
    }

    private fun renderToSurface() {
        val holder = surface.holder ?: return
        val bmp = inkBitmap ?: return
        val c = try { holder.lockCanvas() } catch (t: Throwable) { null } ?: return
        try {
            c.drawColor(Color.WHITE)
            val bg = background
            if (bg != null && !bg.isRecycled) {
                c.drawBitmap(bg, 0f, 0f, null)
            } else {
                var y = lineSpacingPx
                while (y < c.height) {
                    c.drawLine(0f, y, c.width.toFloat(), y, guidePaint)
                    y += lineSpacingPx
                }
            }
            c.drawBitmap(bmp, 0f, 0f, null)
        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    /** Fuerza el refresco de la pantalla e-ink (mientras el lápiz está activo, la pantalla no se refresca). */
    private fun refreshScreen() {
        val th = touchHelper ?: return
        th.setRawDrawingEnabled(false)
        th.setRawDrawingEnabled(true)
        applyPenState()
    }

    private fun applyPenState() {
        val th = touchHelper ?: return
        th.setStrokeWidth(strokeWidthPx)
        // Estilo "pluma estilográfica": el SDK varía el grosor con la presión mientras escribes.
        th.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
        // En modo goma no queremos que el SDK pinte tinta.
        th.setRawDrawingRenderEnabled(!eraser)
    }

    /** Pausa el lápiz para que la interfaz pueda refrescarse, ejecuta [block] y lo reactiva. */
    private fun paused(block: () -> Unit) {
        touchHelper?.setRawDrawingEnabled(false)
        block()
        updateButtons()
        renderToSurface()
        surface.post {
            if (!busy && !isFinishing) {
                touchHelper?.setRawDrawingEnabled(true)
                applyPenState()
            }
        }
    }

    private fun updateButtons() {
        btnPen.text = if (eraser) "Pluma" else "● Pluma"
        btnEraser.text = if (eraser) "● Goma" else "Goma"
        btnWidth.text = "Grosor: ${widthNames[widthIndex]}"
    }

    // ---- Terminar -----------------------------------------------------------------------------

    private fun confirmDiscard() {
        if (drawing.isEmpty() || busy) {
            if (!busy) cancel()
            return
        }
        touchHelper?.setRawDrawingEnabled(false)
        AlertDialog.Builder(this)
            .setMessage("¿Descartar lo escrito?")
            .setPositiveButton("Descartar") { _, _ -> cancel() }
            .setNegativeButton("Seguir escribiendo", null)
            .setOnDismissListener { if (!isFinishing) resumePen() }
            .show()
    }

    private fun cancel() {
        InkSession.result = null
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun resumePen() {
        renderToSurface()
        touchHelper?.setRawDrawingEnabled(true)
        applyPenState()
    }

    private fun setBusy(b: Boolean, label: String? = null) {
        busy = b
        if (b) touchHelper?.setRawDrawingEnabled(false)
        btnDone.isEnabled = !b
        btnDone.text = label ?: if (isTextMode) "✓ Convertir a texto" else "✓ Guardar"
    }

    private fun finishDrawing() {
        if (busy) return
        if (isPdfMode) {
            // En un PDF también se guarda vacío: así se pueden borrar anotaciones existentes.
            InkSession.result = InkResult("", "", "", Drawing().also { it.strokes.addAll(drawing.strokes) })
            setResult(RESULT_OK)
            finish()
            return
        }
        if (drawing.isEmpty()) {
            cancel()
            return
        }
        setBusy(true, if (isTextMode) "Reconociendo…" else "Guardando…")
        lifecycleScope.launch {
            val text = withTimeoutOrNull(if (isTextMode) 15_000L else 5_000L) {
                Recognizer.recognize(drawing, lineSpacingPx)
            }
            if (isTextMode) {
                showTextResult(text)
            } else {
                val bmp = inkBitmap
                InkSession.result = InkResult(
                    svg = drawing.toSvg(density, 6f * density),
                    json = drawing.toJson(bmp?.width ?: 0, bmp?.height ?: 0, density),
                    text = text ?: ""
                )
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun showTextResult(text: String?) {
        if (text == null) {
            AlertDialog.Builder(this)
                .setTitle("Reconocimiento no disponible")
                .setMessage("Estado del modelo: ${Recognizer.status}.\n\nLa primera vez hace falta conexión a internet para descargar el modelo de español.")
                .setPositiveButton("Volver") { _, _ -> }
                .setOnDismissListener { setBusy(false); resumePen() }
                .show()
            return
        }
        val pad = (16 * density).toInt()
        val edit = EditText(this).apply {
            setText(text)
            setSelection(text.length)
            minLines = 2
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        var inserted = false
        AlertDialog.Builder(this)
            .setTitle("Texto reconocido (puedes corregirlo)")
            .setView(box)
            .setPositiveButton("Insertar") { _, _ ->
                inserted = true
                InkSession.result = InkResult("", "", edit.text.toString())
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("Seguir escribiendo", null)
            .setNeutralButton("Borrar y repetir") { _, _ ->
                pushUndo()
                drawing.strokes.clear()
                rebuildBitmap()
            }
            .setOnDismissListener {
                if (!inserted) {
                    setBusy(false)
                    resumePen()
                }
            }
            .show()
    }
}
