@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.util.GradleVersion
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val propCompileSdk = providers.gradleProperty("COMPILE_SDK").get().toInt()
val propMinSdk = providers.gradleProperty("MIN_SDK").get().toInt()
val propTargetSdk = providers.gradleProperty("TARGET_SDK").get().toInt()
val propVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()

val buildDate = SimpleDateFormat("yyyyMMdd").format(Date())
val versionPrefix = providers.gradleProperty("VERSION_PREFIX").get()
val propNdk = providers.gradleProperty("NDK_VERSION").get()
val propCmake = providers.gradleProperty("CMAKE_VERSION").get()
val propBuildTools = providers.gradleProperty("BUILDTOOLS_VERSION").get()

val envNewStorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: ""
val envNewKeyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: ""
val envNewKeyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: ""

android {
    namespace = "com.adb.kitty"
    compileSdk = propCompileSdk
    buildToolsVersion = "$propBuildTools"
    ndkVersion = "$propNdk"

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

    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.adb.kitty"
        minSdk = propMinSdk
        targetSdk = propTargetSdk
        versionCode = propVersionCode
        versionName = "$versionPrefix-$buildDate"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { 
            useSupportLibrary = true
        }
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64"))
        }
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "riscv64")
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    kotlin {
        compilerOptions {
            languageVersion = KotlinVersion.KOTLIN_2_4
            apiVersion = KotlinVersion.KOTLIN_2_4
            jvmTarget = JvmTarget.JVM_25
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "$propCmake"
        }
    }

    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    signingConfigs {
        create("adb") {
        // keystore file，.bks & .jks & .p12
            storeFile = file("bash/new_key.jks")
            storePassword = envNewStorePassword
            keyAlias = envNewKeyAlias
            keyPassword = envNewKeyPassword
            storeType = "PKCS12"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), 
                "proguard-rules.pro"
            )
            optimization.keepRules {
                // ignoreFrom 只允许忽略来自远程库的依赖
                ignoreFrom("org.jetbrains.kotlinx:kotlinx-coroutines-android")
                ignoreFrom("org.lsposed.hiddenapibypass:hiddenapibypass")
                ignoreFrom("com.github.topjohnwu.libsu:core")
            }
            signingConfig = signingConfigs.getByName("adb")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("adb")
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
        prefab = true
    }

    lint {
        checkDependencies = false
      //  abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.jdk.libs)
    runtimeOnly(libs.bundles.coroutines.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.window)
    implementation(libs.lsposed.hiddenapibypass)
    implementation(libs.nayuki.qrcode)
    implementation(libs.zxing.core)
    implementation(libs.com.flyfishxu.kadb)
    implementation(libs.androidx.annotation.experimental)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.compose.lifecycle)
    debugImplementation(libs.bundles.compose.debug)
    implementation(libs.bundles.libsu)
}

abstract class GenerateKotlinMetadataTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Input abstract val agpVersion: Property<String>
    @get:Input abstract val kotlinVersion: Property<String>
    @get:Input abstract val kotlinxCoroutinesVersion: Property<String>
    @get:Input abstract val composeBomVersion: Property<String>
    @get:Input abstract val kadbVersion: Property<String>
    @get:Input abstract val hiddenapibypassVersion: Property<String>
    @get:Input abstract val lifecycleVersion: Property<String>
    @get:Input abstract val nayukiQRVersion: Property<String>
    @get:Input abstract val zxingCodeVersion: Property<String>
    @get:Input abstract val androidxMaterial3Version: Property<String>
    @get:Input abstract val libsuVersion: Property<String>

    @get:Input abstract val sourceCompatibility: Property<String>
    @get:Input abstract val targetCompatibility: Property<String>
    @get:Input abstract val kotlinLanguageVersion: Property<String>
    @get:Input abstract val kotlinApiVersion: Property<String>
    @get:Input abstract val kotlinJvmTarget: Property<String>

    @TaskAction
    fun run() {
        val currentGradleVersion = GradleVersion.current().version

        val jsonContent = """
        {
          "buildSystem": "Gradle",
          "buildSystemVersion": "$currentGradleVersion", 
          "buildPlugin": "org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper",
          "buildPluginVersion": "${kotlinVersion.get()}",
          "projectSettings": {
            "androidGradlePluginVersion": "${agpVersion.get()}",
            "kotlinxCoroutinesVersion": "${kotlinxCoroutinesVersion.get()}",
            "androidxLifecycleVersion": "${lifecycleVersion.get()}",
            "androidxMaterial3Version": "${androidxMaterial3Version.get()}",
            "composeBomVersion": "${composeBomVersion.get()}",
            "composeCompilerVersion": "${kotlinVersion.get()}",
            "kotlinLanguageVersion": "${kotlinLanguageVersion.get()}",
            "kotlinApiVersion": "${kotlinApiVersion.get()}",
            "kotlinJvmTarget": "${kotlinJvmTarget.get()}",
            "kadbVersion": "${kadbVersion.get()}",
            "org.lsposed.hiddenapibypass:hiddenapibypassVersion": "${hiddenapibypassVersion.get()}",
            "com.github.topjohnwu.libsu:libsuVersion": "${libsuVersion.get()}",
            "nayukiQRVersion": "${nayukiQRVersion.get()}",
            "zxingCodeVersion": "${zxingCodeVersion.get()}"
          },
          "projectTargets": [
            {
              "target": "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget",
              "platformType": "androidJvm",
              "extras": {
                "android": {
                  "sourceCompatibility": "${sourceCompatibility.get()}",
                  "targetCompatibility": "${targetCompatibility.get()}"
                }
              }
            }
          ]
        }
        """.trimIndent()

        val targetFile = outputDir.file("kotlin-tooling-metadata.json").get().asFile
        targetFile.parentFile.mkdirs()
        targetFile.writeText(jsonContent)
    }
}

val injectKotlinMetadataToRoot = tasks.register<GenerateKotlinMetadataTask>("injectKotlinMetadataToRoot") {
    outputDir.set(layout.buildDirectory.dir("generated/kotlin-metadata-root"))

    agpVersion.set(providers.provider { libs.versions.agp.get() })
    kotlinVersion.set(providers.provider { libs.versions.kotlin.get() })
    kotlinxCoroutinesVersion.set(providers.provider { libs.versions.kotlinxCoroutines.get() })
    composeBomVersion.set(providers.provider { libs.versions.compose.bom.get() })
    kadbVersion.set(providers.provider { libs.versions.kadb.get() })
    hiddenapibypassVersion.set(providers.provider { libs.versions.hiddenapibypassVersion.get() })
    lifecycleVersion.set(providers.provider { libs.versions.lifecycle.get() })
    nayukiQRVersion.set(providers.provider { libs.versions.nayukiQR.get() })
    zxingCodeVersion.set(providers.provider { libs.versions.zxing.get() })
    androidxMaterial3Version.set(providers.provider { libs.versions.material3.get() })
    libsuVersion.set(providers.provider { libs.versions.libsuVersion.get() })

    sourceCompatibility.set(providers.provider { android.compileOptions.sourceCompatibility.toString() })
    targetCompatibility.set(providers.provider { android.compileOptions.targetCompatibility.toString() })

    kotlinLanguageVersion.set(kotlin.compilerOptions.languageVersion.map { it.version })
    kotlinApiVersion.set(kotlin.compilerOptions.apiVersion.map { it.version })
    kotlinJvmTarget.set(kotlin.compilerOptions.jvmTarget.map { it.target })
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            injectKotlinMetadataToRoot,
            GenerateKotlinMetadataTask::outputDir
        )
    }
}