import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.dokka.gradle.DokkaExtension

/** Kotlin Multiplatform library for every target: android, jvm, js, wasmJs, iosArm64, iosSimulatorArm64. */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureKotlinMultiplatform(allTargets = true)
}

/** Kotlin Multiplatform library for JVM-only APIs: android + jvm. */
class KmpJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = target.configureKotlinMultiplatform(allTargets = false)
}

/** [KmpLibraryConventionPlugin] plus the Compose Multiplatform and Compose compiler plugins. */
class ComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureKotlinMultiplatform(allTargets = true)
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        // Compose UI tests run on the desktop JVM only; there is no web UI test bundle to check.
        tasks.matching { it.name.startsWith("checkComposeUiTestConfigurationFor") }.configureEach { enabled = false }
    }
}

/**
 * Maven Central publishing. Coordinates and POM come from `gradle.properties`
 * (`GROUP`, `VERSION_NAME`, `POM_*`); the artifact id is the module name.
 * Publications are signed only when signing credentials are configured.
 * Dokka builds the `-javadoc.jar` and the module's page on the API docs site.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.dokka")
        extensions.configure<DokkaExtension> {
            moduleName.set(project.name)
            dokkaSourceSets.configureEach {
                val moduleDoc = file("Module.md")
                if (moduleDoc.exists()) includes.from(moduleDoc)
                sourceLink {
                    localDirectory.set(file("src"))
                    remoteUrl("https://github.com/lexmerciful/kmp-connectivity-manager/tree/main/${project.name}/src")
                    remoteLineSuffix.set("#L")
                }
            }
        }
        pluginManager.apply("com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            val hasSigningKey = providers.gradleProperty("signing.keyId").isPresent ||
                providers.gradleProperty("signingInMemoryKey").isPresent
            if (hasSigningKey) signAllPublications()
        }
    }
}
