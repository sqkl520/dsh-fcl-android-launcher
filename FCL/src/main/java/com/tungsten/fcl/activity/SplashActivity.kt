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
            // ★ [R31 / s10-eh-07] 本协程原先没有任何异常兜底，`withContext` 里炸一下的后果是
            //   "enterDsh 不被调用" ⇒ 启动页永远停在原地；再加 lifecycleScope 的默认处理器
            //   ⇒ 进程崩溃。两条都是死路。这里的判据只有一条：**无论 IO 阶段怎么失败，
            //   enterDsh 都必须被调用** —— "进了主壳但脚本没装好"远好于"卡在启动页没有任何出口"。
            //
            //   scriptsOutdated 初值 false（= 不因为一次读失败就把用户赶去准备页重解压，
            //   重解压是分钟级的；失败时宁愿按"不过期"处理，用户仍可手动进准备页）。
            var scriptsOutdated = false
            try {
                scriptsOutdated = withContext(Dispatchers.IO) {
                    // 注意：此处不再调用 FCLPath.loadPaths()。初始化已上提到 FCLApp.onCreate，
                    // 见该文件内 [M-01] 注释。若在此处加回，会造成两处初始化，未来任一处改参数
                    // 另一处不受影响 —— 这正是本次要消除的隐式依赖。此处仅消费 FCLPath.LOG_DIR。
                    //
                    // [R31 / s10-eh-06] 返回值接手：null = 磁盘日志可用，非 null = 不可用的原因。
                    // 原先 start() 返回 void，这段"日志到底有没有落盘"的信息在方法内部就丢了。
                    // LOG_DIR 为 null 时 Paths.get 会抛 NPE，所以整段包 runCatching，
                    // 失败降级为"没有文件日志"并把原因持久化（logcat 重启后取不回）。
                    val logFailure = runCatching { Logging.start(Paths.get(FCLPath.LOG_DIR)) }
                        .getOrElse { "Logging.start threw ${it.javaClass.simpleName}: ${it.message}" }
                    if (logFailure != null) {
                        // 落盘到 filesDir/startup-failures.log —— 与 FCLApp 的启动失败登记同一个出口，
                        // 排障时只看一个文件就能知道"这次启动哪几步没成"。
                        com.tungsten.fcl.FCLApp.recordStartupFailure(
                            filesDir, "splashLogging", IllegalStateException(logFailure)
                        )
                    }
                    // BL-214（fix10）：只读比对脚本版本（读两个几字节的文件，不解压、不弹框）。
                    // 放在同一个 IO 线程里顺序做：它比 Logging.start 快一个量级，不值得再开协程；
                    // 而对外壳用户"多等一次磁盘 stat"是不可感知的。任何异常都吞成 false（不改道）。
                    checkScriptsOutdated()
                }
            } catch (t: Throwable) {
                // 记录 + 降级，**不重抛**：重抛等于把"停在启动页/闪退"这个缺陷原样保留。
                com.tungsten.fcl.FCLApp.recordStartupFailure(
                    filesDir, "splashInit", t
                )
            } finally {
                // ★ 降级跳转：无论上面怎么失败都进主壳（失败时按"脚本不过期"走）。
                //   isFinishing/isDestroyed 判断是必需的，不是防御性冗余：lifecycleScope 在
                //   onDestroy 时取消协程，withContext 随之抛 CancellationException → 落到这里。
                //   此时页面已经没了，再 startActivity 等于从已销毁的 Activity 拉起主壳
                //   （用户已按返回/旋转屏幕离开启动页，却被拽回 App）。判据与"进主壳"不冲突：
                //   页面还活着时恒为 false，照常进。
                if (!isFinishing && !isDestroyed) enterDsh(scriptsOutdated)
            }
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
        //
        // ★ [R31 / s10-eh-07] 这一段原先在 runCatching **之外** —— 上面的 scriptsOutdated 包了，
        //   "判定为过期之后"的两行没包，是一次典型的**半包**。后果不成比例：scriptsVersionPair
        //   或 DshLogBus.append 抛一下，就会把"脚本过期、该去准备页重解压"这个**已经算对的结论**
        //   一起丢掉（连带整个启动页走异常路径）。日志是附带产物，绝不能否决结论。
        return runCatching {
            val (disk, asset) = com.dsh.core.DshBootstrap.scriptsVersionPair(this@SplashActivity)
            com.dsh.core.DshLogBus.append(
                "[bootstrap] scripts version ${disk ?: "(missing)"} -> ${asset ?: "(unknown)"}, " +
                    "entering setup to re-extract"
            )
            true
        }.getOrDefault(true) // 注意默认值是 true：结论已定（过期），日志失败不该把它翻成 false
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
