rootProject.name = "sustc"

include(
    "sustc-api",
    "sustc-runner",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
