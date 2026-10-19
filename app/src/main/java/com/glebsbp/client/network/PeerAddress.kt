package com.glebsbp.client

object PeerAddress {
    fun hasExplicitPort(peer: String): Boolean {
        val trimmed = peer.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            return close != -1 && trimmed.length > close + 1 && trimmed[close + 1] == ':'
        }
        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon <= 0) return false
        val portPart = trimmed.substring(lastColon + 1)
        return portPart.isNotEmpty() && portPart.all(Char::isDigit)
    }

    fun host(peer: String): String {
        val trimmed = peer.trim()
        if (!hasExplicitPort(trimmed)) return trimmed
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            return trimmed.substring(1, close)
        }
        return trimmed.substring(0, trimmed.lastIndexOf(':'))
    }

    fun port(peer: String): Int? {
        val trimmed = peer.trim()
        if (!hasExplicitPort(trimmed)) return null
        return trimmed.substringAfterLast(':').toIntOrNull()
    }

    fun ensurePort(peer: String, defaultPort: Int): String {
        val trimmed = peer.trim()
        if (trimmed.isEmpty()) return trimmed
        if (hasExplicitPort(trimmed)) return trimmed
        return "$trimmed:$defaultPort"
    }

    /** В отличие от [ensurePort] — всегда заменяет порт на [newPort], даже если он уже был указан. */
    fun withPort(peer: String, newPort: Int): String {
        val trimmed = peer.trim()
        if (trimmed.isEmpty()) return trimmed
        return "${host(trimmed)}:$newPort"
    }

    fun httpEndpoint(peer: String, defaultPort: Int): String {
        val trimmed = peer.trim()
        val effectivePort = port(trimmed) ?: defaultPort
        val effectiveHost = host(trimmed)
        val hostForUrl = if (effectiveHost.contains(':')) "[$effectiveHost]" else effectiveHost
        return "$hostForUrl:$effectivePort"
    }
}
