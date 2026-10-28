plugins {
    alias(libs.plugins.connectivity.compose.library)
    alias(libs.plugins.connectivity.publish)
}

kotlin {
    android {
        namespace = "io.github.lexmerciful.connectivity.compose"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":connectivity-core"))
            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.animation)
            implementation(libs.compose.material3)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}
