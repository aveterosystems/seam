// SPDX-License-Identifier: AGPL-3.0-only
pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "seam"

// Modules are added when their milestone starts (see docs/architecture/repo-structure.md for the full list)
include(":app")
include(":core:protocol")
include(":core:crypto")
include(":core:session")
include(":feature:pairing")
include(":feature:status")
