pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        // Only the checksum-verified macOS CI job provides this local Maven
        // mirror. It avoids transient 504s on the official Kotlin compiler
        // artifact redirect; ordinary builds retain their existing repositories.
        System.getenv("STARLIT_CI_KOTLIN_ARTIFACT_MIRROR")?.takeIf { it.isNotBlank() }?.let { mirror ->
            exclusiveContent {
                forRepository {
                    maven {
                        name = "StarlitVerifiedCiKotlinCompiler"
                        url = uri(mirror)
                    }
                }
                filter {
                    includeModule("org.jetbrains.kotlin", "kotlin-compiler-embeddable")
                }
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":composeApp")
include(":androidApp")
include(":tvApp")
include(":desktopSentry")
if (!System.getProperty("os.name").contains("win", ignoreCase = true)) {
    include(":composeMediaPlayer")
    project(":composeMediaPlayer").projectDir = file("vendor/compose-media-player/mediaplayer")
}
