pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/central") // central mirror: avoids 429 throttling
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/central") // central mirror: avoids 429 throttling
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SparkTube"
include(":app")
include(":ytauth")
