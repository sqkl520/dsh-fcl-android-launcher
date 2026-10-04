package com.dsh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dsh.core.DeepSeekApi
import com.dsh.core.DshBootstrap
import com.dsh.core.DshCredentials
import com.dsh.core.DshInstance
import com.dsh.core.DshInstances
import com.dsh.core.DshPaths
import com.dsh.core.DshServices
import com.dsh.fcl.androidlauncher.R
import com.dsh.fcl.androidlauncher.databinding.UiDshInstanceSettingsBinding
import com.dsh.ui.shell.DshMainActivity
import com.dsh.ui.shell.DshShellHost
import com.mio.dialog.ItemSelectionDialog
import com.mio.ui.adapter.SpacingItemDecoration
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.EditDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实例详情设置页。
 *
 * ## 为什么必须有这一页
 * 改造前的启动器**没有任何地方能录入 API Key**：`DshCredentials.storeEncrypted()` 写好了却无人调用，
 * 于是实例永远拿不到 `DEEPSEEK_API_KEY`，端到端流程根本走不通（只能手工往文件里塞 key）。
 *
 * ## 结构（照 FCL 的版本设置页）
 * FCL 的 `VersionSettingPage` 是 `page_setting_list` + 行式适配器（Switch/Value/Edit/Memory/Icon 行），
 * 本页同构：RecyclerView + [DshInstanceSettingAdapter]（分组 / 当前值行 / 按钮行）+
 * [SpacingItemDecoration]（组内 1dp 细缝并绘制分割线、组间 8dp）+ 位置感知圆角。
 * 文本输入用 FCL 的 [EditDialog]，单选用 FCL 的 [ItemSelectionDialog]。
 *
 * 页内能力（与改造前一致）：
 * - API Key：录入（Keystore 加密存储）、脱敏展示、清除、**测试连接**（区分"密钥无效"与"网络问题"）
 * - 实例名 / 模型 / profile / 端口
 * - 运行环境：已安装版本、占用体积、实例路径（点击复制）
 * - 操作：运行时自检、重新安装、查看日志、删除实例
 */
class DshSettingsActivity : FCLActivity() {

    private lateinit var binding: UiDshInstanceSettingsBinding
    private lateinit var adapter: DshInstanceSettingAdapter

    private var instanceId: String? = null
    private var inst: DshInstance? = null

    /** 凭据状态与体积是异步算出来的，缓存在这里供行绑定取值 */
    private var keyStatusText: String = ""
    private var diskText: String = ""

    private val themeInvalidate = Runnable { binding.settingList.invalidate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = UiDshInstanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DshPaths.loadPaths(this)
        DshInstances.init()
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: DshInstances.selectedId.value

        val found = instanceId?.let { DshInstances.byId(it) }
        if (found == null) {
            Toast.makeText(this, R.string.dsh_instance_gone, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        inst = found

        adapter = DshInstanceSettingAdapter(this, ::onValue, ::onAction)
        binding.settingList.layoutManager = LinearLayoutManager(this)
        val rowSpacing = dp(8)
        binding.settingList.addItemDecoration(
            SpacingItemDecoration(
                rowSpacing,
                { parent, position ->
                    val a = parent.adapter as? DshInstanceSettingAdapter
                    if (a?.isNextInSameGroup(position) == true) dp(1) else rowSpacing
                },
                true,
                { ThemeEngine.getInstance().getTheme().color }
            )
        )
        ThemeEngine.getInstance().registerEvent(binding.settingList, themeInvalidate)
        binding.settingList.adapter = adapter

        keyStatusText = getString(R.string.dsh_key_checking)
        diskText = getString(R.string.dsh_size_calculating)
        submitRows()

        refreshCredentialStatus()
        showDiskUsage()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ===== 行构建 =====

    /** 重建行列表（值通过 lambda 现取，保证总是最新） */
    private fun submitRows() {
        val i = inst ?: return
        adapter.submit(
            listOf(
                DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_instance),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_name, R.string.dsh_settings_desc_name,
                    { inst?.name ?: "" }, DshInstanceSettingAdapter.Tag.NAME
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_model, R.string.dsh_settings_desc_model,
                    { inst?.model ?: "" }, DshInstanceSettingAdapter.Tag.MODEL
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_profile, R.string.dsh_settings_desc_profile,
                    { inst?.profile ?: "" }, DshInstanceSettingAdapter.Tag.PROFILE
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_port, R.string.dsh_settings_desc_port,
                    {
                        val p = inst?.port ?: 0
                        if (p > 0) p.toString() else getString(R.string.dsh_port_auto)
                    },
                    DshInstanceSettingAdapter.Tag.PORT
                ),

                DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_key),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_key, R.string.dsh_settings_desc_key,
                    { maskedKey ?: getString(R.string.dsh_key_absent) },
                    DshInstanceSettingAdapter.Tag.API_KEY
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_key_status, R.string.dsh_settings_desc_key_status,
                    { keyStatusText }, DshInstanceSettingAdapter.Tag.KEY_STATUS, editable = false
                ),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_test_key, 0, R.string.dsh_action_test_key,
                    DshInstanceSettingAdapter.Tag.KEY_TEST
                ),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_clear_key, 0, R.string.dsh_action_clear,
                    DshInstanceSettingAdapter.Tag.KEY_CLEAR
                ),

                DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_env),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_version, 0,
                    { inst?.dshVersion ?: "-" }, DshInstanceSettingAdapter.Tag.VERSION,
                    editable = false
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_disk, 0,
                    { diskText }, DshInstanceSettingAdapter.Tag.DISK, editable = false
                ),
                DshInstanceSettingAdapter.Row.Value(
                    R.string.dsh_settings_row_path, R.string.dsh_settings_desc_path,
                    { instanceId?.let { DshPaths.instanceDir(it).absolutePath } ?: "" },
                    DshInstanceSettingAdapter.Tag.PATH
                ),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_verify_runtime, R.string.dsh_settings_desc_verify,
                    R.string.dsh_action_verify_runtime, DshInstanceSettingAdapter.Tag.VERIFY
                ),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_logs, 0, R.string.dsh_action_logs,
                    DshInstanceSettingAdapter.Tag.LOGS
                ),

                DshInstanceSettingAdapter.Row.Group(R.string.dsh_settings_section_maintain),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_reinstall, R.string.dsh_settings_desc_reinstall,
                    R.string.dsh_action_reinstall, DshInstanceSettingAdapter.Tag.REINSTALL
                ),
                DshInstanceSettingAdapter.Row.Action(
                    R.string.dsh_action_delete, R.string.dsh_settings_desc_delete,
                    R.string.dsh_action_delete, DshInstanceSettingAdapter.Tag.DELETE
                ),
            )
        )
    }

    // ===== 行动作 =====

    private fun onValue(row: DshInstanceSettingAdapter.Row.Value) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.NAME -> editText(
                getString(R.string.dsh_settings_row_name), i.name
            ) { text ->
                if (text.isBlank()) {
                    toast(getString(R.string.dsh_name_required))
                } else {
                    DshInstances.updateConfig(i.id, name = text.trim())
                    reload()
                }
            }

            DshInstanceSettingAdapter.Tag.MODEL -> pick(
                getString(R.string.dsh_settings_model), DshInstance.MODELS, i.model
            ) { value ->
                DshInstances.updateConfig(i.id, model = value)
                reload()
            }

            DshInstanceSettingAdapter.Tag.PROFILE -> pick(
                getString(R.string.dsh_settings_profile), DshInstance.PROFILES, i.profile
            ) { value ->
                DshInstances.updateConfig(i.id, profile = value)
                reload()
            }

            DshInstanceSettingAdapter.Tag.PORT -> editText(
                getString(R.string.dsh_settings_row_port),
                if (i.port > 0) i.port.toString() else ""
            ) { text ->
                val port = if (text.isBlank()) 0 else text.trim().toIntOrNull()
                if (port == null || port < 0 || port > 65535) {
                    toast(getString(R.string.dsh_port_invalid))
                } else {
                    DshInstances.updateConfig(i.id, port = port)
                    reload()
                }
            }

            DshInstanceSettingAdapter.Tag.API_KEY -> editText(
                getString(R.string.dsh_settings_row_key), ""
            ) { text -> saveKey(text) }

            DshInstanceSettingAdapter.Tag.PATH -> {
                val path = DshPaths.instanceDir(i.id).absolutePath
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dsh instance path", path))
                toast(getString(R.string.dsh_settings_desc_path))
            }

            else -> Unit
        }
    }

    private fun onAction(row: DshInstanceSettingAdapter.Row.Action) {
        val i = inst ?: return
        when (row.tag) {
            DshInstanceSettingAdapter.Tag.KEY_TEST -> testKey()
            DshInstanceSettingAdapter.Tag.KEY_CLEAR -> confirmClearKey()
            DshInstanceSettingAdapter.Tag.VERIFY -> verifyRuntime()
            DshInstanceSettingAdapter.Tag.LOGS ->
                startActivity(DshMainActivity.intentForTab(this, DshShellHost.TAB_LOGS))
            DshInstanceSettingAdapter.Tag.REINSTALL -> reinstall(i)
            DshInstanceSettingAdapter.Tag.DELETE -> confirmDelete(i)
            else -> Unit
        }
    }

    // ===== 各动作的具体实现（与改造前的行为一致）=====

    /** 文本输入：用 FCL 的 EditDialog（与 FCL 设置页的编辑行同款） */
    private fun editText(title: String, initial: String, onOk: (String) -> Unit) {
        EditDialog(this, initial) { text -> onOk(text) }.apply { setTitle(title) }.show()
    }

    /** 单选：用 FCL 的 ItemSelectionDialog */
    private fun pick(title: String, options: List<String>, current: String, onPick: (String) -> Unit) {
        ItemSelectionDialog(this, title, options, true, options.indexOf(current)) { _, value ->
            onPick(value)
        }.show()
    }

    private var maskedKey: String? = null

    private fun saveKey(key: String) {
        val id = instanceId ?: return
        if (key.isBlank()) {
            toast(getString(R.string.dsh_key_empty))
            return
        }
        // 加密落盘要跑 Keystore（首次还会生成主密钥）+ 写文件，不能在主线程做
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { DshCredentials.save(this@DshSettingsActivity, id, key) }
            toast(getString(if (ok) R.string.dsh_key_saved else R.string.dsh_key_save_failed))
            refreshCredentialStatus()
        }
    }

    private fun testKey() {
        val id = instanceId ?: return
        keyStatusText = getString(R.string.dsh_key_testing)
        submitRows()
        lifecycleScope.launch {
            // 读回明文同样要过 Keystore 解密，放 IO
            val key = withContext(Dispatchers.IO) { DshCredentials.load(this@DshSettingsActivity, id) ?: "" }
            if (key.isEmpty()) {
                toast(getString(R.string.dsh_key_empty))
                refreshCredentialStatus()
                return@launch
            }
            val result = DeepSeekApi.verifyKey(key)
            keyStatusText = when (result) {
                is DeepSeekApi.VerifyResult.Ok ->
                    getString(R.string.dsh_key_ok, result.models.joinToString(", "))
                is DeepSeekApi.VerifyResult.Invalid ->
                    getString(R.string.dsh_key_invalid, result.message)
                is DeepSeekApi.VerifyResult.Unknown ->
                    getString(R.string.dsh_key_unknown, result.message)
            }
            submitRows()
        }
    }

    private fun confirmClearKey() {
        val id = instanceId ?: return
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(getString(R.string.dsh_key_clear_title))
            .setMessage(getString(R.string.dsh_key_clear_message))
            .setPositiveButton(getString(R.string.dsh_action_clear)) {
                DshCredentials.clear(this, id)
                refreshCredentialStatus()
            }
            .setNegativeButton(getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    private fun verifyRuntime() {
        toast(getString(R.string.dsh_verify_running))
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { DshBootstrap.verify(this@DshSettingsActivity) }
            FCLAlertDialog.Builder(this@DshSettingsActivity)
                .setAlertLevel(
                    if (report.ok) FCLAlertDialog.AlertLevel.INFO else FCLAlertDialog.AlertLevel.ALERT
                )
                .setTitle(getString(R.string.dsh_verify_title))
                .setMessage(report.detail)
                .setNegativeButton(getString(R.string.dialog_positive), null)
                .create()
                .show()
        }
    }

    private fun reinstall(i: DshInstance) {
        val version = i.dshVersion
        if (version == null) {
            toast(getString(R.string.dsh_reinstall_pick_version))
            startActivity(DshMainActivity.intentForTab(this, DshShellHost.TAB_DOWNLOAD))
        } else {
            DshServices.installer(this).install(i, version)
            toast(getString(R.string.dsh_install_started, version))
        }
    }

    private fun confirmDelete(i: DshInstance) {
        FCLAlertDialog.Builder(this)
            .setAlertLevel(FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(getString(R.string.dsh_delete_title))
            .setMessage(getString(R.string.dsh_delete_message, i.name))
            .setPositiveButton(getString(R.string.dsh_action_delete)) {
                DshCredentials.clear(this, i.id)
                DshInstances.delete(i.id)
                finish()
            }
            .setNegativeButton(getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    /**
     * 刷新凭据状态。
     * Keystore 解密在部分机型上要 100ms+，放主线程会在进入本页时明显掉帧，所以整体挪到 IO。
     */
    private fun refreshCredentialStatus() {
        val id = instanceId ?: return
        keyStatusText = getString(R.string.dsh_key_checking)
        submitRows()
        lifecycleScope.launch {
            val (label, args, masked) = withContext(Dispatchers.IO) {
                when (val status = DshCredentials.status(this@DshSettingsActivity, id)) {
                    is DshCredentials.Status.Ok -> {
                        val m = DshCredentials.mask(DshCredentials.load(this@DshSettingsActivity, id))
                        Triple(R.string.dsh_key_present, arrayOf<Any>(m), m)
                    }
                    is DshCredentials.Status.None ->
                        Triple(R.string.dsh_key_absent, emptyArray<Any>(), null)
                    is DshCredentials.Status.Unreadable ->
                        Triple(R.string.dsh_key_unreadable, arrayOf<Any>(status.reason), null)
                }
            }
            maskedKey = masked
            keyStatusText =
                if (args.isEmpty()) getString(label) else getString(label, *args)
            submitRows()
        }
    }

    /** 重新读取实例（配置改动后刷新界面） */
    private fun reload() {
        inst = instanceId?.let { DshInstances.byId(it) }
        submitRows()
    }

    private fun showDiskUsage() {
        val id = instanceId ?: return
        diskText = getString(R.string.dsh_size_calculating)
        DshInstances.diskUsageAsync(id) { bytes ->
            runOnUiThread {
                diskText = DshPaths.formatSize(bytes)
                submitRows()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        ThemeEngine.getInstance().unregisterEvent(binding.settingList)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "dsh_instance_id"
    }
}
