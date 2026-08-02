pluginManagement {
    repositories {
        mavenLocal()
        maven(url = "https://artifactory.papermc.io/artifactory/snapshots")
        gradlePluginPortal()
        mavenLocal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "minecraft-plugins"


include(":libraries:FreshLib")

include(":projects:ChunkRevive")
include(":projects:ChunkRevive:api")
include(":projects:ChunkRevive:nms:api")
include(":projects:ChunkRevive:nms:v1_21_11")
include(":projects:ChunkRevive:nms:v26_1_2")
include(":projects:ChunkRevive:nms:v26_2")
