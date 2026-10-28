plugins {
    alias(libs.plugins.connectivity.kmp.jvm)
    alias(libs.plugins.connectivity.publish)
}

kotlin {
    android {
        namespace = "io.github.lexmerciful.connectivity.okhttp"
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":connectivity-core"))
            api(libs.okhttp)
        }
        jvmTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
