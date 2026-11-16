pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("org.jetbrains.intellij.platform") version "2.16.0"
    }
}

rootProject.name = "process-dump"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}
