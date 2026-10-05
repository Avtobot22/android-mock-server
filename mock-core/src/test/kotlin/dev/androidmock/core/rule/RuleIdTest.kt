package dev.androidmock.core.rule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class RuleIdTest {

	@Test
	fun blankIdsAreRejected() {
		listOf("", " ", "\t\n").forEach { value ->
			assertFailsWith<IllegalArgumentException> { RuleId(value) }
		}
	}

	@Test
	fun nonBlankIdPreservesItsExactValue() {
		assertEquals(" Receipt ", RuleId(" Receipt ").value)
		assertNotEquals(RuleId("Receipt"), RuleId("receipt"))
	}

	@Test
	fun equalIdsWorkAsBoxedCollectionKeys() {
		val registered = RuleId("receipt")
		val lookup = RuleId("receipt")
		assertEquals(registered, lookup)
		assertEquals(registered.hashCode(), lookup.hashCode())
		assertEquals("response", mapOf(registered to "response")[lookup])
		assertEquals(1, setOf(registered, lookup).size)
		assertNotEquals<Any>("receipt", registered)
	}
}
