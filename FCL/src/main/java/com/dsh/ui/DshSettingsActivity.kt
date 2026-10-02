package com.dsh.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.dsh.core.DeepSeekApi
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshLogBus
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshSettingsBinding
import com.dsh.ui.shell.DshMainActivity
import com.dsh.ui.shell.DshShellHost
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实例设置页（**本次新增**）。
 *
 * ## 为什么必须有这一页
 * 改造前的启动器**没有任何地方能录入 API Key**：`DshCredentials.storeEncrypted()` 写好了却无人调用，
 * 于是实例永远拿不到 `DEEPSEEK_API_KEY`，端到端流程根本走不通（只能靠手工往文件里塞 key）。
 * 这是最严重的"使用逻辑"缺口，本页补齐，并顺带把实例名/模型/profile/端口这些原来写死在代码里、
 * 用户改不了的配置暴露出来。
 *
 * 页内能力：
 * - API Key：录入（Keystore 加密存储）、显示脱敏值、清除、**测试连接**（区分"密钥无效"和"网络问题"）
 * - 实例名 / 模型（deepseek-flash、deepseek-v4-pro）/ profile（web、headless）/ 端口（0=自动）
 * - 运行环境信息：安装路径、占用体积、凭据状态
 * - 操作：运行时自检、重新安装、查看日志、删除实例
 */
class DshSettingsActivity : FCLActivity() {

    private lateinit var binding: ActivityDshSettingsBinding
    private var instanceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDshSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DshPaths.loadPaths(this)
        DshInstances.init()
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: DshInstances.selectedId.value

