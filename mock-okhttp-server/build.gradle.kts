plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":mock-core"))
    api("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.retrofit2:retrofit:3.0.0")
}
tasks.test { useJUnitPlatform() }
