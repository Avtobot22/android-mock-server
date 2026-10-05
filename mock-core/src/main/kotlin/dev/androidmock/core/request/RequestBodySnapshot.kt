package dev.androidmock.core.request

enum class BodyUnavailableReason { ONE_SHOT,
	STREAMING,
	TOO_LARGE,
	UNSUPPORTED
}

sealed interface RequestBodySnapshot {
	data object Absent : RequestBodySnapshot
	class Buffered(bytes: ByteArray) : RequestBodySnapshot {

		private val owned = bytes.copyOf()
		val bytes: ByteArray get() = owned.copyOf()
		internal fun matches(other: ByteArray) = owned.contentEquals(other)
		internal fun contains(other: ByteArray) = other.isEmpty() || owned.indices.any { index ->
			index + other.size <= owned.size && other.indices.all { owned[index + it] == other[it] }
		}
	}

	data class Unavailable(val reason: BodyUnavailableReason) : RequestBodySnapshot
}
