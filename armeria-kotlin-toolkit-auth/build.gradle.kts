plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
}

group = "net.stewart"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // The server core (re-exports armeria, armeria-grpc, grpc-api, slf4j).
    api(project(":"))
    // AuthUser is the identity type; the config factories wire
    // SessionService/JwtService directly.
    api("net.stewart:auth-kotlin-toolkit:0.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
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
