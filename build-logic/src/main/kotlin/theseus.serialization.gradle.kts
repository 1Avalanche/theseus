// Модуль с kotlinx.serialization: плагин компилятора и JSON-runtime.
plugins {
    id("theseus.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
}
