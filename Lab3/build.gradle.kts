plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val integrationTestImplementation by configurations.creating {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.creating {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
    testImplementation("io.mockk:mockk:1.13.13")

    integrationTestImplementation(kotlin("test"))
    integrationTestImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    integrationTestImplementation("io.kotest:kotest-assertions-core:5.9.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "org.example.MainKt"
}

sourceSets {
    val integrationTest by creating {
        java.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += output + compileClasspath
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests."
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    mustRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}
