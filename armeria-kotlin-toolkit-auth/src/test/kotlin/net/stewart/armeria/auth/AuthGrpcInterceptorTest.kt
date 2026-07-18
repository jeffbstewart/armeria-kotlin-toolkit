package net.stewart.armeria.auth

import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests driving [AuthGrpcInterceptor.interceptCall] with a fake
 * [ServerCall]; the full-server gRPC path is covered by the core
 * module's round-trip test.
 */
class AuthGrpcInterceptorTest {

    private val user = TestUser()

    private class FakeCall(
        private val method: String,
        private val authorityValue: String? = "example.com",
    ) : ServerCall<String, String>() {
        var closedStatus: Status? = null

        override fun getMethodDescriptor(): MethodDescriptor<String, String> =
            MethodDescriptor.newBuilder<String, String>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(method)
                .setRequestMarshaller(StringMarshaller)
                .setResponseMarshaller(StringMarshaller)
                .build()

        override fun getAuthority(): String? = authorityValue
        override fun request(numMessages: Int) {}
        override fun sendHeaders(headers: Metadata) {}
        override fun sendMessage(message: String) {}
        override fun close(status: Status, trailers: Metadata) {
            closedStatus = status
        }
        override fun isCancelled(): Boolean = false

        object StringMarshaller : MethodDescriptor.Marshaller<String> {
            override fun stream(value: String) = value.byteInputStream()
            override fun parse(stream: java.io.InputStream) = stream.readBytes().decodeToString()
        }
    }

    private class RecordingHandler : ServerCallHandler<String, String> {
        var called = false
        var userInContext: net.stewart.auth.AuthUser? = null
        override fun startCall(
            call: ServerCall<String, String>,
            headers: Metadata,
        ): ServerCall.Listener<String> {
            called = true
            userInContext = GRPC_AUTH_USER_KEY.get()
            return object : ServerCall.Listener<String>() {}
        }
    }

    private fun metadata(vararg pairs: Pair<String, String>): Metadata {
        val md = Metadata()
        for ((k, v) in pairs) {
            md.put(Metadata.Key.of(k, Metadata.ASCII_STRING_MARSHALLER), v)
        }
        return md
    }

    private fun interceptor(
        bearerToken: String? = "good-token",
        cookieToken: String? = "good-cookie",
        unauthenticated: Set<String> = emptySet(),
        gate: ((net.stewart.auth.AuthUser, String) -> Status?)? = null,
    ) = AuthGrpcInterceptor(
        GrpcAuthConfig(
            bearerAuthenticator = bearerToken?.let { good -> { t -> if (t == good) user else null } },
            cookieAuthenticator = cookieToken?.let { good -> { t -> if (t == good) user else null } },
            cookieName = "session",
            unauthenticatedMethods = unauthenticated,
            gate = gate,
        )
    )

    @Test
    fun `no credentials is unauthenticated`() {
        val call = FakeCall("pkg.Svc/Do")
        val handler = RecordingHandler()
        interceptor().interceptCall(call, metadata(), handler)
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus?.code)
        assertFalse(handler.called)
    }

    @Test
    fun `valid bearer token reaches the handler with user in context`() {
        val call = FakeCall("pkg.Svc/Do")
        val handler = RecordingHandler()
        interceptor().interceptCall(call, metadata("authorization" to "Bearer good-token"), handler)
        assertNull(call.closedStatus)
        assertTrue(handler.called)
        assertEquals(user, handler.userInContext)
    }

    @Test
    fun `valid session cookie reaches the handler`() {
        val call = FakeCall("pkg.Svc/Do")
        val handler = RecordingHandler()
        interceptor().interceptCall(call, metadata("cookie" to "session=good-cookie"), handler)
        assertNull(call.closedStatus)
        assertTrue(handler.called)
        assertEquals(user, handler.userInContext)
    }

    @Test
    fun `cookie with mismatched origin is rejected`() {
        val call = FakeCall("pkg.Svc/Do", authorityValue = "example.com")
        val handler = RecordingHandler()
        interceptor().interceptCall(
            call,
            metadata("cookie" to "session=good-cookie", "origin" to "https://evil.example.net"),
            handler,
        )
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus?.code)
        assertFalse(handler.called)
    }

    @Test
    fun `cookie with matching origin passes`() {
        val call = FakeCall("pkg.Svc/Do", authorityValue = "example.com:8443")
        val handler = RecordingHandler()
        interceptor().interceptCall(
            call,
            metadata("cookie" to "session=good-cookie", "origin" to "https://example.com"),
            handler,
        )
        assertNull(call.closedStatus)
        assertTrue(handler.called)
    }

    @Test
    fun `unauthenticated methods skip auth`() {
        val call = FakeCall("pkg.Auth/Login")
        val handler = RecordingHandler()
        interceptor(unauthenticated = setOf("pkg.Auth/Login"))
            .interceptCall(call, metadata(), handler)
        assertNull(call.closedStatus)
        assertTrue(handler.called)
    }

    @Test
    fun `gate rejection closes the call with its status`() {
        val call = FakeCall("pkg.Admin/Do")
        val handler = RecordingHandler()
        interceptor(gate = { _, method ->
            if (method.startsWith("pkg.Admin/")) Status.PERMISSION_DENIED.withDescription("admin only")
            else null
        }).interceptCall(call, metadata("authorization" to "Bearer good-token"), handler)
        assertEquals(Status.Code.PERMISSION_DENIED, call.closedStatus?.code)
        assertFalse(handler.called)
    }

    @Test
    fun `invalid bearer token does not fall through to become authenticated`() {
        val call = FakeCall("pkg.Svc/Do")
        val handler = RecordingHandler()
        interceptor().interceptCall(call, metadata("authorization" to "Bearer wrong"), handler)
        assertEquals(Status.Code.UNAUTHENTICATED, call.closedStatus?.code)
        assertFalse(handler.called)
    }
}
