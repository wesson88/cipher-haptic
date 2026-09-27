// swift-tools-version:5.9
// Package.swift 放仓库根：SwiftPM 按 Git URL 引用时只认根目录清单。源码在 ios/ 下。
import PackageDescription

let package = Package(
    name: "CipherHaptic",
    platforms: [
        // 骨架 §二 / §五.3：iOS 13+ 硬门槛；macOS 仅用于跑纯逻辑与 facade（fake gateway）单测
        .iOS(.v13),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "CipherHapticCore", targets: ["CipherHapticCore"]),
        .library(name: "CipherHaptic", targets: ["CipherHaptic"]),
    ],
    targets: [
        // 纯 Swift：IR / SpecLoader / 降级 / 决策管线 / 抢占 / FSM / coalescer / 调度 / IR→iOS 翻译
        // （镜像 android/core）。runtime.min.json 由 tools/extract.py 同步写入 Resources
        .target(
            name: "CipherHapticCore",
            path: "ios/Sources/CipherHapticCore",
            resources: [.copy("Resources/runtime.min.json")]
        ),
        // 平台层：facade + PlaybackHandle + Core Haptics / UIKit 回退网关（镜像 android/library）
        .target(name: "CipherHaptic", dependencies: ["CipherHapticCore"], path: "ios/Sources/CipherHaptic"),
        .testTarget(name: "CipherHapticCoreTests", dependencies: ["CipherHapticCore"], path: "ios/Tests/CipherHapticCoreTests"),
        // facade 测试走 fake gateway，macOS 主机即可跑；CI 另在 iOS 模拟器上跑一遍全部
        .testTarget(name: "CipherHapticTests", dependencies: ["CipherHaptic", "CipherHapticCore"], path: "ios/Tests/CipherHapticTests"),
    ]
)
