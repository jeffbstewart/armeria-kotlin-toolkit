package net.stewart.armeria

import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.ServerBuilder
import io.grpc.BindableService
import io.grpc.ServerInterceptor
import java.nio.file.Path

/**
 * A gRPC service plus the interceptors applied to it, after the global
 * [AppServerConfig.grpcInterceptors]. Interceptor ordering follows
 * grpc-java's [io.grpc.ServerInterceptors.intercept] semantics: the last
 * interceptor in the combined list is invoked first.
 */
data class GrpcServiceSpec(
    val service: BindableService,
    val interceptors: List<ServerInterceptor> = emptyList(),
)

/**
 * An Armeria annotated HTTP service and how to mount it.
 *
 * [blocking] defaults to true so handlers doing DB queries or file I/O
 * can never stall the Netty event loop; set it false only for handlers
 * that are provably non-blocking.
 */
data class HttpServiceSpec(
    val service: Any,
    val decorators: List<DecoratingHttpServiceFunction> = emptyList(),
    val blocking: Boolean = true,
)

/**
 * Single-page-app static serving with `index.html` fallback so the
 * SPA's client-side router owns unknown paths under [urlPrefix]. When
 * [dir] does not exist at startup the SPA routes are skipped (the
 * development mode: run the SPA dev server instead).
 */
data class SinglePageAppConfig(
    val dir: Path,
    val urlPrefix: String = "/app/",
    /** Also redirect `/` to [urlPrefix]. */
    val redirectRoot: Boolean = true,
)

/**
 * Configuration for [ArmeriaAppServer]: one HTTP/2 port serving gRPC
 * (every serialization format Armeria ships, so browser gRPC-Web
 * clients work without a proxy), optional annotated HTTP services, an
 * optional SPA, and a health endpoint — plus an optional LAN-only
 * internal port for monitoring endpoints that must stay off the
 * internet-facing port.
 */
data class AppServerConfig(
    /** Main port. 0 lets the OS pick (useful in tests). */
    val port: Int,
    val grpcServices: List<GrpcServiceSpec> = emptyList(),
    /** Interceptors applied to every gRPC service, before per-service ones. */
    val grpcInterceptors: List<ServerInterceptor> = emptyList(),
    val maxGrpcRequestBytes: Int = 16 * 1024 * 1024,
    val httpServices: List<HttpServiceSpec> = emptyList(),
    val singlePageApp: SinglePageAppConfig? = null,
    /** Mounted as a trivial 200 "OK" text endpoint; null disables it. */
    val healthPath: String? = "/healthz",
    /** Cross-cutting decorators on every HTTP route (access log, security headers, ...). */
    val globalDecorators: List<DecoratingHttpServiceFunction> = emptyList(),
    /** Extra port for LAN-only endpoints; 0 disables it. */
    val internalPort: Int = 0,
    /** Services that respond only on [internalPort] (404 elsewhere). */
    val internalHttpServices: List<HttpServiceSpec> = emptyList(),
    /**
     * Escape hatch applied to the [ServerBuilder] after everything above,
     * for concerns the config doesn't model (meter registry, TLS,
     * timeouts). Runs last, so it can override earlier settings.
     */
    val customizer: (ServerBuilder) -> Unit = {},
)
