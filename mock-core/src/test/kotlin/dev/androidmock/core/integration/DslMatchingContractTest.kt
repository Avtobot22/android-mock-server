package dev.androidmock.core.integration

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.QueryEntry
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DslMatchingContractTest {

	@Test
	fun exactQueryListMatchesRepeatedValuesAndDistinguishesFlagFromEmptyValue() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("ordered-query", "/x") {
					match { query { exactList(QueryEntry("tag", "a"), QueryEntry("tag", "b"), QueryEntry("flag", null), QueryEntry("empty", "")) } }
					respond { bodyText("matched") }
				}
			})
		}

		val matching = RequestSnapshots.fromUrl("GET", "https://example.test/x?tag=a&tag=b&flag&empty=")
		val reversed = RequestSnapshots.fromUrl("GET", "https://example.test/x?tag=b&tag=a&flag&empty=")
		val changedFlag = RequestSnapshots.fromUrl("GET", "https://example.test/x?tag=a&tag=b&flag=&empty=")

		assertEquals("matched", String(assertIs<ResponseBody.Bytes>(assertIs<Decision.Mock>(engine.decide(matching)).response.body).bytes))
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(engine.decide(reversed)).error)
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(engine.decide(changedFlag)).error)
	}

	@Test
	fun headerConditionFindsExactValueAmongRepeatedCaseInsensitiveNames() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("header", "/x") {
					match { headers { contains("X-Mode", "mock") } }
					respond { bodyText("matched") }
				}
			})
		}

		val matching = RequestSnapshots.fromUrl(
			"GET", "https://example.test/x", headers = listOf(
				HeaderEntry("x-mode", "live"), HeaderEntry("X-MODE", "mock"),
			)
		)
		val changedValue = RequestSnapshots.fromUrl(
			"GET", "https://example.test/x", headers = listOf(
				HeaderEntry("x-mode", "live"), HeaderEntry("X-MODE", "MOCK"),
			)
		)

		assertIs<Decision.Mock>(engine.decide(matching))
		assertIs<MockFailure.NoMatchingRule>(assertIs<Decision.Fail>(engine.decide(changedValue)).error)
	}
}
