pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
	}
}
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		mavenCentral()
	}
}
rootProject.name = "android-mock-server"
include(":mock-core", ":mock-okhttp", ":mock-okhttp-server", ":mock-ktor")
