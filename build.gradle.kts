import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
    id("com.google.protobuf") version "0.9.4"
}

group = "net.stewart"
version = "0.1.0"

repositories {
    mavenCentral()
}

val armeriaVersion = "1.38.0"
val grpcVersion = "1.80.0"
val grpcKotlinVersion = "1.5.0"
val protobufVersion = "4.34.1"

dependencies {
    api("com.linecorp.armeria:armeria:$armeriaVersion")
    api("com.linecorp.armeria:armeria-grpc:$armeriaVersion")
    api("io.grpc:grpc-api:$grpcVersion")
    api("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    // The test gRPC service is generated from src/test/proto (see the
    // protobuf block below) — the same toolchain documented in codegen/.
    testImplementation("io.grpc:grpc-stub:$grpcVersion")
    testImplementation("io.grpc:grpc-protobuf:$grpcVersion")
    testImplementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    testImplementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
