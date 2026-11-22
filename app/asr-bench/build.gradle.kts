import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Testovací lavice prototypu A: Vosk na PC nad nahrávkami (čtení, šum, cizí hovor) → ReadingTracker.
// Spuštění: gradlew :asr-bench:run --args="<korpus> <model> [výstup.json]"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("io.github.mpridal.ctecikomiks.bench.BenchKt")
}

dependencies {
    implementation(project(":reader-core"))
    implementation(libs.vosk.desktop)
    implementation(libs.jna)
    implementation(libs.orgjson)
}
