pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "seam"

// Modules are added when their milestone starts (see docs/09-repo-structure.md for the full list)
include(":app")
include(":core:protocol")
include(":core:crypto")
