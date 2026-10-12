pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform 1.12.x зависит от AndroidX-артефактов, которые живут в Google Maven
        google()
    }
}

rootProject.name = "book-translator"

include(":core")
include(":desktop")
