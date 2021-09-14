plugins {
    kotlin("jvm")
}


dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jacoco:org.jacoco.core")
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
}
