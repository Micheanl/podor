pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "podor"
include(":shared", ":desktopApp")
if (providers.gradleProperty("enableAndroid").orNull == "true") include(":androidApp")

