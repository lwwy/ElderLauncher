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
        versionCode = 11
        versionName = "1.1.0"
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
            // R8：代码压缩 + 混淆 + 资源压缩（配合 proguard-rules.pro）
            isMinifyEnabled = true
            isShrinkResources = true
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

    // 让本地单元测试能读到真实资源（strings/layout），配合 Robolectric 做无设备验证
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    // Android 12+ 启动画面（SplashScreen API）
    implementation("androidx.core:core-splashscreen:1.0.1")
    // Material Design 3（Material You）
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}
