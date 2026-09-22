import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "dev.gridiron.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("jvmLibrary") {
            id = libs.plugins.gridiron.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = libs.plugins.gridiron.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.gridiron.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.gridiron.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
    }
}
