package com.jackra1n.dooplex.playback

import java.net.URI
import java.net.URLDecoder

internal object NativeMediaIdentity {
    fun matches(reported: String, expected: URI): Boolean {
        val actual = try {
            URI(reported).normalize()
        } catch (_: Exception) {
            return false
        }
        if (actual.rawPath != expected.rawPath || actual.rawFragment != null) return false
        if (actual.isAbsolute) {
            if (!actual.scheme.equals(expected.scheme, ignoreCase = true) ||
                !actual.host.equals(expected.host, ignoreCase = true) ||
                effectivePort(actual) != effectivePort(expected) || actual.rawUserInfo != expected.rawUserInfo
            ) return false
        } else if (actual.rawAuthority != null || !reported.startsWith("/")) {
            return false
        }
        return try {
            mediaQuery(actual) == mediaQuery(expected)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun mediaQuery(uri: URI): Map<String, List<String>> {
        val query = uri.rawQuery ?: return emptyMap()
        val parameters = linkedMapOf<String, MutableList<String>>()
        for (parameter in query.split('&')) {
            val pair = parameter.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            // Zidoo strips these HTTP-header equivalents from its reported media path.
            if (key.startsWith("X-Plex-", ignoreCase = true)) continue
            val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
            parameters.getOrPut(key) { mutableListOf() }.add(value)
        }
        return parameters
    }

    private fun effectivePort(uri: URI): Int =
        if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
}
