plugins {
    id("theseus.application")
    id("theseus.serialization")
}

application {
    mainClass = "theseus.core.MainKt"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.mcp.sdk.server)
    implementation(libs.tgbotapi)
    runtimeOnly(libs.logback)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mcp.sdk.client)
}

// Интеграционные тесты на реальном PostgreSQL (Testcontainers): миграции и репозитории.
// Входят в `check`; без Docker пропускаются (`disabledWithoutDocker`), в CI Docker есть.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.kotlinx.coroutines.test)
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.postgresql)
                implementation(libs.testcontainers.junit)
                implementation(libs.exposed.core)
                implementation(libs.exposed.jdbc)
                implementation(libs.ktor.server.test.host)
                implementation(libs.kotlinx.serialization.json)
                runtimeOnly(libs.logback)
            }
            targets.all {
                testTask.configure { shouldRunAfter(tasks.test) }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}
