plugins {
    alias(libs.plugins.connectivity.kmp.library)
    alias(libs.plugins.connectivity.publish)
}

kotlin {
    android {
        namespace = "io.github.lexmerciful.connectivity.ktor"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":connectivity-core"))
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
