plugins {
    id("com.android.application")
    // Flutter Gradle 插件必须在 Android/Kotlin 插件之后应用
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.dsh.tapper"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.dsh.tapper"
        // 目标机型荣耀60 出厂 Android 11(API 30)，可升级 Android 13(API 33)。
        // minSdk 26 保证 TYPE_APPLICATION_OVERLAY 自 API 26 起可用，覆盖该机型。
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // 用 debug 签名，方便直接安装验证（正式发布请替换为自己的签名）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 只是抛异常的桩实现，单测中必须换成真实实现，
    // 否则 Config 的 JSON 往返测试没有意义。
    testImplementation("org.json:json:20240303")
}
