pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "My Application"
include(":app")
include(":baselineprofile")
include(":ffmpeg-decoder-downmix")

// SmartTube's YouTube engine (MediaServiceCore, MIT), used by Kable Killers Music
// (ported from Nuvio One, GPL-3.0). See mediaservicecore/LICENSE-*.
// Nuvio One's VPN routing rules (plain Java), used by the ported Live TV.
include(":vpn-core")
include(":mediaservicecore:mediaserviceinterfaces")
include(":mediaservicecore:youtubeapi")
include(":mediaservicecore:sharedutils")
include(":mediaservicecore:commons-io-2.8.0")
include(":mediaservicecore:j2v8")
