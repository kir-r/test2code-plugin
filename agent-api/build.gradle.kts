plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    `maven-publish`
    java
}

kotlin {
    sourceSets.commonMain {
        dependencies {
            compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-core")
        }
    }

    jvm{
        compilations["main"].defaultSourceSet {
            dependencies {
                implementation(project(":experimental"))
            }
        }
    }
}
