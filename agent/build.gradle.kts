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
    runtimeOnly(libs.logback)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
