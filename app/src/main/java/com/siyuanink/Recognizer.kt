package com.siyuanink

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.tasks.await

/** Reconocimiento de escritura a mano en español con ML Kit (funciona sin conexión una vez descargado el modelo). */
object Recognizer {
    @Volatile private var recognizer: DigitalInkRecognizer? = null
    @Volatile var status: String = "sin iniciar"
        private set

    fun prepare(context: Context) {
        if (recognizer != null) return
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("es-ES")
                ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag("es")
        } catch (e: MlKitException) {
            null
        }
        if (id == null) {
            status = "español no disponible"
            return
        }
        val model = DigitalInkRecognitionModel.builder(id).build()
        status = "descargando modelo de español…"
        RemoteModelManager.getInstance()
            .download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
                status = "listo (español)"
            }
            .addOnFailureListener {
                Log.w(App.TAG, "Fallo al descargar el modelo", it)
                status = "error al descargar el modelo: ${it.message}"
            }
    }

    val isReady: Boolean get() = recognizer != null

    /** Devuelve el texto reconocido, un renglón por línea, o null si el reconocedor no está listo. */
    suspend fun recognize(drawing: Drawing, lineSpacing: Float): String? {
        val r = recognizer ?: return null
        val out = mutableListOf<String>()
        var pre = ""
        for (line in drawing.lines(lineSpacing)) {
            val inkBuilder = Ink.builder()
            val area = RectF(line[0].bounds)
            for (s in line) {
                area.union(s.bounds)
                val sb = Ink.Stroke.builder()
                for (p in s.points) {
                    sb.addPoint(if (p.t > 0) Ink.Point.create(p.x, p.y, p.t) else Ink.Point.create(p.x, p.y))
                }
                inkBuilder.addStroke(sb.build())
            }
            val ctx = RecognitionContext.builder()
                .setPreContext(pre.takeLast(20))
                .setWritingArea(WritingArea(area.width(), area.height()))
                .build()
            val text = try {
                r.recognize(inkBuilder.build(), ctx).await().candidates.firstOrNull()?.text
            } catch (e: Exception) {
                Log.w(App.TAG, "Fallo de reconocimiento", e)
                null
            }
            if (!text.isNullOrBlank()) {
                out.add(text)
                pre = text
            }
        }
        return out.joinToString("\n")
    }
}
