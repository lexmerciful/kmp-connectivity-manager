package io.github.lexmerciful.connectivity

/**
 * Marks API that may change in a binary- or source-incompatible way in a minor release.
 */
@RequiresOptIn(
    message = "This connectivity API is experimental and may change without notice.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class ExperimentalConnectivityApi

/** DSL scope marker for the configuration builders. */
@DslMarker
public annotation class ConnectivityDsl
