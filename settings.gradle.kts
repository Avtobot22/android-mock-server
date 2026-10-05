pluginManagement {
	repositories {
		google()
		gradlePluginPortal()
		mavenCentral()
	}
}
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
	}
}
rootProject.name = "android-mock-server"
include(":mock-core", ":mock-okhttp", ":mock-ktor", ":sample")
