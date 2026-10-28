package dev.androidmock.okhttp.server

import java.util.logging.Filter
import java.util.logging.Logger

/** MockWebServer's FINE request-line logs may contain query secrets. */
internal object TransportLogFilter {
    private val logger = Logger.getLogger("okhttp3.mockwebserver.MockWebServer")
    private var previous: Filter? = null
    private var installed: Filter? = null
    @Volatile private var markers: Set<String> = emptySet()

    @Synchronized
    fun register(port: Int) {
        if (markers.isEmpty()) {
            val delegate = logger.filter
            previous = delegate
            installed = Filter { record ->
                val ownMessage = markers.any { record.message?.startsWith(it) == true }
                !ownMessage && (delegate?.isLoggable(record) ?: true)
            }.also { logger.filter = it }
        }
        markers = markers + "MockWebServer[$port]"
    }

    @Synchronized
    fun unregister(port: Int) {
        markers = markers - "MockWebServer[$port]"
        if (markers.isEmpty()) {
            if (logger.filter === installed) logger.filter = previous
            installed = null
            previous = null
        }
    }
}
