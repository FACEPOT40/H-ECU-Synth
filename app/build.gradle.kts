plugins {
    id("com.android.application")
}

android {
    namespace = "com.pai.hecusynth"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pai.hecusynth"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    // the sound engine is shared with the desktop app: core/ (plain Kotlin, no Android)
    sourceSets {
        getByName("main") { kotlin.srcDir("../core/src/main/kotlin") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            // demo WAV files (one rev run per engine style) go here so they can be heard on the PC
            it.systemProperty("wav.dir", layout.buildDirectory.dir("demo-wav").get().asFile.path)
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
