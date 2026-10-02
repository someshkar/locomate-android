package app.locomate.data

import java.security.MessageDigest

/** Keeps preview, local development, and production rail data in separate private stores. */
internal fun railStorageScope(baseUrl: String): String {
    val canonical = baseUrl.trim().trimEnd('/')
    if (canonical.isEmpty()) return "preview"
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.take(12).joinToString("") { "%02x".format(it) }
}
