plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.bayx.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tayfa.chat"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "0.30.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // CI'da kalıcı imzalama anahtarı verilmişse onu kullan; böylece güncellemeler eski sürümün
    // üzerine kurulabilir. Verilmemişse varsayılan debug anahtarıyla imzalanır.
    val keystoreFile = System.getenv("BAYX_KEYSTORE_FILE")
    val keystorePass = System.getenv("BAYX_KEYSTORE_PASS")
    val hasKeystore = !keystoreFile.isNullOrEmpty() && !keystorePass.isNullOrEmpty()
    signingConfigs {
        if (hasKeystore) {
            create("bayx") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePass
                keyAlias = "bayx"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("bayx")
        }
        release {
            isMinifyEnabled = false
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
    // Ekran görüntüsü testleri (Robolectric + Roborazzi): tasarımı telefon olmadan doğrulamak için.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Matrix Rust SDK: denetlenmiş uçtan uca şifreleme (vodozemac) dahil.
    implementation("org.matrix.rustcomponents:sdk-android:26.11.11-nightly")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    // Bildirimler (yalnızca uyandırma; mesaj içeriği Google'dan geçmez)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Sesli/görüntülü arama
    implementation("io.getstream:stream-webrtc-android:1.3.9")
    // Uygulama kilidi (parmak izi / ekran kilidi) ve giriş QR kodu
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.google.zxing:core:3.5.3")

    // Web bağlama için QR tarayıcı (CameraX)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
