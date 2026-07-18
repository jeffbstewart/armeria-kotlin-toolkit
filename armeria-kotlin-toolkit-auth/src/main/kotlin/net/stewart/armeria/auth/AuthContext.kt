package net.stewart.armeria.auth

import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Context
import io.netty.util.AttributeKey
import net.stewart.auth.AuthUser

/** gRPC context key carrying the authenticated user for the current RPC. */
val GRPC_AUTH_USER_KEY: Context.Key<AuthUser> = Context.key("toolkit-auth-user")

/**
 * The authenticated user for the current gRPC call. Only valid inside an
 * RPC that passed [AuthGrpcInterceptor]; calling it elsewhere is a bug.
 */
fun currentAuthUser(): AuthUser = GRPC_AUTH_USER_KEY.get()
    ?: error("BUG: currentAuthUser() called in an unauthenticated RPC")

/** Armeria request attribute carrying the authenticated user. */
val HTTP_AUTH_USER_KEY: AttributeKey<AuthUser> =
    AttributeKey.valueOf("toolkit.authUser")

/** Armeria request attribute naming the auth method that succeeded. */
val HTTP_AUTH_METHOD_KEY: AttributeKey<String> =
    AttributeKey.valueOf("toolkit.authMethod")

/** The authenticated user on an HTTP request, or null before/without [AuthHttpDecorator]. */
fun authUser(ctx: ServiceRequestContext): AuthUser? = ctx.attr(HTTP_AUTH_USER_KEY)
