# Module connectivity-compose

A "No internet connection" strip for Jetpack Compose and Compose Multiplatform (Android, iOS, Desktop, Web).

Wrap your root content once in
[ConnectivityStripLayout][io.github.lexmerciful.connectivity.compose.ConnectivityStripLayout] and an offline banner
appears whenever the [ConnectivityMonitor][io.github.lexmerciful.connectivity.ConnectivityMonitor] reports no
internet, with an optional "Back online" confirmation. The strip handles edge-to-edge window insets without a layout
jump, can sit at the top or bottom, push content or overlay it, and every visual is overridable, down to a fully
custom slot. It is a polite accessibility live region, so screen readers announce changes.

# Package io.github.lexmerciful.connectivity.compose

The strip layout, the default Material 3 strip and its defaults.
