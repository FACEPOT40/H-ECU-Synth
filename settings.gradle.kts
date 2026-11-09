pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // usb-serial-for-android (the FTDI cable), same as H-ECU Logger
    }
}
rootProject.name = "HECUSynth"
include(":app")
include(":desktop")    // PC app for trying and tuning the sounds
