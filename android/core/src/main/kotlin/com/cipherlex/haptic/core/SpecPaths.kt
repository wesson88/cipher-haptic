package com.cipherlex.haptic.core

import java.io.File

/**
 * 测试期定位仓库根目录下的 `spec/` 产物。
 *
 * 各测试原本各写一遍 `File(System.getProperty("user.dir")).parentFile.parentFile`——
 * `parentFile` 可空，写法本身就带隐患（library 模块开 -Werror 后当场报
 * "Unsafe use of a nullable receiver"）；而且模块层级一变就要改多处。
 *
 * ⚠️ 仅供测试使用。**产品路径是 `SpecLoader.fromResources()`**（读内嵌资源），
 * 绝不从文件系统读 —— APK / IPA 里没有 `spec/` 目录。
 */
object SpecPaths {

    /** 从当前工作目录向上找到含 `spec/runtime.min.json` 的仓库根。 */
    val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "spec/runtime.min.json").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("找不到仓库根（向上未见 spec/runtime.min.json）—— 先跑 python tools/extract.py")
    }

    fun runtimeJson(): String = File(repoRoot, "spec/runtime.min.json").readText()

    fun goldenJson(): String = File(repoRoot, "spec/golden.json").readText()
}
