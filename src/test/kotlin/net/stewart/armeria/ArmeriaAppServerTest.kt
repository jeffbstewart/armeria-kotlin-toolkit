package net.stewart.armeria

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.annotation.Get
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import net.stewart.armeria.testproto.EchoGrpcKt
import net.stewart.armeria.testproto.EchoReply
import net.stewart.armeria.testproto.EchoRequest

internal class EchoService : EchoGrpcKt.EchoCoroutineImplBase() {
    override suspend fun say(request: EchoRequest): EchoReply =
        EchoReply.newBuilder().setMessage("echo: ${request.message}").build()
}

internal class CountingInterceptor : ServerInterceptor {
    val calls = AtomicInteger()
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        calls.incrementAndGet()
        return next.startCall(call, headers)
    }
}

internal class MetricsStub {
    @Get("/metrics")
    fun metrics(): String = "metrics-ok"
}

class ArmeriaAppServerTest {

    private var appServer: ArmeriaAppServer? = null

    @AfterTest
    fun tearDown() {
        appServer?.stop()
        appServer = null
    }

    private fun start(config: AppServerConfig): ArmeriaAppServer {
        val s = ArmeriaAppServer(config)
        s.start()
        appServer = s
        return s
    }

    @Test
    fun `grpc round trip with global interceptor`() {
        val interceptor = CountingInterceptor()
        val server = start(
            AppServerConfig(
                port = 0,
                grpcServices = listOf(GrpcServiceSpec(EchoService())),
                grpcInterceptors = listOf(interceptor),
            )
        )
        val stub = GrpcClients.newClient(
            "http://127.0.0.1:${server.activePort()}/",
            EchoGrpcKt.EchoCoroutineStub::class.java,
        )
        val reply = runBlocking { stub.say(EchoRequest.newBuilder().setMessage("hi").build()) }
        assertEquals("echo: hi", reply.message)
        assertEquals(1, interceptor.calls.get())
    }

    @Test
    fun `health endpoint responds ok`() {
        val server = start(AppServerConfig(port = 0, healthPath = "/healthz"))
        val res = WebClient.of("http://127.0.0.1:${server.activePort()}")
            .get("/healthz").aggregate().join()
        assertEquals(HttpStatus.OK, res.status())
        assertEquals("OK", res.contentUtf8())
    }

    @Test
    fun `spa serves files and falls back to index html`() {
        val spaDir = Files.createTempDirectory("spa-test")
        Files.writeString(spaDir.resolve("index.html"), "<html>index</html>")
        Files.writeString(spaDir.resolve("main.js"), "console.log('js')")

        val server = start(AppServerConfig(port = 0, singlePageApp = SinglePageAppConfig(dir = spaDir)))
        val client = WebClient.of("http://127.0.0.1:${server.activePort()}")

        val asset = client.get("/app/main.js").aggregate().join()
        assertEquals(HttpStatus.OK, asset.status())
        assertEquals("console.log('js')", asset.contentUtf8())

        val route = client.get("/app/some/client/route").aggregate().join()
        assertEquals(HttpStatus.OK, route.status())
        assertEquals("<html>index</html>", route.contentUtf8())

        val root = client.get("/").aggregate().join()
        assertTrue(root.status().isRedirection, "expected redirect, got ${root.status()}")
        assertEquals("/app/", root.headers().get(com.linecorp.armeria.common.HttpHeaderNames.LOCATION))
    }

    @Test
    fun `internal services respond only on the internal port`() {
        val internalPort = freePort()
        val server = start(
            AppServerConfig(
                port = 0,
                internalPort = internalPort,
                internalHttpServices = listOf(HttpServiceSpec(MetricsStub())),
            )
        )
        val internal = WebClient.of("http://127.0.0.1:$internalPort")
            .get("/metrics").aggregate().join()
        assertEquals(HttpStatus.OK, internal.status())
        assertEquals("metrics-ok", internal.contentUtf8())

        val mainPort = server.armeriaServer.activePorts().keys
            .map { it.port }.first { it != internalPort }
        val external = WebClient.of("http://127.0.0.1:$mainPort")
            .get("/metrics").aggregate().join()
        assertEquals(HttpStatus.NOT_FOUND, external.status())
    }

    @Test
    fun `customizer runs against the server builder`() {
        var ran = false
        start(AppServerConfig(port = 0, customizer = { ran = true }))
        assertTrue(ran)
    }

    private fun freePort(): Int =
        java.net.ServerSocket(0).use { it.localPort }
}
