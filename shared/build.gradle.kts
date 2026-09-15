import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.kmp)
    alias(libs.plugins.android.kmp.library)
  //  alias(libs.plugins.kotlin.cmp)
  //  alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    android {
        namespace = "com.libs.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_25
        }
    }

    sourceSets {
        androidMain.dependencies {
            runtimeOnly(libs.bundles.kotlinx.android)
            implementation(libs.androidx.annotation)
            implementation(libs.androidx.annotation.experimental)
            implementation(libs.lsposed.hiddenapibypass)
        }
        commonMain.dependencies {
            runtimeOnly(libs.bundles.kotlinx.kmp)
        }
    }
}

// androidRuntimeClasspath
// androidCompilationClasspath
// androidApiElements
