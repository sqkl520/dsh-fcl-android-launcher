package com.dsh

import com.dsh.core.DshBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启解压的**空间门槛**单测（纯 JVM，不需要真机/Android）。
 *
 * 为什么单独钉成断言（K21）：空间门槛是"看不见的拦路者"——数字错了不会报错，只会把本来够用的
 * 设备拦在首启页，或放一个必然写满磁盘的设备进去解到一半失败。它又最适合纯函数化：
 * [DshBootstrap.requiredFreeBytes] 只依赖两个实测常量，这里把"首装/升级各需要多少可用空间"的
 * 两个口径逐一钉死，连同"升级不该按两份 rootfs 的可用空间要求"这条回归也一起锁住。
 *
 * 实测来源：docs/ROOTFS.md §9「瘦身后（0.1.3 起）」——
 *   · rootfs 解压后 apparent-size 合计 904,335,360 B（≈862 MiB）
 *   · assets/dsh/rootfs/version = debian-bookworm-arm64-node22-dsh0.1.6-alpha.2-slim1（slim1 瘦身版）
 * ★ BL-072（R15）：门槛常量已从 apparent-size 改为**块对齐保守值 960 MiB**
 *   （实测 4K 块对齐 961,220,608 B + 目录开销 ≈936 MiB，见
 *   `docs/reports/round12/A6-bootstrap.md` A6-3；门槛要拦"注定写满"，宁多勿少）。
 *   testFordebug 与常量同源同改 —— 改常量必须同步改这里与实测记录。
 *
 * 运行：./gradlew :FCL:testFordebugUnitTest --tests "com.dsh.DshBootstrapSpaceTest"
 *   （或仓库的 run-tests.sh，自动扫描本类）
 */
class DshBootstrapSpaceTest {

    private companion object {
        /** [DshBootstrap.requiredFreeBytes] 依赖的 rootfs 门槛常量（BL-072：960 MiB 保守值） */
        const val ROOTFS_EXTRACTED = 960L * 1024 * 1024

        /** 一个实例的 node_modules 预留（= DshBootstrap.INSTANCE_NODE_MODULES_BYTES，600 MiB） */
        const val NODE_MODULES = 600L * 1024 * 1024

        /** 瘦身**前**的旧升级门槛（rootfs×2 + node_modules = 3900 MiB），用于回归对照 */
        const val OLD_UPGRADE_THRESHOLD = 3900L * 1024 * 1024
    }

    /** 常量与 DshBootstrap 内部口径一致：node_modules 预留必须是同一个数 */
    @Test
    fun nodeModulesReserveMatchesBootstrapConstant() {
        assertEquals(NODE_MODULES, DshBootstrap.INSTANCE_NODE_MODULES_BYTES)
    }

    /** 升级/重装：只需 1 份 rootfs 的可用空间（旧 rootfs 已占盘，不计入可用空间） */
    @Test
    fun upgradeNeedsExactlyOneRootfs() {
        assertEquals(ROOTFS_EXTRACTED, DshBootstrap.requiredFreeBytes(replacing = true))
    }

    /** 首装：1 份 rootfs（.tmp）+ 1 份 node_modules（随后首个实例 npm install 用） */
    @Test
    fun firstInstallNeedsRootfsPlusNodeModules() {
        assertEquals(
            ROOTFS_EXTRACTED + NODE_MODULES,
            DshBootstrap.requiredFreeBytes(replacing = false)
        )
    }

    /** 两种口径的关系：首装恰好比升级多一份 node_modules（而不是多一份 rootfs） */
    @Test
    fun firstInstallExceedsUpgradeByNodeModulesOnly() {
        val first = DshBootstrap.requiredFreeBytes(replacing = false)
        val upgrade = DshBootstrap.requiredFreeBytes(replacing = true)
        assertTrue("首装门槛应高于升级门槛", first > upgrade)
        assertEquals(
            "首装与升级之差应恰为一份 node_modules（升级不额外要一份 rootfs）",
            NODE_MODULES,
            first - upgrade
        )
    }

    /**
     * 回归：升级门槛**绝不**回到"两份 rootfs 的可用空间"。
     * 旧实现按 rootfs×2 + node_modules ≈ 3.9 GB 算，把 1.1 GB 可用的真机直接误拦——
     * 这正是 K21 要消灭的虚高门槛。
     */
    @Test
    fun upgradeNeverRequiresTwoRootfsOfFreeSpace() {
        val upgrade = DshBootstrap.requiredFreeBytes(replacing = true)
        assertTrue(
            "升级门槛不应达到两份 rootfs（旧 bug）",
            upgrade < 2L * ROOTFS_EXTRACTED
        )
        assertTrue(
            "升级门槛应远低于瘦身前旧门槛 3900 MiB",
            upgrade < OLD_UPGRADE_THRESHOLD
        )
    }

    /**
     * 校准落点：瘦身后首装门槛应落在 ~1.5 GB，低于瘦身前的旧首装门槛（rootfs+node = 2250 MiB），
     * 这样一台清理出 ~1.6 GB 的中低端机才不会被误拦（计划 §6.3 要求真机清出 ≥1.7 GB）。
     */
    @Test
    fun firstInstallThresholdFitsMidRangeDevice() {
        val first = DshBootstrap.requiredFreeBytes(replacing = false)
        assertTrue("首装门槛应低于 1.6 GiB", first < 1600L * 1024 * 1024)
        assertTrue("首装门槛应低于瘦身前旧门槛 2250 MiB", first < 2250L * 1024 * 1024)
    }
}
