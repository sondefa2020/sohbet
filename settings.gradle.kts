pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Matrix SDK'nın Android paketleri GitHub sürüm dosyaları olarak yayınlanıyor.
        ivy {
            url = uri("https://github.com/matrix-org/matrix-rust-components-kotlin/releases/download")
            patternLayout { artifact("sdk-[revision]/[artifact]-[revision](-[classifier])(.[ext])") }
            metadataSources { gradleMetadata() }
            content { includeModule("org.matrix.rustcomponents", "sdk-android") }
        }
    }
}
rootProject.name = "Bayx"
include(":app")
