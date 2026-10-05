plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "dev.androidmock.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.androidmock.sample"
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":mock-core"))
    implementation(project(":mock-okhttp"))
    implementation(project(":mock-ktor"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
