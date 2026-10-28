package dev.androidmock.okhttp.server

import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

/** Removes even partially received requests, which MockWebServer never sends to Dispatcher. */
internal class RequestQueueDrainer(
    private val server: MockWebServer,
    port: Int,
    private val onRequestDrained: () -> Unit = {},
) : AutoCloseable {
    private val worker = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                if (server.takeRequest(100, TimeUnit.MILLISECONDS) != null) onRequestDrained()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }, "AndroidMockServer[$port] request cleanup").apply { isDaemon = true }

    fun start() = worker.start()

    override fun close() {
        worker.interrupt()
        var interrupted = Thread.interrupted()
        while (worker.isAlive) {
            try {
                worker.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        try {
            while (true) {
                try {
                    if (server.takeRequest(0, TimeUnit.NANOSECONDS) == null) break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
