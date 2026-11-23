plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.lensProtocol)
    implementation(libs.clikt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kermit)
    // OkHttp/Ktor log through SLF4J; silence its "no provider" warning.
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass = "io.github.bropines.lens.cli.MainKt"
    applicationName = "lens"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}
