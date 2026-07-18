# armeria-kotlin-toolkit

Armeria server wiring for Kotlin applications: **gRPC + gRPC-Web + REST
+ SPA on one HTTP/2 port**, plus documented templates for the
protobuf → Kotlin / TypeScript (Connect-ES) codegen pipeline.

Extracted from [MediaManager](https://github.com/jeffbstewart/MediaManager)'s
proven server wiring, generalized from a hard-coded service list to
configuration. Sibling of
[h2-kotlin-toolkit](https://github.com/jeffbstewart/h2-kotlin-toolkit)
(embedded DB) and
[auth-kotlin-toolkit](https://github.com/jeffbstewart/auth-kotlin-toolkit)
(sessions/JWT/passkeys).

## Features

- **One port serves everything** — gRPC with every serialization format
  Armeria ships: proto-binary for native clients, and **gRPC-Web for
  browser Connect-ES clients with no Envoy/proxy**. Plus annotated REST
  services, SPA static assets with `index.html` fallback for
  client-side routing, and a health endpoint.
- **Blocking-executor default** for HTTP handlers, so DB/file I/O can
  never stall the Netty event loop.
- **Interceptors and decorators as configuration** — global gRPC
  interceptors, per-service interceptors, global HTTP decorators
  (access log, security headers), per-service decorators (auth).
- **Internal-only port** — LAN-only monitoring endpoints (`/metrics`)
  that 404 on the internet-facing port.
- **Escape hatch** — a `customizer: (ServerBuilder) -> Unit` for
  anything the config doesn't model (meter registry, TLS, timeouts).
- **Codegen templates** — `codegen/` documents the full
  `.proto` → Kotlin stubs + typed TS Connect-ES client pipeline where
  drift is a compile error on both sides, enforced in CI.

## Quick start

**Composite build (recommended for development):** clone as a sibling
directory, then in `settings.gradle.kts`:

```kotlin
includeBuild("../armeria-kotlin-toolkit")
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.stewart:armeria-kotlin-toolkit:0.1.0")
}
```

**Maven Local:** `./gradlew publishToMavenLocal` here, then depend on
`net.stewart:armeria-kotlin-toolkit:0.1.0` with `mavenLocal()` in your
repositories.

## Usage

```kotlin
import net.stewart.armeria.*
import java.nio.file.Path

val server = ArmeriaAppServer(
    AppServerConfig(
        port = 9090,
        grpcServices = listOf(
            GrpcServiceSpec(AccountsGrpcService()),
            GrpcServiceSpec(AdminGrpcService(), interceptors = listOf(adminOnly)),
        ),
        grpcInterceptors = listOf(loggingInterceptor, authInterceptor),
        httpServices = listOf(
            HttpServiceSpec(ImagesHttpService(), decorators = listOf(authDecorator)),
        ),
        singlePageApp = SinglePageAppConfig(dir = Path.of("spa"), urlPrefix = "/app/"),
        healthPath = "/healthz",
        globalDecorators = listOf(accessLogDecorator),
        internalPort = 8081,
        internalHttpServices = listOf(HttpServiceSpec(MetricsHttpService())),
        customizer = { sb -> sb.meterRegistry(registry) },
    )
).start()
```

The browser client talks gRPC-Web straight to this port:

```ts
import { createGrpcWebTransport } from '@connectrpc/connect-web';

const transport = createGrpcWebTransport({
  baseUrl: '/',            // same origin as the SPA the server serves
  credentials: 'include',  // HttpOnly session cookie rides along
});
```

See [`codegen/README.md`](codegen/README.md) for the full
`.proto` → Kotlin + TypeScript pipeline, including the CI job that
makes contract drift a build failure.

## License

MIT. Includes code derived from
[MediaManager](https://github.com/jeffbstewart/MediaManager) (MIT,
same copyright holder).
