import kotlinx.benchmark.gradle.*
import org.jetbrains.kotlin.allopen.gradle.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.allopen") version "1.4.21"
    id("org.jetbrains.kotlinx.benchmark") version "0.3.0"
    id("kotlinx-atomicfu")
}

configure<AllOpenExtension> {
    annotation("org.openjdk.jmh.annotations.State")
}

configurations {
    all { resolutionStrategy.cacheDynamicVersionsFor(5, TimeUnit.MINUTES) }
}

val drillAdminVersion: String by rootProject
val ktorVersion: String by rootProject
val ktorSwaggerVersion: String by rootProject
val benchmarkVersion: String by rootProject
val kodeinVersion: String by extra

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    testCompileOnly(project(":api"))
    testCompileOnly(project(":agent-api"))
    testImplementation(project(":admin-part"))
    testImplementation(project(":tests"))
    testImplementation(project(":agent-part"))

    testImplementation("com.epam.drill:common")
    testImplementation("com.epam.drill:drill-agent-part")
    testImplementation("com.epam.drill:drill-admin-part")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    testImplementation("org.jetbrains.kotlinx:kotlinx-collections-immutable")

    testImplementation("com.epam.drill:test-framework:$drillAdminVersion") { isChanging = true }
    testImplementation("com.epam.drill:admin-core:$drillAdminVersion") { isChanging = true }

    testImplementation("org.kodein.di:kodein-di-jvm:$kodeinVersion")

    testImplementation("com.epam.drill.ktor:ktor-swagger:$ktorSwaggerVersion")
    testImplementation("org.javassist:javassist:+")

    testImplementation("com.epam.drill:kodux")
    testImplementation("org.jetbrains.xodus:xodus-entity-store")

    testImplementation("org.jacoco:org.jacoco.core")
    testImplementation("org.apache.bcel:bcel:6.3.1")
    testImplementation("org.jetbrains.kotlinx:atomicfu")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.3.2")
    testImplementation("io.mockk:mockk:1.9.3")
}
kotlin {
    sourceSets.main {
        dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:$benchmarkVersion")
        }
    }
}
tasks {
    test {
        useJUnitPlatform()
        systemProperty("plugin.feature.drealtime", false)
    }
}
benchmark {
    configurations {
        named("main") {
            iterationTime = 5
            iterationTimeUnit = "ms"
        }
    }
    targets {
        register("test") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.21"
        }
    }
}

