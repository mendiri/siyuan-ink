plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.siyuanink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.siyuanink"
        minSdk = 26
        // El SDK de Onyx usa APIs ocultas de Android; con targetSdk 30 funciona como en sus demos.
        targetSdk = 30
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            // Clave fija para que cada APK compilado en GitHub se pueda instalar encima del anterior.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
                "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
                "META-INF/notice.txt", "META-INF/ASL2.0", "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Reconocimiento de escritura en el dispositivo (sin nube).
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    // SDK del lápiz de Boox: escritura nativa de baja latencia.
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
