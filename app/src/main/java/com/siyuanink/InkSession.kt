package com.siyuanink

import android.graphics.Bitmap

/**
 * Datos compartidos entre la pantalla de SiYuan y el lienzo.
 * Se usa un objeto en memoria en vez de extras del Intent porque los trazos
 * (y la captura del PDF) pueden superar el límite de tamaño de un Intent.
 */
object InkSession {
    const val MODE_INK = "ink"
    const val MODE_TEXT = "text"
    /** Anotar directamente encima de las páginas de un PDF abierto en SiYuan. */
    const val MODE_PDF = "pdf"

    var mode: String = MODE_INK
    /** Bloque de SiYuan junto al que se inserta (o que se edita). Vacío = final del documento. */
    var blockId: String = ""
    /** true si se está editando un dibujo existente (se reemplaza el bloque). */
    var editing: Boolean = false
    var initial: Drawing? = null
    var result: InkResult? = null

    /** Captura de la pantalla de SiYuan que se muestra de fondo al anotar un PDF. */
    var background: Bitmap? = null
    var pdfKey: String = ""
    /** Páginas visibles del PDF, en píxeles CSS relativos a la vista web. */
    var pdfPages: List<PdfPage> = emptyList()

    fun reset(mode: String) {
        this.mode = mode
        blockId = ""
        editing = false
        initial = null
        result = null
        background = null
        pdfKey = ""
        pdfPages = emptyList()
    }
}

data class InkResult(val svg: String, val json: String, val text: String, val drawing: Drawing? = null)

data class PdfPage(val n: String, val left: Float, val top: Float, val width: Float, val height: Float)
