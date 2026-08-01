pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
			url = uri(
				"https://jitpack.io"
			)
		}
    }
}

rootProject.name = providers.gradleProperty("plugin.id").get()
include("plugins-ksp")
