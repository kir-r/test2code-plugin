plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("kotlinx-atomicfu")
    id("com.github.johnrengelman.shadow")
}

val jarDeps by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
}
configurations.implementation {
    extendsFrom(jarDeps)
}

dependencies {
    jarDeps(project(":agent-api"))
    jarDeps("org.jacoco:org.jacoco.core")
    jarDeps("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm") { isTransitive = false }

    implementation(kotlin("stdlib"))
    implementation(project(":experimental"))
    jarDeps(project(":experimental"))

    //provided by drill runtime
    implementation("com.epam.drill:drill-agent-part")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    compileOnly("org.jetbrains.kotlinx:atomicfu")

    testImplementation(project(":tests"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:atomicfu")
}

tasks {
    test {
        useJUnitPlatform()
        systemProperty("plugin.feature.drealtime", false)
    }

    fun com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar.commonConfig() {
        isZip64 = true
        destinationDirectory.set(file("$buildDir/shadowLibs"))
        archiveFileName.set("agent-part.jar")
        configurations = listOf(jarDeps)
        dependencies {
            exclude(
                "/META-INF/**",
                "/*.class",
                "/*.html"
            )
        }
        listOf(
            "org.objectweb.asm",
            "org.jacoco.core",
            "kotlinx.collections.immutable"
        ).forEach { relocate(it, "${rootProject.group}.test2code.shadow.$it") }
    }

    shadowJar {
        commonConfig()
        relocate("kotlin", "kruntime")
        relocate("kotlinx", "kruntimex")
    }
    //TODO remove after fixes in test framework
    val shadowJarTest by registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class)
    shadowJarTest {
        group = "shadow"
        from(jar)
        commonConfig()
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.freeCompilerArgs = listOf(
            "-Xuse-experimental=kotlin.ExperimentalStdlibApi",
            "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-Xuse-experimental=kotlinx.coroutines.FlowPreview",
            "-Xuse-experimental=kotlinx.coroutines.InternalCoroutinesApi",
            "-Xuse-experimental=kotlinx.coroutines.ObsoleteCoroutinesApi"
        )
    }
}
