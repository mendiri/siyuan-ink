package com.siyuanink

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones desde la release "latest" de GitHub.
 * El título de la release lleva "build N"; si N es mayor que la versión instalada, hay actualización.
 */
object Updater {
    private const val REPO = "mendiri/siyuan-ink"
    private const val API = "https://api.github.com/repos/$REPO"

    data class Release(val build: Long, val apkAssetId: Long, val title: String)

    fun installedBuild(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun open(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SiYuanInk-updater")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    suspend fun fetchLatest(token: String): Release = withContext(Dispatchers.IO) {
        val c = open("$API/releases/tags/latest", token, "application/vnd.github+json")
        try {
            when (val code = c.responseCode) {
                200 -> Unit
                401 -> throw IOException("el token de GitHub no es válido o ha caducado")
                403 -> throw IOException("GitHub ha denegado el acceso (token sin permiso o límite de consultas)")
                404 -> throw IOException(
                    if (token.isBlank()) "el repositorio es privado: añade el token de GitHub en Ajustes"
                    else "no se encuentra la release (¿el token tiene acceso a $REPO?)"
                )
                else -> throw IOException("GitHub respondió HTTP $code")
            }
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val title = o.optString("name")
            val build = Regex("build (\\d+)").find(title)?.groupValues?.get(1)?.toLong()
                ?: throw IOException("la release no indica el número de build")
            val assets = o.getJSONArray("assets")
            var id = -1L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) id = a.getLong("id")
            }
            if (id < 0) throw IOException("la release no contiene el APK")
            Release(build, id, title)
        } finally {
            c.disconnect()
        }
    }

    /** Descarga el APK. El token solo se envía a GitHub, no al servidor de descarga al que redirige. */
    suspend fun download(context: Context, token: String, rel: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            var c = open("$API/releases/assets/${rel.apkAssetId}", token, "application/octet-stream")
            var code = c.responseCode
            var hops = 0
            while (code in 300..399 && hops < 5) {
                val loc = c.getHeaderField("Location") ?: break
                c.disconnect()
                c = open(loc, null, "application/octet-stream")
                code = c.responseCode
                hops++
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("error al descargar (HTTP $code)")
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "SiYuanInk.apk")
            val total = c.contentLengthLong
            try {
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 5 == 0) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            out
        }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
