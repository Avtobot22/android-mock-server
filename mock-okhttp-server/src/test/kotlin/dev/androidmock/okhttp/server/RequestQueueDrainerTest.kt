package dev.androidmock.okhttp.server

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestQueueDrainerTest {
    @Test
    fun partialPostIsDiscardedWhileServerRunsEvenWhenDispatcherNeverReceivesIt() {
        val dispatched = AtomicInteger()
        val drained = CountDownLatch(1)
        MockWebServer().use { server ->
            server.bodyLimit = 4
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    dispatched.incrementAndGet()
                    return MockResponse()
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val drainer = RequestQueueDrainer(server, server.port, onRequestDrained = { drained.countDown() })
            drainer.start()
            try {
                Socket().use { socket ->
                    socket.soTimeout = 2_000
                    socket.connect(InetSocketAddress("127.0.0.1", server.port), 2_000)
                    socket.getOutputStream().apply {
                        // Omit the terminating zero chunk so MWS records an EOF failure, not a request.
                        write(("POST /partial HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                            "Transfer-Encoding: chunked\r\n\r\n7\r\npartial\r\n").toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                    socket.shutdownOutput()
                    assertContentEquals(ByteArray(0), socket.getInputStream().readBytes())
                }
                assertTrue(drained.await(2, TimeUnit.SECONDS), "Partial failed request was retained without dispatch")
                assertEquals(0, dispatched.get())
                assertNull(server.takeRequest(0, TimeUnit.NANOSECONDS))
                val cleanupWorker = Thread.getAllStackTraces().keys.singleOrNull {
                    it.name == "AndroidMockServer[${server.port}] request cleanup"
                }
                assertNotNull(cleanupWorker)
                drainer.close()
                assertFalse(cleanupWorker.isAlive)
            } finally {
                drainer.close()
            }
        }
    }
}
