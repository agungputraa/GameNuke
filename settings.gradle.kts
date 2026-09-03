pluginManagement {
    plugins {
        id("com.android.application") version "8.13.2"
        id("org.jetbrains.kotlin.android") version "2.3.0"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    }
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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Game Tweak"
include(":app")
project(":app").projectDir = file("app")
