plugins {
    application
}

configurations.all { resolutionStrategy.cacheDynamicVersionsFor(5, TimeUnit.MINUTES) }

val drillAdminVersion: String by rootProject

dependencies {
    runtimeOnly("com.epam.drill:admin-core:$drillAdminVersion:all@jar")
}

val appJvmArgs = listOf(
    "-server",
    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5006",
    "-Djava.awt.headless=true",
    "-XX:+UseG1GC",
    "-XX:+UseStringDeduplication",
    "-Xms128m",
    "-Xmx2g"
)

application {
    mainClassName = "io.ktor.server.netty.EngineMain"
    applicationDefaultJvmArgs = appJvmArgs
}

tasks {
    val cleanData by registering(Delete::class) {
        group = "build"
        delete("work", "distr")
    }

    clean {
        dependsOn(cleanData)
    }

    val syncDistro by registering(Sync::class) {
        from(rootProject.tasks.distZip)
        into("distr/adminStorage")
    }

    (run) {
        dependsOn(syncDistro)
        environment("DRILL_DEVMODE", true)
        environment("DRILL_DEFAULT_PACKAGES", "org/springframework/samples/petclinic,com/epam,package")
        environment("DRILL_PLUGINS_REMOTE_ENABLED", false)
        environment("analytic.disable", true)
    }
}
