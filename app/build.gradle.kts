plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.metronomo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sincrono.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("SINCRONO_VERSION_CODE") as String?)?.toIntOrNull() ?: 1
        versionName = "0.5.0-grupos"
    }

    // Assinatura do release/AAB: o build_apk.py cria a chave de upload em ~/.sincrono-keystore
    // e passa os dados por propriedades (-PSINCRONO_KEYSTORE=...). Sem elas, o release fica sem assinatura.
    val ksPath = (project.findProperty("SINCRONO_KEYSTORE") as String?)
    signingConfigs {
        if (ksPath != null) {
            create("upload") {
                storeFile = file(ksPath)
                storePassword = project.findProperty("SINCRONO_KS_PASSWORD") as String?
                keyAlias = project.findProperty("SINCRONO_KEY_ALIAS") as String? ?: "upload"
                keyPassword = project.findProperty("SINCRONO_KS_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // QR code: geração (zxing, só o núcleo) e leitura pelo leitor do Google Play Services (sem permissão de câmera).
    implementation("com.google.zxing:core:3.5.3")
    // Banner de anúncios (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
