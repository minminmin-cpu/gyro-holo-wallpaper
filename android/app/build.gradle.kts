plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.holowallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.holowallpaper"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 签名信息只从环境变量读取；keystore 与密码绝不入库。
    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_FILE")?.let {
                storeFile = file(it)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 只有提供 keystore 环境变量时才签名；本地无 keystore 也能编译出 unsigned APK，
            // CI 通过 Secrets 注入 KEYSTORE_FILE 后再签名。
            System.getenv("KEYSTORE_FILE")?.let {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // M1 不依赖 AndroidX / Compose：纯 framework API + Kotlin stdlib，
    // 减少离线/CI 构建时的依赖解析失败面。
}

dependencies { /* 暂无外部依赖 */ }
