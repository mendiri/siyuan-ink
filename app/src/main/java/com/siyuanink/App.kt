package com.siyuanink

import android.app.Application
import android.os.Build
import android.util.Log
import com.onyx.android.sdk.rx.RxBaseAction
import com.onyx.android.sdk.utils.ResManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inicialización que exige el SDK de Onyx (igual que en sus demos oficiales).
        try {
            ResManager.init(this)
            RxBaseAction.init(this)
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo inicializar el SDK de Onyx (¿no es un Boox?)", t)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                Log.w(TAG, "HiddenApiBypass falló", t)
            }
        }
        Recognizer.prepare(this)
    }

    companion object {
        const val TAG = "SiYuanInk"
    }
}
