plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.myowns3"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("io.javalin:javalin:6.4.0")
    implementation("org.slf4j:slf4j-simple:2.0.16")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("io.javalin:javalin-testtools:6.4.0")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

application {
    mainClass.set("s3.AppKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
