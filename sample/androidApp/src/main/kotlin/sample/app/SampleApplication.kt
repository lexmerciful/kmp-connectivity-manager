package sample.app

import android.app.Application
import io.github.lexmerciful.connectivity.ConnectivityMonitor

class SampleApplication : Application() {
    /** One monitor per process (in a real app: a DI singleton). */
    val connectivityMonitor: ConnectivityMonitor by lazy { ConnectivityMonitor(this) }
}
