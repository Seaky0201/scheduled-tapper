pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        // [本机改动] dl.google.com 与 plugins.gradle.org 在本机不可达（直连被重置、走代理 000）。
        // 阿里云镜像已验证包含全部所需构件，故只使用镜像，不再声明 google()/gradlePluginPortal()，
        // 否则 Gradle 仍会对不可达域名发起请求导致解析失败。
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        mavenCentral()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // 与 flutter create (3.47.5) 模板一致：AGP 9.1.0 / Kotlin 2.4.0
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}

include(":app")
