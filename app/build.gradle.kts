import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing: the same key as H-ECU Logger (same developer). Its settings file stays where
// it is (H-ECU-Logger/android/keystore.properties, gitignored there) and is never copied, printed
// or committed. A keystore.properties next to this project wins if there is one. Without either,
// debug builds are unchanged and a release build comes out unsigned.
val keystoreFile = listOf(rootProject.file("keystore.properties"), rootProject.file("../Honda-ECU-Logger/android/keystore.properties"))
    .firstOrNull { it.exists() }
val keystoreProps = Properties().apply { keystoreFile?.inputStream()?.use { load(it) } }

android {
    namespace = "com.pai.hecusynth"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pai.hecusynth"
        minSdk = 29
        targetSdk = 36
        versionCode = 4                 // raise on every phone release
        versionName = "1.0.2"
    }
    signingConfigs {
        if (!keystoreProps.isEmpty) create("release") {
            // a relative storeFile is relative to that properties file's app module, as in the logger
            val f = File(keystoreProps.getProperty("storeFile"))
            storeFile = if (f.isAbsolute) f else File(keystoreFile!!.parentFile, "app/" + keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword") ?: keystoreProps.getProperty("storePassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false     // small app; keeps usb-serial-for-android free of shrinker surprises
            signingConfig = signingConfigs.findByName("release")
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
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    testImplementation("junit:junit:4.13.2")
}
