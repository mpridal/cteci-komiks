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
        versionCode = 1
        versionName = "0.1.0-protoA"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
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
