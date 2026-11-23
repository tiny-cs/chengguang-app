// 城光 — 根 Gradle 配置
// 版本与麦禾 VPN 保持一致（已在 GitHub Actions 上验证可构建）：
// AGP 8.5.2 / Kotlin 2.0.21 / Gradle 8.7 / JDK 17
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
    }
}
rootProject.name = "chengguang"
include(":app")
