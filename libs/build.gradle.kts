@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinx.serialization)
}

val libsCompileSdk = libs.versions.android.compileSdk.get().toInt()
val libsMinSdk = libs.versions.android.minSdk.get().toInt()
val libsBuildTools = libs.versions.android.buildTools.version.get()

android {
    namespace = "libs.libs.libs"
    compileSdk = libsCompileSdk
    buildToolsVersion = "$libsBuildTools"

    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    defaultConfig {
        minSdk = libsMinSdk

        vectorDrawables { 
            useSupportLibrary = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        aidl = true
        prefab = true
    }

    lint {
        checkDependencies = false
      //  abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.jdk.libs)
    runtimeOnly(libs.bundles.kotlinx.android)
    implementation(libs.lsposed.hiddenapibypass)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.annotation.experimental)
}
