// Запускаемый сервис: Kotlin-модуль + плагин application.
plugins {
    id("theseus.kotlin-jvm")
    application
}

// Версия входит в каждый дистрибутив; окружение запущенного сервиса её не переопределяет.
version = providers.gradleProperty("theseusVersion").get()
require(version.toString().matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
    "theseusVersion должна соответствовать SemVer MAJOR.MINOR.PATCH"
}
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")
val writeBuildInfo = tasks.register<WriteProperties>("writeBuildInfo") {
    destinationFile = buildInfoDir.map { it.file("build-info.properties") }
    property("version", project.version.toString())
}
sourceSets.main { resources.srcDir(buildInfoDir) }
tasks.processResources { dependsOn(writeBuildInfo) }
