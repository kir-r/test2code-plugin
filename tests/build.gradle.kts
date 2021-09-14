plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("kotlinx-atomicfu")
}

val testBuilds = listOf("build1", "build2")
sourceSets {
    testBuilds.forEach { create(it) }
}

val testData: Configuration by configurations.creating {}

configurations {
    all { resolutionStrategy.cacheDynamicVersionsFor(5, TimeUnit.MINUTES) }
    testImplementation {
        extendsFrom(testData)
    }
    testBuilds.forEach {
        named("${it}Implementation") {
            extendsFrom(testData)
        }
    }
}

val drillAdminVersion: String by rootProject
val ktorVersion: String by rootProject
val ktorSwaggerVersion: String by rootProject
val kodeinVersion: String by extra

dependencies {
    implementation(project(":api"))
    implementation(project(":agent-api"))
    implementation(project(":admin-part"))
    implementation(project(":agent-part"))
    implementation(project(":jacoco"))

    implementation("com.epam.drill:common")
    implementation("com.epam.drill:drill-agent-part")
    implementation("com.epam.drill:drill-admin-part")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable")

    implementation("com.epam.drill:test-framework:$drillAdminVersion") { isChanging = true }
    implementation("com.epam.drill:admin-core:$drillAdminVersion") { isChanging = true }

    implementation("org.kodein.di:kodein-di-jvm:$kodeinVersion")

    implementation("com.epam.drill.ktor:ktor-swagger:$ktorSwaggerVersion")
    implementation(ktor("server-test-host"))
    implementation(ktor("auth"))
    implementation(ktor("auth-jwt"))
    implementation(ktor("server-netty"))
    implementation(ktor("locations"))
    implementation(ktor("server-core"))
    implementation(ktor("websockets"))
    implementation(ktor("client-cio"))
    implementation(ktor("serialization"))

    implementation("com.epam.drill:kodux")
    implementation("org.jetbrains.xodus:xodus-entity-store")

    implementation("org.jacoco:org.jacoco.core")
    implementation("org.apache.bcel:bcel:6.3.1")
    implementation("io.vavr:vavr-kotlin:0.10.0") //TODO remove
    implementation("org.jetbrains.kotlinx:atomicfu")

    testImplementation(project(":tests"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotlintest:kotlintest-runner-junit5:3.3.2")
    testImplementation("io.mockk:mockk:1.9.3")
    testImplementation("org.testcontainers:postgresql:1.16.2")

    testData("com.epam.drill:test-data:$drillAdminVersion") { isChanging = true }
}

tasks {
    val testBuildClassesTasks = testBuilds.map { named("${it}Classes") }

    val distrDir = file("distr")

    clean {
        delete(distrDir)
    }

    val prepareDist by registering(Sync::class) {
        from(rootProject.tasks.named("testDistZip"))
        into(distrDir.resolve("adminStorage"))
    }

    val integrationTest by registering(Test::class) {
        description = "Runs the integration tests"
        group = "verification"
        dependsOn(testBuildClassesTasks.toTypedArray())
        dependsOn(prepareDist)
        useJUnitPlatform()
        systemProperty("plugin.config.path", rootDir.resolve("plugin_config.json"))
        systemProperty("drill.plugins.test2code.features.realtime", false)
        mustRunAfter(test)
    }

    check {
        dependsOn(integrationTest)
    }
}

@Suppress("unused")
fun DependencyHandler.ktor(module: String, version: String? = ktorVersion): Any =
    "io.ktor:ktor-$module:${version ?: "+"}"
