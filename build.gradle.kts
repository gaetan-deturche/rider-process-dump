import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

val pluginGroup = project.property("pluginGroup") as String
val pluginVersion = project.property("pluginVersion") as String
val platformVersion = project.property("platformVersion") as String

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        rider(platformVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
        // Optional at runtime (plugin.xml <depends optional>): hosts the MCP toolset.
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.modules.jna")
        // XDebugger API: a product module since 263, no longer on the default compile classpath.
        bundledModule("intellij.platform.debugger")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        name = project.property("pluginName") as String
        ideaVersion {
            sinceBuild = project.property("pluginSinceBuild") as String
            untilBuild = project.property("pluginUntilBuild") as String
        }
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    // The IDE ships jnidispatch outside its jars; point JNA at it like the IDE launcher does.
    doFirst {
        systemProperty("jna.boot.library.path", intellijPlatform.platformPath.resolve("plugins/jna-plugin/lib/jna/amd64").toString())
        systemProperty("jna.nosys", "true")
    }
}
