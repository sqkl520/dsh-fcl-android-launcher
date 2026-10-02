package com.dsh.ui.shell

import android.content.Context
import android.view.View
import android.widget.Toast
import com.dsh.core.DeepSeekApi
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.ActivityDshSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（外壳 tab 4）。由原 `DshSettingsActivity` 迁移为 [DshPageUI]。
 *
 * 设置是**按实例**的（API Key / 名称 / 模型 / profile / 端口）。作为全局 tab，
 * 它作用于**当前选中的实例**（[DshInstances.selectedId]，无则取第一个）。
 * 实例列表页点"更多→设置"仍会打开独立的 `DshSettingsActivity`（针对具体那一个实例）。
 * 本页随选中实例变化自动重绑；无任何实例时显示空态。
 */
class DshSettingsUI(context: Context) : DshPageUI(context, R.layout.activity_dsh_settings) {

    private val binding = ActivityDshSettingsBinding.bind(contentView)
    private var boundId: String? = null

    override fun onCreate() {
        super.onCreate()
        DshPaths.loadPaths(context)
        DshInstances.init()

        binding.modelSpinner.setItems(DshInstance.MODELS)
        binding.profileSpinner.setItems(DshInstance.PROFILES)

        // 跟随"选中实例 + 实例列表"变化重绑（例如实例被删/被改名）
        scope.launch {
            DshInstances.instances.collectLatest { rebind() }
        }
        scope.launch {
            DshInstances.selectedId.collectLatest { rebind() }
        }
    }

    /** 解析当前应展示的实例并重绑；无实例则空态 */
    private fun rebind() {
        val inst = (DshInstances.selectedId.value?.let { DshInstances.byId(it) })
            ?: DshInstances.instances.value.firstOrNull()
        if (inst == null) {
            boundId = null
            binding.root.visibility = View.GONE
            Toast.makeText(context, R.string.dsh_settings_no_instance, Toast.LENGTH_SHORT).show()
            return
        }
        binding.root.visibility = View.VISIBLE
        // 已经绑定同一实例且用户可能正在编辑时，避免打断输入（仅首次或切换实例时重填）
        if (boundId == inst.id) return
        boundId = inst.id
        bindInstance(inst)
        bindActions(inst)
        refreshCredentialStatus(inst)
        showDiskUsage(inst)
    }

    private fun bindInstance(inst: DshInstance) {
        binding.nameInput.setText(inst.name)
        binding.modelSpinner.setSelection(DshInstance.MODELS.indexOf(inst.model).coerceAtLeast(0))
        binding.profileSpinner.setSelection(DshInstance.PROFILES.indexOf(inst.profile).coerceAtLeast(0))
        binding.portInput.setText(if (inst.port > 0) inst.port.toString() else "")
        binding.portInput.hint = context.getString(R.string.dsh_port_auto)
        binding.instancePath.text = DshPaths.instanceDir(inst.id).absolutePath
        binding.versionText.text = context.getString(R.string.dsh_settings_version, inst.dshVersion ?: "-")
    }