        val inst = instanceId?.let { DshInstances.byId(it) }
        if (inst == null) {
            Toast.makeText(this, R.string.dsh_instance_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupModelSpinner()
        setupProfileSpinner()
        bindInstance(inst)
        bindActions(inst)
        refreshCredentialStatus(inst)
        showDiskUsage(inst)
    }

    private fun setupModelSpinner() {
        binding.modelSpinner.setItems(DshInstance.MODELS)
    }

    private fun setupProfileSpinner() {
        binding.profileSpinner.setItems(DshInstance.PROFILES)
    }

    private fun bindInstance(inst: DshInstance) {
        binding.nameInput.setText(inst.name)
        val modelIdx = DshInstance.MODELS.indexOf(inst.model).coerceAtLeast(0)
        binding.modelSpinner.setSelection(modelIdx)
        val profileIdx = DshInstance.PROFILES.indexOf(inst.profile).coerceAtLeast(0)
        binding.profileSpinner.setSelection(profileIdx)
        binding.portInput.setText(if (inst.port > 0) inst.port.toString() else "")
        binding.portInput.hint = getString(R.string.dsh_port_auto)
        binding.instancePath.text = DshPaths.instanceDir(inst.id).absolutePath
        binding.versionText.text = getString(
            R.string.dsh_settings_version, inst.dshVersion ?: "-"
        )
    }

    private fun bindActions(inst: DshInstance) {
        binding.btnSave.setOnClickListener {
            val name = binding.nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.dsh_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val portText = binding.portInput.text?.toString()?.trim().orEmpty()
            val port = if (portText.isEmpty()) 0 else portText.toIntOrNull()
            if (port == null || port < 0 || port > 65535) {
                Toast.makeText(this, R.string.dsh_port_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DshInstances.updateConfig(
                inst.id,
                name = name,
                model = binding.modelSpinner.getSelectedItem()?.toString() ?: DshInstance.MODELS.first(),
                profile = binding.profileSpinner.getSelectedItem()?.toString() ?: DshInstance.PROFILES.first(),
                port = port
            )
            Toast.makeText(this, R.string.dsh_saved, Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(this, R.string.dsh_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 加密落盘要跑 Keystore（首次还会生成主密钥）+ 写文件，不能在主线程做
            binding.btnSaveKey.isEnabled = false
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { DshCredentials.save(this@DshSettingsActivity, inst.id, key) }
                binding.btnSaveKey.isEnabled = true
                if (ok) {
                    // 明文立刻从输入框里抹掉，只留脱敏展示
                    binding.apiKeyInput.setText("")
                    Toast.makeText(this@DshSettingsActivity, R.string.dsh_key_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@DshSettingsActivity, R.string.dsh_key_save_failed, Toast.LENGTH_LONG).show()
                }
                refreshCredentialStatus(inst)
            }
        }

        binding.btnTestKey.setOnClickListener {
            val typed = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            binding.btnTestKey.isEnabled = false
            binding.keyStatus.text = getString(R.string.dsh_key_testing)
            lifecycleScope.launch {
                // 读回明文同样要过 Keystore 解密，放 IO
                val key = withContext(Dispatchers.IO) {
                    typed.ifEmpty { DshCredentials.load(this@DshSettingsActivity, inst.id) ?: "" }
                }
                if (key.isEmpty()) {
                    binding.btnTestKey.isEnabled = true
                    Toast.makeText(this@DshSettingsActivity, R.string.dsh_key_empty, Toast.LENGTH_SHORT).show()
                    refreshCredentialStatus(inst)
                    return@launch
                }
                val result = DeepSeekApi.verifyKey(key)
                binding.btnTestKey.isEnabled = true
                binding.keyStatus.text = when (result) {
                    is DeepSeekApi.VerifyResult.Ok ->
                        getString(R.string.dsh_key_ok, result.models.joinToString(", "))
                    is DeepSeekApi.VerifyResult.Invalid ->
                        getString(R.string.dsh_key_invalid, result.message)
                    is DeepSeekApi.VerifyResult.Unknown ->
                        getString(R.string.dsh_key_unknown, result.message)
                }
            }
        }

        binding.btnClearKey.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dsh_key_clear_title)
                .setMessage(R.string.dsh_key_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dsh_action_clear) { _, _ ->
                    DshCredentials.clear(this, inst.id)
                    refreshCredentialStatus(inst)
                }
                .show()
        }

        binding.btnVerifyRuntime.setOnClickListener {
            binding.btnVerifyRuntime.isEnabled = false
            Toast.makeText(this, R.string.dsh_verify_running, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) { DshBootstrap.verify(this@DshSettingsActivity) }
                binding.btnVerifyRuntime.isEnabled = true
                MaterialAlertDialogBuilder(this@DshSettingsActivity)
                    .setTitle(R.string.dsh_verify_title)
                    .setMessage(report.detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        binding.btnReinstall.setOnClickListener {
            val version = inst.dshVersion
            if (version == null) {
                Toast.makeText(this, R.string.dsh_reinstall_pick_version, Toast.LENGTH_LONG).show()
                startActivity(DshMainActivity.intentForTab(this, DshShellHost.TAB_DOWNLOAD))
            } else {
                DshServices.installer(this).install(inst, version)
                Toast.makeText(this, getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLogs.setOnClickListener {
            startActivity(DshMainActivity.intentForTab(this, DshShellHost.TAB_LOGS))
        }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dsh_delete_title)
                .setMessage(getString(R.string.dsh_delete_message, inst.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dsh_action_delete) { _, _ ->
                    DshCredentials.clear(this, inst.id)
                    DshInstances.delete(inst.id)
                    finish()
                }
                .show()
        }
    }

    /**
     * 刷新凭据状态。
     * Keystore 解密（[DshCredentials.status]/[DshCredentials.load]）在部分机型上要 100ms+，
     * 放主线程会在进入设置页时明显掉帧，所以整体挪到 IO，再切回主线程更新文字。
     */
    private fun refreshCredentialStatus(inst: DshInstance) {
        binding.keyStatus.setText(R.string.dsh_key_checking)
        lifecycleScope.launch {
            val (label, args) = withContext(Dispatchers.IO) {
                when (val status = DshCredentials.status(this@DshSettingsActivity, inst.id)) {
                    is DshCredentials.Status.Ok -> {
                        val shown = DshCredentials.mask(
                            DshCredentials.load(this@DshSettingsActivity, inst.id)
                        )
                        R.string.dsh_key_present to arrayOf<Any>(shown)
                    }
                    is DshCredentials.Status.None ->
                        R.string.dsh_key_absent to emptyArray<Any>()
                    is DshCredentials.Status.Unreadable ->
                        R.string.dsh_key_unreadable to arrayOf<Any>(status.reason)
                }
            }
            binding.keyStatus.text = if (args.isEmpty()) getString(label) else getString(label, *args)
        }
    }

    private fun showDiskUsage(inst: DshInstance) {
        binding.diskUsage.text = getString(R.string.dsh_size_calculating)
        DshInstances.diskUsageAsync(inst.id) { bytes ->
            runOnUiThread {
                binding.diskUsage.text = getString(R.string.dsh_disk_usage, DshPaths.formatSize(bytes))
            }
        }
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "dsh_instance_id"
    }
}
