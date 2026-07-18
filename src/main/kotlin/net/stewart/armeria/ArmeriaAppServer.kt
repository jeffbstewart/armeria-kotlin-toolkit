package net.stewart.armeria

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.file.HttpFile
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.ServerInterceptors
import org.slf4j.LoggerFactory

/**
 * Armeria-based application server: gRPC (native proto for programmatic
 * clients and gRPC-Web for browser Connect-ES clients — no proxy),
 * annotated HTTP services, SPA static assets, and a health endpoint,
 * all on one HTTP/2 port.
 *
 * Extracted from MediaManager's server wiring
 * (github.com/jeffbstewart/MediaManager, MIT), generalized from a
 * hard-coded service list to [AppServerConfig].
 */
class ArmeriaAppServer(private val config: AppServerConfig) {

    private val log = LoggerFactory.getLogger(ArmeriaAppServer::class.java)

    @Volatile
    private var server: Server? = null

    /** The Armeria [Server], available after [start]. */
    val armeriaServer: Server
        get() = server ?: error("server not started")

    /** The actual main port, resolved after [start] (useful with port 0). */
    fun activePort(): Int = armeriaServer.activeLocalPort()

    fun start(): Server {
        check(server == null) { "server already started" }

        val sb = Server.builder().http(config.port)
        config.globalDecorators.forEach { sb.decorator(it) }

        if (config.grpcServices.isNotEmpty()) {
            val grpcBuilder = GrpcService.builder()
                .maxRequestMessageLength(config.maxGrpcRequestBytes)
                // Accept every standard gRPC serialization format Armeria
                // ships — proto-binary for native clients and the gRPC-Web
                // variants for browsers via @connectrpc/connect-web. Same
                // handler code path; Armeria picks the codec from the
                // request's Content-Type.
                //
                // enableUnframedRequests is deliberately left off: a POST
                // without gRPC framing is rejected outright instead of
                // being routed into application code.
                .supportedSerializationFormats(GrpcSerializationFormats.values())
            for (spec in config.grpcServices) {
                val interceptors = config.grpcInterceptors + spec.interceptors
                grpcBuilder.addService(
                    if (interceptors.isEmpty()) spec.service.bindService()
                    else ServerInterceptors.intercept(spec.service, interceptors)
                )
            }
            sb.service(grpcBuilder.build())
        }

        for (spec in config.httpServices) {
            registerAnnotated(sb, spec, extraDecorator = null)
        }

        config.healthPath?.let { path ->
            sb.service(path) { _, _ ->
                HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "OK")
            }
        }

        config.singlePageApp?.let { spa -> registerSinglePageApp(sb, spa) }

        if (config.internalPort > 0) {
            sb.http(config.internalPort)
            val internalOnly = internalOnlyDecorator(config.internalPort)
            for (spec in config.internalHttpServices) {
                registerAnnotated(sb, spec, extraDecorator = internalOnly)
            }
        }

        config.customizer(sb)

        val started = sb.build()
        started.start().join()
        server = started
        if (config.internalPort > 0) {
            log.info(
                "Armeria server started on port {} (gRPC + HTTP) and {} (internal)",
                started.activeLocalPort(), config.internalPort
            )
        } else {
            log.info("Armeria server started on port {} (h2c)", started.activeLocalPort())
        }
        return started
    }

    fun stop() {
        server?.stop()?.join()
        server = null
        log.info("Armeria server stopped")
    }

    private fun registerAnnotated(
        sb: com.linecorp.armeria.server.ServerBuilder,
        spec: HttpServiceSpec,
        extraDecorator: DecoratingHttpServiceFunction?,
    ) {
        // Builder API (.annotatedService().decorator().build()) avoids the
        // varargs ambiguity of annotatedService(Object, Object...), which
        // treats decorator functions as exception handlers.
        var builder = sb.annotatedService()
        extraDecorator?.let { builder = builder.decorator(it) }
        spec.decorators.forEach { builder = builder.decorator(it) }
        builder.useBlockingTaskExecutor(spec.blocking).build(spec.service)
    }

    private fun registerSinglePageApp(
        sb: com.linecorp.armeria.server.ServerBuilder,
        spa: SinglePageAppConfig,
    ) {
        require(spa.urlPrefix.startsWith("/") && spa.urlPrefix.endsWith("/")) {
            "singlePageApp.urlPrefix must start and end with '/': ${spa.urlPrefix}"
        }
        if (!spa.dir.toFile().exists()) {
            log.info("SPA directory not found ({}), SPA routes disabled", spa.dir.toAbsolutePath())
            return
        }
        val fileService = FileService.of(spa.dir)
        val indexService = HttpFile.of(spa.dir.resolve("index.html")).asService()

        // Serve real files when they exist; otherwise fall back to
        // index.html so the SPA's client-side router handles the route.
        sb.serviceUnder(spa.urlPrefix) { ctx, req ->
            val mappedPath = ctx.mappedPath().removePrefix("/")
            val file = spa.dir.resolve(mappedPath)
            if (mappedPath.isNotEmpty() && file.toFile().isFile) {
                fileService.serve(ctx, req)
            } else {
                indexService.serve(ctx, req)
            }
        }
        if (spa.redirectRoot) {
            sb.service("/") { _, _ -> HttpResponse.ofRedirect(spa.urlPrefix) }
        }
        log.info("SPA enabled at {} from {}", spa.urlPrefix, spa.dir.toAbsolutePath())
    }

    /** Rejects requests that did not arrive on [allowedPort] with 404. */
    private fun internalOnlyDecorator(allowedPort: Int) =
        DecoratingHttpServiceFunction { delegate, ctx, req ->
            if (ctx.localAddress().port == allowedPort) {
                delegate.serve(ctx, req)
            } else {
                HttpResponse.of(HttpStatus.NOT_FOUND)
            }
        }
}
