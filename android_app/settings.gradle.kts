pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        // WebRTC repos
        maven("https://maven.webrtc.org")
        maven { url = uri("https://webrtc.github.io/webrtc-maven-repo") }
        maven("https://jitpack.io")
    }
}

rootProject.name = "ManyEyes"
include(":app")
