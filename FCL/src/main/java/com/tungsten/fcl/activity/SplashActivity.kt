package com.tungsten.fcl.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.core.app.ActivityOptionsCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.mio.util.ImageUtil
import com.tungsten.fcl.databinding.ActivitySplashBinding
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.util.Logging
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * 启动页（LAUNCHER）。
 *
 * [外壳改造] 本 app 是 **DeepSeek Harness (dsh) 启动器**，不再需要 FCL 那套 Minecraft
 * 运行时门禁（LWJGL / Caciocavallo / JRE 8·17·21·25 / JNA）与 MC 主界面，
 * 因此启动流程简化为：初始化 dsh 路径 → 直接进入 dsh 实例列表。
 *
 * 也不再申请外部存储权限：dsh 的 rootfs / 实例 / 日志全部在 App 私有目录
 * （filesDir / cacheDir）内，无需 MANAGE_EXTERNAL_STORAGE。
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
            async(Dispatchers.IO) {
                FCLPath.loadPaths(this@SplashActivity)
                Logging.start(Paths.get(FCLPath.LOG_DIR))
            }.await()
            enterDsh()
        }
    }

    /** 进入 dsh 启动器界面。不做任何 MC 单例初始化（渲染器 / Java / 控制器 / 配置）。 */
    private fun enterDsh() {
        startActivity(
            Intent(this@SplashActivity, com.dsh.ui.DshInstancesActivity::class.java),
            ActivityOptionsCompat.makeCustomAnimation(this@SplashActivity, 0, 0).toBundle()
        )
        finish()
    }
}
