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
        // Repositorios de Onyx/Boox (SDK del lápiz). Solo sirven por HTTP.
        maven {
            url = uri("http://repo.boox.com/repository/proxy-public/")
            isAllowInsecureProtocol = true
        }
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SiYuanInk"
include(":app")
