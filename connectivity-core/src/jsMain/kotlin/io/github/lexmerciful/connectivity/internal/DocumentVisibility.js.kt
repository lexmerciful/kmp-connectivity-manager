package io.github.lexmerciful.connectivity.internal

import kotlinx.browser.document

internal actual fun isDocumentHidden(): Boolean = document.asDynamic().hidden == true
