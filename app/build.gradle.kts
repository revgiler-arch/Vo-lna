plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.volna.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.volna.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "1.16.21"
    }

    // Один и тот же ключ для всех сборок: новая версия ставится поверх старой
    // и история с лайками не теряется. В репозитории ключ лежит только зашифрованным
    // (volna.keystore.enc); GitHub Actions расшифровывает его паролем из секрета SIGNING_KEY_PASS.
    val keystore = file("volna.keystore")
    signingConfigs {
        if (keystore.exists()) {
            create("volna") {
                storeFile = keystore
                storePassword = "volna123"
                keyAlias = "volna"
                keyPassword = "volna123"
            }
        }
    }

    buildTypes {
        val volna = signingConfigs.findByName("volna")
        debug {
            if (volna != null) signingConfig = volna
        }
        release {
            isMinifyEnabled = false
            signingConfig = volna ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // Извлечение потоков YouTube / YouTube Music (та же версия, что у NewPipe)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    // _nio — расширенный набор: нужен для URLEncoder.encode(String, Charset) на Android 8
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation("junit:junit:4.13.2")
}
