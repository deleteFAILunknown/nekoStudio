plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val specialJar = tasks.register<Jar>("specialJar") {
    archiveBaseName.set("special")
    from("build/special")
}

configurations {
    consumable("special") {
        outgoing.artifact(specialJar)
    }
}

tasks.named("assemble") {
    dependsOn(specialJar)
}