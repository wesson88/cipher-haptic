package com.cipherlex.haptic.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证【生产路径】`SpecLoader.fromResources()`。
 *
 * 其余测试都按文件路径读 `spec/runtime.min.json`，走的不是产品实际用的那条链路。
 * 而真正打进 APK 的是**构建时拷进 resources 的那一份**——如果 Gradle 的 copySpec
 * 任务坏了、或拷错了文件、或资源没进 jar，前面 4 个测试**一个都不会红**，
 * 而 App 一启动就 crash。
 *
 * 这条链路本身也是回归点：spec 从 bundle.json 换成 runtime.min.json 时，
 * 忘改 copySpec 或忘改 fromResources 的资源名，只有本测试会抓到。
 */
class ResourceEmbedTest {

    @Test
    fun `内嵌资源可加载且内容完整`() {
        val loader = SpecLoader.fromResources()

        // ⚠️ 不写死数字 —— 每加一个语义 token 就要改测试是没必要的摩擦，
        //    而且写死的数字一旦忘改就变成"测试挡着不让加功能"。
        //    真正要验的是【内嵌的那份】与【仓库里的那份】一致，即资源没被截断/拷错。
        val fromRepo = SpecLoader(SpecPaths.runtimeJson()).semanticIds
        assertEquals(fromRepo, loader.semanticIds, "内嵌资源的语义集合应与仓库 spec 一致")
        assertTrue("item.dissolve" in loader.semanticIds)

        // 三层数据都要真的能读出来，不能只是 JSON 解析成功
        val rw = loader.resolve("item.dissolve", HardwareClass.LINEAR_X_FULL)
        assertTrue(rw != null, "内嵌资源应能解析出 IR")
        assertEquals("glitch_dissolve", rw.effectId)
        assertEquals(3, rw.events.size)

        // 迁移表也在同一份资源里
        val table = TransitionTable.from(loader.transitions)
        assertEquals(8, table.states.size, "8 态")
        assertEquals(10, table.events.size, "10 事件")
    }

    @Test
    fun `内嵌产物不含 parity 与双端镜像`() {
        // 把平台差异说明文档打进 App 二进制是不对的，与体积大小无关。
        val raw = SpecLoader::class.java.getResourceAsStream("/runtime.min.json")!!
            .bufferedReader().use { it.readText() }
        assertTrue("\"parity\"" !in raw, "runtime 产物不得含 parity（CI 与人读的东西）")
        assertTrue("ios_core_haptics" !in raw, "runtime 产物应已归一化为 IR 形态")
        assertTrue("timings_ms" !in raw, "runtime 产物不得含 android 镜像（仅供 CI diff）")
        println("内嵌产物 ${raw.toByteArray().size / 1024.0} KB，无 parity / 无镜像 / 已归一化")
    }
}
