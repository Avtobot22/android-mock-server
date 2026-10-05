package dev.androidmock.core.integration

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.ConfigurationException
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SerializableResponseDslTest {
	@Serializable
	private data class User(
		@SerialName("user_id") val id: Int,
		val name: String = "anonymous",
		val nickname: String? = null,
	)

	@Serializable
	private data class Team(val members: List<User>, val labels: List<String> = emptyList())

	@Serializable
	private data class Measurement(val value: Double)

	@Serializable
	private data class Profile(val city: String)

	@Serializable
	private data class Account(
		@SerialName("account_id") val id: Int,
		val profile: Profile,
		val tags: List<String> = emptyList(),
		val alias: String? = "unknown",
	)

	@Serializable
	private enum class State {

		NEW,
		DONE
	}

	@Serializable
	private data class Generated(
		val id: Int,
		val title: String = "from-model",
		val enabled: Boolean,
		val profile: Profile,
		val tags: List<String>,
		val attributes: Map<String, Int>,
		val nickname: String?,
		val state: State,
	)

	@Serializable
	private data class NullableCycle(val next: NullableCycle?)

	@Serializable
	private data class RequiredCycle(val next: RequiredCycle)

	@Serializable
	private sealed interface Choice {

		@Serializable
		data object First : Choice
	}

	@Serializable
	private data class PolymorphicContainer(val choice: Choice)

	@Serializable
	private data class NestedAuto(val city: String, val zip: Int)

	@Serializable
	private data class NestedContainer(val profile: NestedAuto)

	@Serializable(with = OpaqueSerializer::class)
	private data class Opaque(val token: String)

	private object OpaqueSerializer : KSerializer<Opaque> {

		override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Opaque", PrimitiveKind.STRING)
		override fun serialize(encoder: Encoder, value: Opaque) = encoder.encodeString(value.token)
		override fun deserialize(decoder: Decoder): Opaque {
			val token = decoder.decodeString()
			if (token.isEmpty()) throw SerializationException("Token cannot be empty")
			return Opaque(token)
		}
	}

	@Serializable
	private data class OpaqueContainer(val opaque: Opaque)

	private fun response(engine: RuleEngine): dev.androidmock.core.response.ResponseSpec =
		assertIs<Decision.Mock>(engine.decide(RequestSnapshots.fromUrl("GET", "https://example.test/team"))).response

	private fun body(engine: RuleEngine): String =
		String(assertIs<ResponseBody.Bytes>(response(engine).body).bytes, Charsets.UTF_8)

	@Test
	fun nestedSerializableModelProducesCompleteJsonAndContentType() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond { bodyJson(Team(listOf(User(7), User(8, "Ирина", "ira")))) }
				}
			})
		}

		assertEquals(
			"""{"members":[{"user_id":7,"name":"anonymous","nickname":null},{"user_id":8,"name":"Ирина","nickname":"ira"}],"labels":[]}""",
			body(engine),
		)
		assertEquals("application/json; charset=utf-8", response(engine).headers.entries.single().value)
	}

	@Test
	fun explicitSerializerAndContentTypeArePreserved() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond {
						header("content-type", "application/problem+json")
						bodyJson(User(3), serializer<User>())
					}
				}
			})
		}

		assertEquals("application/problem+json", response(engine).headers.entries.single().value)
		assertEquals("""{"user_id":3,"name":"anonymous","nickname":null}""", body(engine))
	}

	@Test
	fun contentTypeDeclaredAfterJsonBodyOverridesAutomaticHeader() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond {
						bodyJson(User(3))
						header("CONTENT-TYPE", "application/vnd.example+json")
					}
				}
			})
		}

		assertEquals("application/vnd.example+json", response(engine).headers.entries.single().value)
	}

	@Test
	fun fieldsBuilderUsesSerialNamesNestedModelsListsDefaultsAndNull() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond {
						bodyJson<Account> {
							field("account_id", 42)
							field("profile", Profile("Paris"))
							field("tags", listOf("admin", "reader"))
							field("alias", null)
						}
					}
				}
			})
		}

		assertEquals("""{"account_id":42,"profile":{"city":"Paris"},"tags":["admin","reader"],"alias":null}""", body(engine))
	}

	@Test
	fun fieldsBuilderRejectsUnknownAndDuplicateFields() {
		val unknown = assertFailsWith<ConfigurationException> {
			mockRules {
				rule("team", "/team") {
					respond { bodyJson<Account> { field("id", 1) } }
				}
			}
		}
		assertEquals("Unknown JSON field: id", unknown.message)

		val duplicate = assertFailsWith<ConfigurationException> {
			mockRules {
				rule("team", "/team") {
					respond { bodyJson<Account> { field("account_id", 1); field("account_id", 2) } }
				}
			}
		}
		assertEquals("Duplicate JSON field: account_id", duplicate.message)
	}

	@Test
	fun fieldsBuilderRejectsWrongTypeWithoutPublishing() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") { respond { bodyJson(User(1)) } }
			})
		}

		val wrongType = assertFailsWith<ConfigurationException> {
			engine.replaceRules(mockRules {
				rule("team", "/team") {
					respond { bodyJson<Account> { field("account_id", "secret-value"); field("profile", Profile("Paris")) } }
				}
			})
		}
		assertEquals("Invalid JSON model response", wrongType.message)
		assertEquals(null, wrongType.cause)
		assertEquals("""{"user_id":1,"name":"anonymous","nickname":null}""", body(engine))

	}

	@Test
	fun fieldsBuilderUsesExplicitThenModelDefaultsThenGeneratedTypeDefaults() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond {
						bodyJson<Generated> { field("id", 73) }
					}
				}
			})
		}

		assertEquals(
			"""{"id":73,"title":"from-model","enabled":false,"profile":{"city":""},"tags":[],"attributes":{},"nickname":null,"state":"NEW"}""",
			body(engine),
		)
	}

	@Test
	fun nestedObjectFieldAcceptsPartialDataAndFillsMissingNestedFields() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond {
						bodyJson<NestedContainer> {
							objectField("profile") { field("city", "Tomsk") }
						}
					}
				}
			})
		}

		assertEquals("""{"profile":{"city":"Tomsk","zip":0}}""", body(engine))
	}

	@Test
	fun fieldsBuilderTerminatesNullableRecursiveModelWithNull() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond { bodyJson<NullableCycle> { } }
				}
			})
		}

		assertEquals("""{"next":null}""", body(engine))
	}

	@Test
	fun fieldsBuilderRejectsRequiredRecursiveModelWithoutSafeDefault() {
		val cyclic = assertFailsWith<ConfigurationException> {
			mockRules { rule("team", "/team") { respond { bodyJson<RequiredCycle> { } } } }
		}
		assertEquals(true, cyclic.message!!.startsWith("Cannot generate cyclic JSON model:"))
	}

	@Test
	fun fieldsBuilderRejectsRequiredPolymorphicModelWithoutSafeDefault() {
		val polymorphic = assertFailsWith<ConfigurationException> {
			mockRules { rule("team", "/team") { respond { bodyJson<PolymorphicContainer> { } } } }
		}
		assertEquals(true, polymorphic.message!!.startsWith("Cannot generate JSON default for"))
	}

	@Test
	fun customTypeWithoutSafeDefaultRequiresExplicitValue() {
		val missing = assertFailsWith<ConfigurationException> {
			mockRules { rule("team", "/team") { respond { bodyJson<OpaqueContainer> { } } } }
		}
		assertEquals("Invalid JSON model response", missing.message)

		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") {
					respond { bodyJson<OpaqueContainer> { field("opaque", Opaque("known")) } }
				}
			})
		}
		assertEquals("""{"opaque":"known"}""", body(engine))
	}

	@Test
	fun jsonBodyStillCountsAsTheSingleResponseBody() {
		assertFailsWith<ConfigurationException> {
			mockRules {
				rule("team", "/team") {
					respond { bodyJson(User(1)); bodyText("another body") }
				}
			}
		}
	}

	@Test
	fun failedSerializationKeepsPreviouslyPublishedRules() {
		val engine = RuleEngine().apply {
			replaceRules(mockRules {
				rule("team", "/team") { respond { bodyJson(User(1)) } }
			})
		}

		val failure = assertFailsWith<ConfigurationException> {
			engine.replaceRules(mockRules {
				rule("team", "/team") { respond { bodyJson(Measurement(Double.NaN)) } }
			})
		}

		assertEquals("Cannot serialize response JSON", failure.message)
		assertEquals("""{"user_id":1,"name":"anonymous","nickname":null}""", body(engine))
	}
}
