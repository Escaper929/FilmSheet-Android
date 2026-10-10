//  Gradle 仓库与镜像配置。
//
//  为什么需要它：这台机器上 plugins.gradle.org 的证书链在 WorkBuddy 注入的
//  代理下校验失败（PKIX path building failed），而 GitHub 直连超时。
//  阿里云是 AGP / Kotlin / Compose 全套的完整镜像，用它做唯一仓库即可。
//
//  代价写清楚：镜像同步有延迟。某个版本刚发布时可能还没同步上，
//  届时把这里的镜像注释掉、回退到 google() / mavenCentral() 即可。

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "FilmSheet-Android"

include(":app")
