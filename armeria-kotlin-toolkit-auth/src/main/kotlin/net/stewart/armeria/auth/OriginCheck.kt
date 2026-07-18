package net.stewart.armeria.auth

/**
 * CSRF gate for cookie-based auth: when a request carries an `Origin`
 * header it must match the request authority, or cookie credentials are
 * rejected. Native clients don't send Origin, so the absent-header case
 * passes — Bearer-token clients aren't CSRF-able by construction.
 *
 * Hostname-only comparison: HTTP/2 reverse proxies often rewrite the
 * `:authority` pseudo-header, dropping or changing the port from the
 * public-facing one the browser puts in Origin. Matching hosts and
 * ignoring ports keeps the gate meaningful (a different hostname can't
 * pass) while tolerating the proxy rewrite. Fails closed when the
 * authority is unknown.
 */
object OriginCheck {

    fun originPermitted(origin: String?, authority: String?): Boolean {
        if (origin == null) return true
        if (authority.isNullOrBlank()) return false
        val originHost = parseOriginHost(origin) ?: return false
        val authorityHost = stripPort(authority)
        return originHost.equals(authorityHost, ignoreCase = true)
    }

    /** Pull just the hostname out of `scheme://host[:port]`. */
    private fun parseOriginHost(origin: String): String? =
        try {
            java.net.URI(origin).host
        } catch (_: Exception) {
            null
        }

    /**
     * Strip the trailing `:port` from a `host[:port]` authority value.
     * Handles bracketed IPv6 literals (`[::1]:8443` → `[::1]`).
     */
    private fun stripPort(hostPort: String): String {
        if (hostPort.startsWith('[')) {
            val close = hostPort.indexOf(']')
            if (close >= 0) return hostPort.substring(0, close + 1)
            return hostPort
        }
        val colon = hostPort.lastIndexOf(':')
        return if (colon < 0) hostPort else hostPort.substring(0, colon)
    }

    /**
     * Pull a single cookie value out of an RFC 6265 `Cookie` header
     * (`name1=value1; name2=value2; …`).
     */
    fun parseCookie(cookieHeader: String, name: String): String? {
        for (raw in cookieHeader.split(';')) {
            val entry = raw.trim()
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            if (entry.substring(0, eq) == name) {
                return entry.substring(eq + 1)
            }
        }
        return null
    }
}
