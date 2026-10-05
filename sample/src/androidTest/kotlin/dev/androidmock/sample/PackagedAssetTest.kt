package dev.androidmock.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PackagedAssetTest {

	@Test
	fun debugAssetIsReadFromTargetApkBeforeDecide() {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val reads = AtomicInteger()
		createDebugMocks(context) { reads.incrementAndGet() }.use { mocks ->
			val request = RequestSnapshots.fromUrl("GET", "https://api.example.test/v1/receipt")
			val first = mocks.engine.decide(request)
			assertTrue(first is Decision.Mock)
			val body = (first as Decision.Mock).response.body as ResponseBody.Bytes
			assertEquals("{\"orderId\":42}\n", String(body.bytes))
			assertEquals(String(body.bytes), String(((mocks.engine.decide(request) as Decision.Mock).response.body as ResponseBody.Bytes).bytes))
			assertEquals(1, reads.get())
		}
	}
}
