plugins {
	kotlin("jvm")
	kotlin("plugin.serialization")
	`java-library`
}
kotlin {
	jvmToolchain(17)
}
dependencies {
	api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	testImplementation(kotlin("test"))
}
tasks.test {
	useJUnitPlatform()
}
