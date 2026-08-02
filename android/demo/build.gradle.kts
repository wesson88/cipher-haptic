plugins {
    id("com.android.application") version "8.7.2"
    kotlin("android") version "1.9.24"
}

// 调音台 —— 主文档 B.9 要求的"马达调音台单页 App"。
// ⚠️ 它【不参与库的发布产物】：AAR 只来自 :library。
//
// 刻意零第三方依赖（不用 appcompat / material / compose），全部用 framework View
// 手写。理由与库一致：这个 demo 的价值是【真机上验证库本身】，多引一个 UI 框架
// 就多一层"是库的问题还是框架的问题"的干扰。
android {
    namespace = "com.cipherlex.haptic.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cipherlex.haptic.demo"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(project(":library"))
}
