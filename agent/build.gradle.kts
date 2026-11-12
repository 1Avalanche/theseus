plugins {
    id("theseus.application")
    id("theseus.serialization")
}

application {
    mainClass = "theseus.agent.MainKt"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.mcp.sdk.client)
    implementation(libs.tgbotapi)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.logback)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}

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
                implementation(libs.hikari)
                implementation(libs.kotlinx.serialization.json)
                runtimeOnly(libs.logback)
            }
            targets.all { testTask.configure { shouldRunAfter(tasks.test) } }
        }
    }
}
tasks.check { dependsOn(testing.suites.named("integrationTest")) }
