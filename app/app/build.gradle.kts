plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mpridal.ctecikomiks"
    compileSdk = 37 // knihovny Compose vyžadují kompilaci proti API 37; targetSdk zůstává 36

    defaultConfig {
        applicationId = "io.github.mpridal.ctecikomiks"
        minSdk = 26
        targetSdk = 36
        // Vydání dostane verzi z tagu: -PversionName=0.1.0 (GitHub Actions). Vývojový build se neaktualizuje.
        val vName = (project.findProperty("versionName") as String?) ?: "0.0.0-dev"
        val parts = vName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        versionName = vName
        versionCode = maxOf(1, parts[0] * 10000 + parts[1] * 100 + parts[2])
        buildConfigField("String", "UPDATE_REPO", "\"mpridal/cteci-komiks\"")
        buildConfigField("String", "LOG_REPO", "\"mpridal/cteci-komiks-logs\"")
    }

    signingConfigs {
        // Klíč z proměnných prostředí (GitHub Secrets / lokálně) – nikdy v repozitáři
        val ks = System.getenv("CTECI_KEYSTORE")
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("CTECI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CTECI_KEY_ALIAS")
                keyPassword = System.getenv("CTECI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            // Tablet JENBIZZ je arm64; jen jedna sada nativních knihoven Vosku = menší APK
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Aplikace je jen česky, žádné jazykové mutace (ani z knihoven)
        localeFilters += listOf("cs")
        // Model Vosk se čte ze složky assets – nekomprimovat (rychlejší rozbalení)
        noCompress += listOf("mdl", "fst", "int", "conf", "dubm", "ie", "mat", "stats")
    }
}

dependencies {
    implementation(project(":reader-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.vosk.android)
    implementation("${libs.jna.get()}@aar")

    testImplementation(libs.junit)
}
