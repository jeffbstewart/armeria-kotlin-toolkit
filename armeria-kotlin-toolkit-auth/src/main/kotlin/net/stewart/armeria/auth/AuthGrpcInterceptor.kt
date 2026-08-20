package net.stewart.armeria.auth

import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.stewart.auth.AuthUser
import net.stewart.auth.JwtService
import net.stewart.auth.SessionService
import org.slf4j.LoggerFactory

/**
 * The request authority (Host) for a server call. Armeria's gRPC
 * bridge returns null from [ServerCall.getAuthority] — most visibly on
 * the gRPC-Web path browsers use — which would make the Origin CSRF
 * check fail closed on every cookie-authenticated browser RPC. Fall
 * back to the Armeria request context, which always knows the
 * authority the request was addressed to.
 */
fun requestAuthority(call: ServerCall<*, *>): String? =
    call.authority ?: ServiceRequestContext.currentOrNull()?.request()?.authority()

/**
 * Configuration for [AuthGrpcInterceptor]. Identity resolution is
 * expressed as lambdas so applications (and tests) can wire anything;
 * [grpcAuthConfig] builds one directly from the auth-kotlin-toolkit
 * services.
 */
data class GrpcAuthConfig(
    /** Validates an `Authorization: Bearer` token. Null disables the bearer path. */
    val bearerAuthenticator: ((String) -> AuthUser?)? = null,
    /** Validates a session cookie value. Null disables the cookie path. */
    val cookieAuthenticator: ((String) -> AuthUser?)? = null,
    val cookieName: String = "auth_session",
    /** Full method names (`package.Service/Method`) reachable without credentials. */
    val unauthenticatedMethods: Set<String> = emptySet(),
    /**
     * Application-specific gate evaluated after identity resolution
     * (roles, terms-of-use, must-change-password, ...). Return a
     * non-null [Status] to reject the call.
     */
    val gate: ((user: AuthUser, fullMethodName: String) -> Status?)? = null,
)

/** Build a [GrpcAuthConfig] from auth-kotlin-toolkit services. */
fun grpcAuthConfig(
    sessionService: SessionService? = null,
    jwtService: JwtService? = null,
    unauthenticatedMethods: Set<String> = emptySet(),
    gate: ((AuthUser, String) -> Status?)? = null,
): GrpcAuthConfig = GrpcAuthConfig(
    bearerAuthenticator = jwtService?.let { s -> { token -> s.validateAccessToken(token) } },
    cookieAuthenticator = sessionService?.let { s -> { token -> s.validateToken(token) } },
    cookieName = sessionService?.cookieName ?: "auth_session",
    unauthenticatedMethods = unauthenticatedMethods,
    gate = gate,
)

/**
 * gRPC [ServerInterceptor] enforcing authentication on every RPC not in
 * [GrpcAuthConfig.unauthenticatedMethods]. Two identity paths, in
 * precedence order:
 *
 * 1. `Authorization: Bearer <jwt>` — native and programmatic clients.
 * 2. HttpOnly session cookie — browser SPAs (which can't read the JWT),
 *    gated by a CSRF check requiring the `Origin` header (when present)
 *    to match the request authority. Armeria forwards HTTP/2 headers
 *    into gRPC metadata, so the `Cookie` header is visible here.
 *
 * On success the user lands in [GRPC_AUTH_USER_KEY]; service methods
 * read it via [currentAuthUser].
 *
 * Extracted from MediaManager's AuthInterceptor (MIT, same copyright
 * holder), with the app-specific gates generalized into
 * [GrpcAuthConfig.gate].
 */
class AuthGrpcInterceptor(private val config: GrpcAuthConfig) : ServerInterceptor {

    private val log = LoggerFactory.getLogger(AuthGrpcInterceptor::class.java)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName

        if (method in config.unauthenticatedMethods) {
            return next.startCall(call, headers)
        }

        val authority = requestAuthority(call)
        val user = resolveBearer(headers) ?: resolveCookie(headers, authority)
        if (user == null) {
            logAuthFailure(method, headers, authority)
            call.close(
                Status.UNAUTHENTICATED.withDescription("Missing or invalid credentials"),
                Metadata()
            )
            return noop()
        }

        config.gate?.invoke(user, method)?.let { rejection ->
            call.close(rejection, Metadata())
            return noop()
        }

        val ctx = Context.current().withValue(GRPC_AUTH_USER_KEY, user)
        return Contexts.interceptCall(ctx, call, headers, next)
    }

    private fun extractBearer(headers: Metadata): String? {
        val value = headers.get(AUTHORIZATION_KEY) ?: return null
        return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).trim() else null
    }

    private fun resolveBearer(headers: Metadata): AuthUser? {
        val authenticator = config.bearerAuthenticator ?: return null
        val token = extractBearer(headers) ?: return null
        return authenticator(token)
    }

    private fun resolveCookie(headers: Metadata, authority: String?): AuthUser? {
        val authenticator = config.cookieAuthenticator ?: return null
        val cookieHeader = headers.get(COOKIE_KEY) ?: return null
        val token = OriginCheck.parseCookie(cookieHeader, config.cookieName) ?: return null
        val origin = headers.get(ORIGIN_KEY)
        if (!OriginCheck.originPermitted(origin, authority)) {
            log.warn("Cookie auth denied — Origin {} does not match authority {}", origin, authority)
            return null
        }
        return authenticator(token)
    }

    /**
     * Single-line diagnostic when auth resolved nobody, distinguishing
     * the failure path without leaking token or cookie values.
     */
    private fun logAuthFailure(method: String, headers: Metadata, authority: String?) {
        val hasBearer = extractBearer(headers) != null
        val hasSessionCookie = headers.get(COOKIE_KEY)
            ?.let { OriginCheck.parseCookie(it, config.cookieName) != null } == true
        val origin = headers.get(ORIGIN_KEY)
        log.info(
            "AUTH_DENIED rpc={} bearer={} session_cookie={} origin={} authority={} origin_permitted={}",
            method, hasBearer, hasSessionCookie, origin ?: "(none)", authority ?: "(none)",
            OriginCheck.originPermitted(origin, authority),
        )
    }

    private fun <ReqT> noop(): ServerCall.Listener<ReqT> = object : ServerCall.Listener<ReqT>() {}

    companion object {
        private val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val COOKIE_KEY: Metadata.Key<String> =
            Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER)
        private val ORIGIN_KEY: Metadata.Key<String> =
            Metadata.Key.of("origin", Metadata.ASCII_STRING_MARSHALLER)
    }
}
