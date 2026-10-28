package dev.androidmock.okhttp.server

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LoopbackLifecycleTest {
    @Test
    fun closeDoesNotHoldLifecycleLockWhileWaitingForCallbackThatReadsBaseUrl() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inspectBaseUrl = AtomicBoolean(false)
        val observedClosedState = AtomicBoolean(false)
        lateinit var server: LoopbackMockServer
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("callback", "/callback") {
                    matching {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        if (inspectBaseUrl.get()) {
                            try { server.baseUrl } catch (_: IllegalStateException) { observedClosedState.set(true) }
                        }
                        true
                    }
                    respond { bodyText("done") }
                }
            })
        }
        server = LoopbackMockServer(engine).apply { start() }
        val client = testClient()
        val call = client.newCall(Request.Builder().url(server.baseUrl.resolve("callback")!!).build())
        val executor = Executors.newFixedThreadPool(3)
        try {
            val request = executor.submit { try { call.execute().close() } catch (_: IOException) { } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = executor.submit { server.close() }
            val closedStatePublished = executor.submit<Boolean> {
                var closedState = false
                while (!closedState) {
                    try { server.baseUrl } catch (_: IllegalStateException) { closedState = true }
                }
                closedState
            }
            assertTrue(closedStatePublished.get(1, TimeUnit.SECONDS))
            inspectBaseUrl.set(true)
            release.countDown()
            closing.get(2, TimeUnit.SECONDS)
            request.get(2, TimeUnit.SECONDS)
            assertTrue(observedClosedState.get())
        } finally {
            // If close still owns the lifecycle lock, release the callback without asking for it.
            release.countDown()
            call.cancel()
            server.close()
            client.closeTestClient()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun startsOnceOnIpv4LoopbackAndCloseReleasesListenerAndWorkers() {
        val engine = RuleEngine().apply {
            replaceRules(mockRules { rule("health", "/health") { respond { bodyText("ok") } } })
        }
        val server = LoopbackMockServer(engine)
        val client = testClient()
        try {
            assertFailsWith<IllegalStateException> { server.baseUrl }
            server.start()
            val baseUrl = server.baseUrl
            assertEquals("127.0.0.1", baseUrl.host)
            assertEquals("http", baseUrl.scheme)
            assertTrue(baseUrl.port > 0)
            assertFailsWith<IllegalStateException> { server.start() }
            client.get(baseUrl, "health").use { assertEquals("ok", it.body!!.string()) }
            val serverThreads = Thread.getAllStackTraces().keys.filter {
                it.name.startsWith("MockWebServer") && it.name.contains(baseUrl.port.toString())
            }
            server.close()
            server.close()
            assertFailsWith<IllegalStateException> { server.baseUrl }
            assertFailsWith<IllegalStateException> { server.start() }
            for (thread in serverThreads) {
                thread.join(2_000)
                assertFalse(thread.isAlive, "Server thread still alive after close: ${thread.name}")
            }
            assertFailsWith<IOException> {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", baseUrl.port), 500) }
            }
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), baseUrl.port))
            }
        } finally {
            client.closeTestClient()
            server.close()
        }
    }

    @Test
    fun closeBeforeStartIsTerminalAndDoesNotOpenPort() {
        val server = LoopbackMockServer(RuleEngine())
        server.close()
        server.close()
        assertFailsWith<IllegalStateException> { server.start() }
        assertFailsWith<IllegalStateException> { server.baseUrl }
    }

    @Test
    fun malformedLogicalOriginsAndInvalidCaptureLimitsFailBeforeStart() {
        for (url in listOf(
            "not a URL", "ftp://example.test/", "https://user:password@example.test/",
            "https://example.test/prefix/", "https://example.test/?query=value", "https://example.test/#fragment",
        )) {
            assertFailsWith<IllegalArgumentException>(url) { LoopbackMockServer(RuleEngine(), url) }
        }
        for (limit in listOf(0L, -1L, Int.MAX_VALUE.toLong() + 1)) {
            assertFailsWith<IllegalArgumentException> { LoopbackMockServer(RuleEngine(), maxRequestBytes = limit) }
        }
    }

    @Test
    fun concurrentStartAndCloseHaveOnlyOneLifecycleAndNoLiveListenerAfterClose() {
        val server = LoopbackMockServer(RuleEngine())
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val begin = CountDownLatch(1)
        try {
            val operations = (0 until 8).map { index ->
                executor.submit {
                    ready.countDown()
                    check(begin.await(5, TimeUnit.SECONDS))
                    if (index % 2 == 0) server.close() else {
                        try { server.start() } catch (_: IllegalStateException) { }
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            begin.countDown()
            operations.forEach { it.get(5, TimeUnit.SECONDS) }
            server.close()
            assertFailsWith<IllegalStateException> { server.baseUrl }
            assertFailsWith<IllegalStateException> { server.start() }
        } finally {
            begin.countDown()
            server.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun closeInterruptsLongDeliveryDelayAndClosesActiveRequests() {
        val entered = CountDownLatch(1)
        val engine = RuleEngine().apply {
            replaceRules(mockRules {
                rule("slow", "/slow") {
                    matching { entered.countDown(); true }
                    respond { delay(30.seconds); bodyText("delayed") }
                }
            })
        }
        val server = LoopbackMockServer(engine).apply { start() }
        val executor = Executors.newFixedThreadPool(2)
        val client = testClient()
        val call = client.newCall(Request.Builder().url(server.baseUrl.resolve("slow")!!).build())
        try {
            val request = executor.submit {
                try { call.execute().use { it.body?.bytes() } } catch (_: IOException) { }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            executor.submit { server.close() }.get(2, TimeUnit.SECONDS)
            request.get(2, TimeUnit.SECONDS)
        } finally {
            call.cancel()
            server.close()
            client.closeTestClient()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
