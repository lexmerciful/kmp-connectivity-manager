plugins {
    `kotlin-dsl`
}

group = "io.github.lexmerciful.connectivity.buildlogic"

dependencies {
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.maven.publish.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = libs.plugins.connectivity.kmp.library.get().pluginId
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpJvm") {
            id = libs.plugins.connectivity.kmp.jvm.get().pluginId
            implementationClass = "KmpJvmLibraryConventionPlugin"
        }
        register("composeLibrary") {
            id = libs.plugins.connectivity.compose.library.get().pluginId
            implementationClass = "ComposeLibraryConventionPlugin"
        }
        register("publish") {
            id = libs.plugins.connectivity.publish.get().pluginId
            implementationClass = "PublishConventionPlugin"
        }
    }
}
