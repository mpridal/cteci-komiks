plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Repozitář leží v OneDrive, který zamyká soubory v build/ – buildy proto jdou mimo OneDrive.
val buildRoot = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "cteci-komiks-build")
allprojects {
    layout.buildDirectory.set(File(buildRoot, if (path == ":") "root" else path.removePrefix(":").replace(':', '-')))
}
