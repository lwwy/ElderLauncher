plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.elderlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.elderlauncher"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.7"
    }

    // 固定签名：保证每次构建（换机器/换环境）证书一致，
    // 否则覆盖安装会被系统判定为"另一个应用"，默认桌面/电话/无障碍设置全部丢失。
    signingConfigs {
        create("elderSign") {
            storeFile = file("<redacted>")
            storePassword = "<redacted>"
            keyAlias = "<redacted>"
            keyPassword = "<redacted>"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("elderSign")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("elderSign")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // Material Design 3（Material You）
    implementation("com.google.android.material:material:1.12.0")
}
