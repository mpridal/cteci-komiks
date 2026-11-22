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
    // Vosk přes JNA vrací texty v kódování systému (na Windows cp1252) → poškozená diakritika
    applicationDefaultJvmArgs = listOf("-Djna.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

tasks.withType<Test>().configureEach { jvmArgs("-Djna.encoding=UTF-8", "-Dfile.encoding=UTF-8") }

// Přehrání záznamu relace: gradlew :asr-bench:replay --args="<záznam.json|složka> [snrDb] [stableMs]"
tasks.register<JavaExec>("replay") {
    group = "application"
    description = "Přehraje záznam relace Čtečky přes filtr a sledování"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.mpridal.ctecikomiks.bench.ReplayMain")
    jvmArgs("-Djna.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":reader-core"))
    implementation(libs.vosk.desktop)
    implementation(libs.jna)
    implementation(libs.orgjson)
}

tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.mpridal.ctecikomiks.bench.ProbeMain")
    jvmArgs("-Djna.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
