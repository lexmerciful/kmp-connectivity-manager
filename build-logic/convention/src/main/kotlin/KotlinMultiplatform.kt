import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 24
internal val JVM_TARGET = JvmTarget.JVM_17

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/**
 * Shared Kotlin Multiplatform setup for every library module.
 *
 * @param allTargets true = android, jvm, js, wasmJs, iOS; false = android + jvm only (JVM-only APIs such as OkHttp).
 */
@OptIn(ExperimentalWasmDsl::class, ExperimentalAbiValidation::class)
internal fun Project.configureKotlinMultiplatform(allTargets: Boolean) {
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    pluginManager.apply("com.android.kotlin.multiplatform.library")

    extensions.configure<KotlinMultiplatformExtension> {
        explicitApi()

        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
            compileSdk = COMPILE_SDK
            minSdk = MIN_SDK
            compilerOptions { jvmTarget.set(JVM_TARGET) }
            withHostTest { }
        }

        jvm {
            compilerOptions { jvmTarget.set(JVM_TARGET) }
        }

        if (allTargets) {
            js { browser { testTask { useKarma { useChromeHeadless() } } } }
            wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
            iosArm64()
            iosSimulatorArm64()
        }

        compilerOptions {
            optIn.add("io.github.lexmerciful.connectivity.ExperimentalConnectivityApi")
        }

        abiValidation()

        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
            implementation(libs.findLibrary("turbine").get())
        }
    }
}
