// CipherHaptic · Android 侧
//
// 模块切分不是为了好看，是为了让「IR 之后禁止决策」由【编译器】而非 grep 保证：
//   core     —— 纯 Kotlin/JVM。语义解析、决策管线、降级、IR、FSM。零 Android 依赖。
//   library  —— com.android.library。engine 接缝 + 平台桥。依赖 core。
//
// core 里 `PipelineContext` / `HardwareClass` / `globalScale` 全部 internal，
// 只有 `ResolvedWaveform` 跨模块公开 —— library 在【语言层面】就看不见那些标识符，
// CI 规则 9（engine 目录禁止出现 hardwareClass/category/globalScale）从静态 grep
// 升级为编译期强制。见工程骨架 §一 原则 4。
//
// Swift 侧将来按同样的边界切：CipherHapticCore（平台中立）+ CipherHaptic（Apple only）。

pluginManagement {
    repositories {
        // AGP 只在 Google 的 Maven 仓，不在 Maven Central —— 缺 google() 时
        // com.android.library 的 marker 解析不到，报错文案却只提 "plugin not found"
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "cipher-haptic"

include(":core")
include(":library")
