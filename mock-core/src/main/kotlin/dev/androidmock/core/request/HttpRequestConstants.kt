package dev.androidmock.core.request

internal const val HTTP_SCHEME = "http"
internal const val HTTPS_SCHEME = "https"
internal const val HTTP_DEFAULT_PORT = 80
internal const val HTTPS_DEFAULT_PORT = 443
internal const val MIN_PORT = 1
internal const val MAX_PORT = 65535
internal const val UNSPECIFIED_PORT = -1

internal const val ROOT_PATH = "/"
internal const val PATH_SEPARATOR = '/'
internal const val QUERY_SEPARATOR = '?'
internal const val FRAGMENT_SEPARATOR = '#'
internal const val QUERY_ENTRY_SEPARATOR = '&'
internal const val QUERY_VALUE_SEPARATOR = '='
internal const val PERCENT_ESCAPE_MARKER = '%'
internal const val HEX_RADIX = 16
internal const val PERCENT_ESCAPE_HEX_LENGTH = 2
internal const val PERCENT_ESCAPE_LENGTH = 1 + PERCENT_ESCAPE_HEX_LENGTH

internal val HTTP_TOKEN_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
internal val PERCENT_ESCAPE_PATTERN = Regex("%[0-9a-fA-F]{2}")
