plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.cmp) apply false
    alias(libs.plugins.kotlin.kmp) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
