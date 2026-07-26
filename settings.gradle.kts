import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    // Pyzh start - project setup
    val errorText = """
        
        =====================[ ERROR ]=====================
         The Pyzh project directory is not a properly cloned Git repository.
         
         In order to build Pyzh from source you must clone
         the Pyzh repository using Git, not download a code
         zip from GitHub.
         
         See https://github.com/PaperMC/Paper/blob/main/CONTRIBUTING.md
         for further information on building and modifying Paper forks.
        ===================================================
    """.trimIndent()
    // Pyzh end - project setup
    error(errorText)
}

rootProject.name = "pyzh"

for (name in listOf("api", "server")) {
    val projName = "pyzh-$name".lowercase(Locale.ENGLISH)
    include(projName)
    project(":$projName").projectDir = file("pyzh-$name")
}

gradle.lifecycle.beforeProject {
    val mcVersion = providers.gradleProperty("mcVersion").get().trim()
    val paperVersionChannel = providers.gradleProperty("channel").get().trim()
    val paperBuildNumber = providers.environmentVariable("BUILD_NUMBER").orNull?.trim()?.toInt()
    val versionString = if (paperBuildNumber == null) {
        "$mcVersion.local-SNAPSHOT"
    } else {
        "$mcVersion.build.$paperBuildNumber-${paperVersionChannel.lowercase()}"
    }
    version = versionString
}
