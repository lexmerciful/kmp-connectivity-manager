plugins {
    alias(libs.plugins.connectivity.kmp.library)
    alias(libs.plugins.connectivity.publish)
}

kotlin {
    android {
        namespace = "io.github.lexmerciful.connectivity"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
