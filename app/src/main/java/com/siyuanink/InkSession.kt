package com.siyuanink

/**
 * Datos compartidos entre la pantalla de SiYuan y el lienzo.
 * Se usa un objeto en memoria en vez de extras del Intent porque los trazos
 * pueden superar el límite de tamaño de un Intent.
 */
object InkSession {
    const val MODE_INK = "ink"
    const val MODE_TEXT = "text"

    var mode: String = MODE_INK
    /** Bloque de SiYuan junto al que se inserta (o que se edita). Vacío = final del documento. */
    var blockId: String = ""
    /** true si se está editando un dibujo existente (se reemplaza el bloque). */
    var editing: Boolean = false
    var initial: Drawing? = null
    var result: InkResult? = null

    fun reset(mode: String) {
        this.mode = mode
        blockId = ""
        editing = false
        initial = null
        result = null
    }
}

data class InkResult(val svg: String, val json: String, val text: String)
