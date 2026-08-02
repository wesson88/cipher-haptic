plugins {
    id("com.android.library") version "8.7.2"
    kotlin("android") version "1.9.24"
}

android {
    namespace = "com.cipherlex.haptic"
    compileSdk = 34

    defaultConfig {
        minSdk = 29                    // VibrationEffect 下限（工程骨架 §五.3）
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    // 平台调用被收窄到 VibratorGateway 一个接口，其余全是纯逻辑，
    // 故绝大部分测试能在 JVM 上跑，不需要 Robolectric 或真机。
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(project(":core"))

    // 仅测试期。运行时依赖只有 core + kotlin-stdlib —— 无第三方，无 native，无 ABI 分包
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")   // JVM 单测里补一份;Android 平台自带
}
