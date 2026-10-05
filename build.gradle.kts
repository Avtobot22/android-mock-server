plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("android") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("com.android.application") version "8.13.2" apply false
}

subprojects {
    group = "dev.androidmock"
    version = "0.1.0-SNAPSHOT"
}
