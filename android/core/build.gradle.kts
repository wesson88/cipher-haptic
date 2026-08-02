plugins {
    kotlin("jvm") version "1.9.24"
}

// ⚠️ 纯 Kotlin/JVM，【不引入任何 Android 依赖】。
// 这是「双端镜像同构」的公共面：这里的每个类在 Swift 侧都有对应物，
// 且两边共用同一份 golden 用例（工程骨架 §六.6 的双端行为等价测试）。
//
// 也【不引入 YAML 解析器】：内嵌产物是 spec/bundle.json，用 JDK/Android 都有的
// 方式解析。给触觉库塞 snakeyaml 是实打实的成本（方法数、与宿主版本冲突），
// 而且 `on:` 那次已经证明 YAML 1.1 的隐式类型转换会同时坑双端。

dependencies {
    // org.json 在 Android 平台上由 android.jar 提供（零体积成本），
    // 故这里 compileOnly；纯 JVM 单测里才真的需要一份实现。
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

// spec/runtime.min.json 构建时拷入 resources（不是 bundle.json —— 后者含 parity 与双端镜像,运行时不需要） —— 单向注入，绝不反向依赖（骨架原则 3）。
// 注意方向：spec/ 是生成产物，core 只读它；core 绝不回写 spec/。
val copySpec by tasks.registering(Copy::class) {
    from(rootProject.file("../spec/runtime.min.json"))
    into(layout.buildDirectory.dir("generated/spec"))
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("generated/spec"))

tasks.named("processResources") { dependsOn(copySpec) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
