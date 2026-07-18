package net.stewart.armeria.auth

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Get
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import net.stewart.armeria.AppServerConfig
import net.stewart.armeria.ArmeriaAppServer
import net.stewart.armeria.HttpServiceSpec

internal class WhoAmIService {
    @Get("/whoami")
    fun whoami(ctx: ServiceRequestContext): String {
        val user = authUser(ctx) ?: return "nobody"
        return "${user.username}/${ctx.attr(HTTP_AUTH_METHOD_KEY)}"
    }
}

/** End-to-end decorator tests through a real [ArmeriaAppServer]. */
class AuthHttpDecoratorTest {

    private val user = TestUser(username = "alice")
    private var server: ArmeriaAppServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun startServer(config: HttpAuthConfig): WebClient {
        val s = ArmeriaAppServer(
            AppServerConfig(
                port = 0,
                httpServices = listOf(
                    HttpServiceSpec(WhoAmIService(), decorators = listOf(AuthHttpDecorator(config)))
                ),
            )
        )
        s.start()
        server = s
        return WebClient.of("http://127.0.0.1:${s.activePort()}")
    }

    private fun baseConfig() = HttpAuthConfig(
        cookieAuthenticator = { t -> if (t == "good-cookie") user else null },
        cookieName = "session",
        bearerAuthenticator = { t -> if (t == "good-jwt") user else null },
    )

    @Test
    fun `no credentials gets 401`() {
        val client = startServer(baseConfig())
        val res = client.get("/whoami").aggregate().join()
        assertEquals(HttpStatus.UNAUTHORIZED, res.status())
    }

    @Test
    fun `session cookie authenticates`() {
        val client = startServer(baseConfig())
        val res = client.prepare().get("/whoami")
            .header("cookie", "session=good-cookie")
            .execute().aggregate().join()
        assertEquals(HttpStatus.OK, res.status())
        assertEquals("alice/cookie", res.contentUtf8())
    }

    @Test
    fun `bearer token authenticates`() {
        val client = startServer(baseConfig())
        val res = client.prepare().get("/whoami")
            .header("authorization", "Bearer good-jwt")
            .execute().aggregate().join()
        assertEquals(HttpStatus.OK, res.status())
        assertEquals("alice/bearer", res.contentUtf8())
    }

    @Test
    fun `extra resolver runs after cookie and bearer`() {
        val deviceUser = TestUser(id = 2, username = "roku")
        val client = startServer(
            baseConfig().copy(
                extraResolvers = listOf(
                    "device_token" to { ctx, _ ->
                        if (ctx.queryParams().get("key") == "device-key") deviceUser else null
                    }
                )
            )
        )
        val res = client.get("/whoami?key=device-key").aggregate().join()
        assertEquals(HttpStatus.OK, res.status())
        assertEquals("roku/device_token", res.contentUtf8())
    }

    @Test
    fun `no users yet gets 403`() {
        val client = startServer(baseConfig().copy(hasUsers = { false }))
        val res = client.prepare().get("/whoami")
            .header("authorization", "Bearer good-jwt")
            .execute().aggregate().join()
        assertEquals(HttpStatus.FORBIDDEN, res.status())
    }

    @Test
    fun `gate can short-circuit an authenticated request`() {
        val mustChange = TestUser(username = "bob", mustChangePassword = true)
        val client = startServer(
            HttpAuthConfig(
                bearerAuthenticator = { t -> if (t == "good-jwt") mustChange else null },
                gate = { u, _ ->
                    if (u.mustChangePassword) HttpResponse.of(HttpStatus.valueOf(451)) else null
                },
            )
        )
        val res = client.prepare().get("/whoami")
            .header("authorization", "Bearer good-jwt")
            .execute().aggregate().join()
        assertEquals(451, res.status().code())
    }

    @Test
    fun `gate passes a compliant user through`() {
        val client = startServer(
            baseConfig().copy(
                gate = { u, _ ->
                    if (u.mustChangePassword) HttpResponse.of(HttpStatus.valueOf(451)) else null
                }
            )
        )
        val res = client.prepare().get("/whoami")
            .header("authorization", "Bearer good-jwt")
            .execute().aggregate().join()
        assertEquals(HttpStatus.OK, res.status())
        assertEquals("alice/bearer", res.contentUtf8())
    }
}
