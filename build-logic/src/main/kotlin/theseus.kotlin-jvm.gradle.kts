// Общие настройки любого Kotlin-модуля: JVM 21, JUnit, ktlint, detekt.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val libs = versionCatalogs.named("libs")

group = "theseus"

kotlin {
    jvmToolchain(libs.findVersion("jvm").get().requiredVersion.toInt())
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("mockk").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.test {
    useJUnitPlatform()
}
