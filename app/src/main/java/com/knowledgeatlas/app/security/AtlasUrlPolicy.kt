package com.knowledgeatlas.app.security

import java.net.URI

/** Shared HTTPS-only policy for remote service and source URLs. */
object AtlasUrlPolicy {
    fun isHttpsUrl(value: String): Boolean {
        if (value.isBlank() || value != value.trim()) return false
        return runCatching {
            val uri = URI(value)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }.getOrDefault(false)
    }
}