    private fun bindActions(inst: DshInstance) {
        binding.btnSave.setOnClickListener {
            val name = binding.nameInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(context, R.string.dsh_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val portText = binding.portInput.text?.toString()?.trim().orEmpty()
            val port = if (portText.isEmpty()) 0 else portText.toIntOrNull()
            if (port == null || port < 0 || port > 65535) {
                Toast.makeText(context, R.string.dsh_port_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DshInstances.updateConfig(
                inst.id,
                name = name,
                model = binding.modelSpinner.getSelectedItem()?.toString() ?: DshInstance.MODELS.first(),
                profile = binding.profileSpinner.getSelectedItem()?.toString() ?: DshInstance.PROFILES.first(),
                port = port
            )
            Toast.makeText(context, R.string.dsh_saved, Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) {
                Toast.makeText(context, R.string.dsh_key_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnSaveKey.isEnabled = false
            scope.launch {
                val ok = withContext(Dispatchers.IO) { DshCredentials.save(context, inst.id, key) }
                binding.btnSaveKey.isEnabled = true
                if (ok) {
                    binding.apiKeyInput.setText("")
                    Toast.makeText(context, R.string.dsh_key_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, R.string.dsh_key_save_failed, Toast.LENGTH_LONG).show()
                }
                refreshCredentialStatus(inst)
            }
        }

        binding.btnTestKey.setOnClickListener {
            val typed = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            binding.btnTestKey.isEnabled = false
            binding.keyStatus.text = context.getString(R.string.dsh_key_testing)
            scope.launch {
                val key = withContext(Dispatchers.IO) {
                    typed.ifEmpty { DshCredentials.load(context, inst.id) ?: "" }
                }
                if (key.isEmpty()) {
                    binding.btnTestKey.isEnabled = true
                    Toast.makeText(context, R.string.dsh_key_empty, Toast.LENGTH_SHORT).show()
                    refreshCredentialStatus(inst)
                    return@launch
                }
                val result = DeepSeekApi.verifyKey(key)
                binding.btnTestKey.isEnabled = true
                binding.keyStatus.text = when (result) {
                    is DeepSeekApi.VerifyResult.Ok ->
                        context.getString(R.string.dsh_key_ok, result.models.joinToString(", "))
                    is DeepSeekApi.VerifyResult.Invalid ->
                        context.getString(R.string.dsh_key_invalid, result.message)
                    is DeepSeekApi.VerifyResult.Unknown ->
                        context.getString(R.string.dsh_key_unknown, result.message)
                }
            }
        }

        binding.btnClearKey.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dsh_key_clear_title)
                .setMessage(R.string.dsh_key_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dsh_action_clear) { _, _ ->
                    DshCredentials.clear(context, inst.id)
                    refreshCredentialStatus(inst)
                }
                .show()
        }

        binding.btnVerifyRuntime.setOnClickListener {
            binding.btnVerifyRuntime.isEnabled = false
            Toast.makeText(context, R.string.dsh_verify_running, Toast.LENGTH_SHORT).show()
            scope.launch {
                val report = withContext(Dispatchers.IO) { DshBootstrap.verify(context) }
                binding.btnVerifyRuntime.isEnabled = true
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dsh_verify_title)
                    .setMessage(report.detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }

        binding.btnReinstall.setOnClickListener {
            val version = inst.dshVersion
            if (version == null) {
                Toast.makeText(context, R.string.dsh_reinstall_pick_version, Toast.LENGTH_LONG).show()
            } else {
                DshServices.installer(context).install(inst, version)
                Toast.makeText(context, context.getString(R.string.dsh_install_started, version), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLogs.setOnClickListener { /* 日志是独立 tab，这里不再跳转 */ }
        binding.btnLogs.visibility = View.GONE

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dsh_delete_title)
                .setMessage(context.getString(R.string.dsh_delete_message, inst.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.dsh_action_delete) { _, _ ->
                    DshCredentials.clear(context, inst.id)
                    DshInstances.delete(inst.id)
                    boundId = null   // 触发重绑到下一个实例或空态
                }
                .show()
        }
    }

    private fun refreshCredentialStatus(inst: DshInstance) {
        binding.keyStatus.setText(R.string.dsh_key_checking)
        scope.launch {
            val (label, args) = withContext(Dispatchers.IO) {
                when (val status = DshCredentials.status(context, inst.id)) {
                    is DshCredentials.Status.Ok -> {
                        val shown = DshCredentials.mask(DshCredentials.load(context, inst.id))
                        R.string.dsh_key_present to arrayOf<Any>(shown)
                    }
                    is DshCredentials.Status.None ->
                        R.string.dsh_key_absent to emptyArray<Any>()
                    is DshCredentials.Status.Unreadable ->
                        R.string.dsh_key_unreadable to arrayOf<Any>(status.reason)
                }
            }
            binding.keyStatus.text = if (args.isEmpty()) context.getString(label) else context.getString(label, *args)
        }
    }

    private fun showDiskUsage(inst: DshInstance) {
        binding.diskUsage.text = context.getString(R.string.dsh_size_calculating)
        DshInstances.diskUsageAsync(inst.id) { bytes ->
            host(binding) { binding.diskUsage.text = context.getString(R.string.dsh_disk_usage, DshPaths.formatSize(bytes)) }
        }
    }

    /** diskUsageAsync 回调在工作线程，切回主线程更新 UI */
    private inline fun host(b: ActivityDshSettingsBinding, crossinline block: () -> Unit) {
        b.root.post { block() }
    }
}
