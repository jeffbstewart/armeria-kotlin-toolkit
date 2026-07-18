# The proto codegen pipeline (documented template)

Copy these pieces into a consuming project. They are deliberately
templates, not a Gradle plugin: three small transparent blocks beat
build-machinery indirection at this scale.

The invariant the pipeline guarantees: **`.proto` is the single source
of truth, generated code is never committed, and a hallucinated field
or RPC is a compile error on both sides — enforced in CI by
regenerating and compiling both sides on every build.**

## 1. Server side (Kotlin) — Gradle

Kotlin/Java stubs regenerate on every Gradle build, so they can never
be stale. Add to `build.gradle.kts` (this repo's own build uses the
same block for its test protos — see it working there):

```kotlin
import com.google.protobuf.gradle.id

plugins {
    id("com.google.protobuf") version "0.9.4"
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.34.1" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.80.0" }
        id("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { id("grpc"); id("grpckt") }
            task.builtins { id("kotlin") }
        }
    }
}

// Put .proto files at the repo root in proto/:
sourceSets { main { proto { srcDir("proto") } } }
```

Dependencies: `io.grpc:grpc-stub`, `io.grpc:grpc-protobuf`,
`io.grpc:grpc-kotlin-stub`, `com.google.protobuf:protobuf-kotlin`.

## 2. Client side (TypeScript) — gen-proto.mjs

Copy [`gen-proto.mjs`](gen-proto.mjs) into your web app, set
`PROTO_FILES`, and wire the npm `prebuild`/`prestart` hooks per the
header comment. Gitignore the output directory.

## 3. CI — compile both sides

```yaml
  codegen-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: corretto, java-version: '25' }
      - run: ./gradlew build --no-daemon        # regenerates + compiles Kotlin stubs
      - uses: actions/setup-node@v4
        with: { node-version: '22' }
      - run: npm ci
        working-directory: web-app
      - run: npm run build                      # prebuild regenerates TS, then compiles
        working-directory: web-app
```

Because nothing generated is committed, there is no "sync" state to
check — the build either compiles against fresh output or fails.
