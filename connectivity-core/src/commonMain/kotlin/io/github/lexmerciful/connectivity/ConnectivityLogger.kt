package io.github.lexmerciful.connectivity

/** Receives diagnostic messages from the monitor. */
public fun interface ConnectivityLogger {

    public fun log(level: Level, message: String, throwable: Throwable?)

    public enum class Level { Debug, Warning, Error }

    public companion object {
        /** Default. Discards every message. */
        public val None: ConnectivityLogger = ConnectivityLogger { _, _, _ -> }

        /** Prints every message to standard output. Handy while developing. */
        public val Println: ConnectivityLogger = ConnectivityLogger { level, message, throwable ->
            println("[Connectivity/$level] $message" + (throwable?.let { ": $it" } ?: ""))
        }
    }
}
