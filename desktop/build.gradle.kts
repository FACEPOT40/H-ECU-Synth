// H-ECU Synth desktop: the phone's sound engine (core/) on the PC, with a tuning panel.
// Run with run_desktop.bat (or: gradlew :desktop:run).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    sourceSets["main"].kotlin.srcDir("../core/src/main/kotlin")
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.pai.hecusynth.desktop.MainKt")
    applicationName = "H-ECU-Synth"
}

version = "1.0.0"     // keep in step with Main.kt VERSION and the phone's versionName
