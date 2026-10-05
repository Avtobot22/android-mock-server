package dev.androidmock.core.request

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

private const val INVALID_PERCENT_ESCAPE_MESSAGE = "Invalid percent escape"

internal fun normalizeUrl(originalUrl: String): UrlSnapshot {
	val uri = URI(originalUrl)
	val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw InvalidRequestException()
	require(scheme == HTTP_SCHEME || scheme == HTTPS_SCHEME)
	require(uri.rawUserInfo == null && uri.rawFragment == null)
	val host = uri.host?.lowercase(Locale.ROOT) ?: throw InvalidRequestException()
	require(host.isNotBlank())
	val port = if (uri.port == UNSPECIFIED_PORT) (if (scheme == HTTPS_SCHEME) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT) else uri.port
	require(port in MIN_PORT..MAX_PORT)
	val path = canonicalPath(uri.rawPath.orEmpty())
	val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.split(QUERY_ENTRY_SEPARATOR)?.map { segment ->
		val separator = segment.indexOf(QUERY_VALUE_SEPARATOR)
		if (separator < 0) QueryEntry(decode(segment), null)
		else QueryEntry(decode(segment.substring(0, separator)), decode(segment.substring(separator + 1)))
	} ?: emptyList()
	return UrlSnapshot(scheme, host, port, path, query)
}

internal fun canonicalPath(path: String): String {
	val actual = path.ifEmpty { ROOT_PATH }
	require(actual.startsWith(PATH_SEPARATOR))
	decode(actual) // Also validates percent escapes and UTF-8.
	return PERCENT_ESCAPE_PATTERN.replace(actual) { it.value.uppercase() }
}

private fun decode(value: String): String {
	val bytes = ByteArrayOutputStream()
	var index = 0
	while (index < value.length) {
		if (value[index] == PERCENT_ESCAPE_MARKER) {
			require(index + PERCENT_ESCAPE_HEX_LENGTH < value.length)
			val hex = value.substring(index + 1, index + PERCENT_ESCAPE_LENGTH)
			val byte = hex.toIntOrNull(HEX_RADIX) ?: throw IllegalArgumentException(INVALID_PERCENT_ESCAPE_MESSAGE)
			bytes.write(byte)
			index += PERCENT_ESCAPE_LENGTH
		} else {
			val codePoint = Character.codePointAt(value, index)
			bytes.write(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8))
			index += Character.charCount(codePoint)
		}
	}
	return StandardCharsets.UTF_8.newDecoder()
		.onMalformedInput(CodingErrorAction.REPORT)
		.onUnmappableCharacter(CodingErrorAction.REPORT)
		.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}
