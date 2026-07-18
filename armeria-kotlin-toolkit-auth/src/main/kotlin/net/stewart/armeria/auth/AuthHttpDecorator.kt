package net.stewart.armeria.auth

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import net.stewart.auth.AuthUser
import net.stewart.auth.JwtService
import net.stewart.auth.SessionService
import net.stewart.auth.UserRepository

/**
 * Configuration for [AuthHttpDecorator]. Identity resolution is
 * expressed as lambdas; [httpAuthConfig] builds one directly from the
 * auth-kotlin-toolkit services.
 */
data class HttpAuthConfig(
    /** Pre-setup gate: when provided and false, every request gets 403. */
    val hasUsers: (() -> Boolean)? = null,
    /** Validates a session cookie value. Null disables the cookie path. */
    val cookieAuthenticator: ((String) -> AuthUser?)? = null,
    val cookieName: String = "auth_session",
    /** Validates an `Authorization: Bearer` token. Null disables the bearer path. */
    val bearerAuthenticator: ((String) -> AuthUser?)? = null,
    /**
     * App-specific fallback resolvers tried after cookie and bearer —
     * device tokens, signed URLs, .... Each returns a user or null.
     * The second value names the method for [HTTP_AUTH_METHOD_KEY].
     */
    val extraResolvers: List<Pair<String, (ServiceRequestContext, HttpRequest) -> AuthUser?>> = emptyList(),
    /**
     * Application-specific gate evaluated after identity resolution
     * (roles, terms-of-use, ...). Return a non-null response to
     * short-circuit the request.
     */
    val gate: ((user: AuthUser, ctx: ServiceRequestContext) -> HttpResponse?)? = null,
)

/** Build an [HttpAuthConfig] from auth-kotlin-toolkit services. */
fun httpAuthConfig(
    sessionService: SessionService? = null,
    jwtService: JwtService? = null,
    userRepository: UserRepository? = null,
    extraResolvers: List<Pair<String, (ServiceRequestContext, HttpRequest) -> AuthUser?>> = emptyList(),
    gate: ((AuthUser, ServiceRequestContext) -> HttpResponse?)? = null,
): HttpAuthConfig = HttpAuthConfig(
    hasUsers = userRepository?.let { r -> { r.hasUsers() } },
    cookieAuthenticator = sessionService?.let { s -> { token -> s.validateToken(token) } },
    cookieName = sessionService?.cookieName ?: "auth_session",
    bearerAuthenticator = jwtService?.let { s -> { token -> s.validateAccessToken(token) } },
    extraResolvers = extraResolvers,
    gate = gate,
)

/**
 * Armeria decorator authenticating HTTP requests. Identity paths, in
 * precedence order: session cookie, `Authorization: Bearer`, then any
 * [HttpAuthConfig.extraResolvers]. On success the user is attached to
 * the request context ([HTTP_AUTH_USER_KEY], read via [authUser]) along
 * with the method name that succeeded ([HTTP_AUTH_METHOD_KEY]).
 * Unauthenticated requests get 401; pre-setup requests (no users yet)
 * get 403.
 *
 * Extracted from MediaManager's ArmeriaAuthDecorator (MIT, same
 * copyright holder), with device tokens and legal gates generalized
 * into [HttpAuthConfig.extraResolvers] and [HttpAuthConfig.gate].
 */
class AuthHttpDecorator(private val config: HttpAuthConfig) : DecoratingHttpServiceFunction {

    override fun serve(
        delegate: HttpService,
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        config.hasUsers?.let { hasUsers ->
            if (!hasUsers()) return HttpResponse.of(HttpStatus.FORBIDDEN)
        }

        var user: AuthUser? = null
        var method: String? = null

        val cookieAuth = config.cookieAuthenticator
        if (cookieAuth != null) {
            val cookie = req.headers().cookies().firstOrNull { it.name() == config.cookieName }
            if (cookie != null) {
                user = cookieAuth(cookie.value())
                if (user != null) method = "cookie"
            }
        }

        val bearerAuth = config.bearerAuthenticator
        if (user == null && bearerAuth != null) {
            val header = req.headers().get("authorization")
            if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
                user = bearerAuth(header.substring(7).trim())
                if (user != null) method = "bearer"
            }
        }

        if (user == null) {
            for ((name, resolver) in config.extraResolvers) {
                user = resolver(ctx, req)
                if (user != null) {
                    method = name
                    break
                }
            }
        }

        if (user == null) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        }

        ctx.setAttr(HTTP_AUTH_USER_KEY, user)
        ctx.setAttr(HTTP_AUTH_METHOD_KEY, method!!)

        config.gate?.invoke(user, ctx)?.let { return it }

        return delegate.serve(ctx, req)
    }
}
