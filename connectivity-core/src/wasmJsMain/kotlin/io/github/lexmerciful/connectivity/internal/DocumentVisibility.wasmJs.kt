package io.github.lexmerciful.connectivity.internal

internal actual fun isDocumentHidden(): Boolean = jsDocumentHidden()

private fun jsDocumentHidden(): Boolean = js("document.hidden === true")
