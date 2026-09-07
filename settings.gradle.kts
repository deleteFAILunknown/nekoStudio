@file:Suppress("UnstableApiUsage")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    maven("https://maven.scijava.org/content/repositories/public/")
    maven("https://plugins.gradle.org/m2/")
    maven("https://redirector.kotlinlang.org/maven/bootstrap")
  }
}

rootProject.name = "nekoStudio"

include(":app")