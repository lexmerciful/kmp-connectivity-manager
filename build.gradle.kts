plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.compose.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.dokka)
}

// API docs site for every published module: ./gradlew :dokkaGenerate → build/dokka/html
dokka {
    moduleName.set("KMP Connectivity")
    dokkaPublications.html {
        includes.from("Module.md")
    }
}

// Dokka titles the landing page "All modules"; give search results a descriptive title and snippet.
tasks.named<org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml") {
    val index = outputDirectory.file("index.html")
    doLast {
        val file = index.get().asFile
        val title = "KMP Connectivity: internet connectivity monitor for Kotlin Multiplatform | API reference"
        val description = "API reference for KMP Connectivity, a Kotlin Multiplatform library that tells Android, " +
            "iOS, Desktop and Web apps whether the internet is reachable and shows a \"No internet connection\" " +
            "banner in Compose Multiplatform and Jetpack Compose."
        val html = file.readText()
        check("<title>All modules</title>" in html) { "Dokka's landing page title changed; update build.gradle.kts" }
        file.writeText(
            html.replace(
                "<title>All modules</title>",
                "<title>$title</title>\n    <meta name=\"description\" content=\"${description.replace("\"", "&quot;")}\">",
            ),
        )
    }
}

dependencies {
    dokka(project(":connectivity-core"))
    dokka(project(":connectivity-compose"))
    dokka(project(":connectivity-ktor"))
    dokka(project(":connectivity-okhttp"))
}
