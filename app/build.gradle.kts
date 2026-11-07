import java.util.Properties

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
        versionCode = 29
        versionName = "1.2.15"
    }

    // 签名密钥与密码放在仓库根目录的 keystore.properties（已被 .gitignore 排除，绝不提交）。
    // 全新克隆 / CI 没有该文件时，debug 使用系统默认 debug 签名、release 产出未签名包，
    // 构建不会因缺少密钥而失败；本地配置了密钥则证书保持不变，可继续覆盖安装。
    val keystoreProps = Properties()
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }
    val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !keystoreProps.getProperty(it).isNullOrBlank() }

    signingConfigs {
        if (hasReleaseKey) {
            create("elderSign") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
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
            signingConfigs.findByName("elderSign")?.let { signingConfig = it }
        }
        debug {
            isMinifyEnabled = false
            signingConfigs.findByName("elderSign")?.let { signingConfig = it }
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
    // Material Design 3（Material You）
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}
