package com.tungsten.fcl.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.app.ActivityOptionsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mio.util.ImageUtil
import com.dsh.fcl.androidlauncher.databinding.ActivitySplashBinding
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.util.Logging
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * 启动页（LAUNCHER）。
 *
 * [外壳改造] 本 app 是 **DeepSeek Harness (dsh) 启动器**，不再需要 FCL 那套 Minecraft
 * 运行时门禁（LWJGL / Caciocavallo / JRE 8·17·21·25 / JNA）与 MC 主界面，
 * 因此启动流程简化为：直接进入 dsh 实例列表。
 *
 * 也不再申请外部存储权限：dsh 的 rootfs / 实例全部在 App 私有目录
 * （filesDir / cacheDir）内，无需 MANAGE_EXTERNAL_STORAGE。
 *
 * 路径初始化（FCLPath / DshPaths）**均不在此处**：已上提到 [com.tungsten.fcl.FCLApp]
 * 的 onCreate，见该文件内 [M-01] 注释。本页只**消费** FCLPath.LOG_DIR，不负责初始化 ——
 * 若在此处加回 loadPaths()，会重新制造"必须先经过启动页"的隐式依赖（通知栏
 * PendingIntent 冷启动会绕过本页），未来任一处改参数另一处不受影响。
 *
 * 日志：[M-02] fcl.log 写入 App 私有目录（FCLPath.LOG_DIR = context.getDir("log", 0)），
 * 随 App 卸载清理；不写外部存储 —— 存储权限已移除。注：dsh 自身运行日志走
 * DshLogBus / <filesDir>/dsh/logs/runtime.log，与此处 fcl.log 是两套。
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : FCLActivity() {

    lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ImageUtil.loadInto(
            binding.background, ThemeEngine.getInstance().getTheme().getBackground(this)
        )
        init()
    }

    private fun init() {
        lifecycleScope.launch {
            val scriptsOutdated = withContext(Dispatchers.IO) {
                // 注意：此处不再调用 FCLPath.loadPaths()。初始化已上提到 FCLApp.onCreate，
                // 见该文件内 [M-01] 注释。若在此处加回，会造成两处初始化，未来任一处改参数
                // 另一处不受影响 —— 这正是本次要消除的隐式依赖。此处仅消费 FCLPath.LOG_DIR。
                Logging.start(Paths.get(FCLPath.LOG_DIR))
                // BL-214（fix10）：只读比对脚本版本（读两个几字节的文件，不解压、不弹框）。
                // 放在同一个 IO 线程里顺序做：它比 Logging.start 快一个量级，不值得再开协程；
                // 而对外壳用户"多等一次磁盘 stat"是不可感知的。任何异常都吞成 false（不改道）。
                checkScriptsOutdated()
            }
            enterDsh(scriptsOutdated)
        }
    }

    /**
     * BL-214（fix10）：**只读**比对 APK 内与本机的脚本版本，过期则本次改道准备页。
     *
     * 为什么必须在启动链上做：首启门禁 `KEY_SETUP_DONE` 一次置真永真，而 `DshBootstrap.install()`
     * 的唯一生产调用点在准备页 —— 存量用户升级后直接进主壳，脚本 9 → 10 的重解压永不发生
     * （BL-191 的新日志对老用户永远看不到，RELEASES 里"首启会重解压"的承诺也落空）。
     *
     * 只在 IO 线程读两个几字节的文件，不解压、不弹框、失败一律按"不过期"处理（见
     * [com.dsh.core.DshBootstrap.scriptsOutdated]）。已完成准备的用户才查 —— 首次使用本来
     * 就进准备页，再查一遍没有意义（也避免首次安装多读一次 assets）。
     */
    private fun checkScriptsOutdated(): Boolean {
        if (!com.dsh.ui.setup.DshSetupActivity.isCompleted(this)) return false
        val outdated = runCatching { com.dsh.core.DshBootstrap.scriptsOutdated(this@SplashActivity) }
            .getOrDefault(false)
        if (!outdated) return false
        // 日志说清"哪一版落后"：排障时只看这一行就知道该不该重解压（版本号本身不含敏感信息）
        val (disk, asset) = com.dsh.core.DshBootstrap.scriptsVersionPair(this@SplashActivity)
        com.dsh.core.DshLogBus.append(
            "[bootstrap] scripts version ${disk ?: "(missing)"} -> ${asset ?: "(unknown)"}, " +
                "entering setup to re-extract"
        )
        return true
    }

    /**
     * 进入 dsh 启动器主外壳。不做任何 MC 单例初始化（渲染器 / Java / 控制器 / 配置）。
     *
     * @param scriptsOutdated APK 内脚本比本机新：改道准备页（它会在 `beginPrepare()` 里因
     *        `DshBootstrap.isReady()` 为 false 而只重解 scripts 子项，几秒级，不动 rootfs）。
     */
    private fun enterDsh(scriptsOutdated: Boolean) {
        // ★ 首启门禁：运行环境没准备好就先走「准备运行环境」页（一次性）。
        //   准备完成前不进主外壳 —— 首页因此不必再挂"底座未就绪"横幅（那种横幅还会把
        //   真实失败原因覆盖掉，真机已踩过）。
        // ★ BL-214：已完成的用户也要查一次脚本版本 —— "完成过"是历史事实，"脚本还是新的"
        //   是当次事实，两者不能互相替代。
        val target = if (com.dsh.ui.setup.DshSetupActivity.isCompleted(this) && !scriptsOutdated) {
            com.dsh.ui.shell.DshMainActivity::class.java
        } else {
            com.dsh.ui.setup.DshSetupActivity::class.java
        }
        startActivity(
            Intent(this@SplashActivity, target),
            ActivityOptionsCompat.makeCustomAnimation(this@SplashActivity, 0, 0).toBundle()
        )
        finish()
    }
}
