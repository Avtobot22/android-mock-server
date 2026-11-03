plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":mock-core"))
    api("io.ktor:ktor-client-core-jvm:3.6.1")
    api("io.ktor:ktor-client-mock-jvm:3.6.1")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-logging-jvm:3.6.1")
}
tasks.test { useJUnitPlatform() }
