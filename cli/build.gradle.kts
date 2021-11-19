plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":admin-part"))
    implementation(project(":api"))
    implementation("com.github.ajalt:clikt:2.7.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}

val main = "com.epam.drill.plugins.test2code.cli.CliKt"

application {
    mainClassName = main
}

val jarDeps by configurations.creating {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
}

tasks {
    shadowJar {
        archiveBaseName.set("parsing-cli.jar")
        destinationDirectory.set(file("$buildDir/shadowLibs"))
        isZip64 = true
        configurations = listOf(jarDeps)
        manifest.attributes["Main-Class"] = main
    }
}

publishing {
    publications {
        create<MavenPublication>("test2codeZip") {
            artifact(tasks.shadowJar.get())
        }
    }
}
