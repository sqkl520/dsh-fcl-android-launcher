package com.dsh.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import java.io.File

/**
 * 实例配置文件的**多行文本编辑器**（BL-249）。
 *
 * ## 为什么要做它
 *
 * dsh 自带一个「设置 → 打开配置文件」按钮，但在 Android 上**必然失败**（BL-248）：
 * 它在 linux 分支调 `xdg-open`，而 proot 里既没有 `xdg-utils`，也没有可连接的
 * 显示服务（无 X11 / Wayland）。用户点了只会看到红字「无法打开配置文件」。
 *
 * 而 `launcher.patch.yml` 恰恰是**用户可见的配置入口** —— dsh 文档教用户往里加
 * provider / 插件（BL-246 记录过：用户改它、重启、发现没生效，只会以为"配置写错了"）。
 * 所以启动器必须自己给一个能用的入口。
 *
 * ## 为什么用 Dialog 而不是 EditDialog
 *
 * FCL 自带的 [com.tungsten.fcllibrary.component.dialog.EditDialog] 是**单行**的
 * （内部就是一个单行 EditText）。YAML 配置天然多行、依赖缩进，单行框放不下，
 * 也无法校对缩进。所以这里单独做一个多行版本。
 *
 * ## 安全约束（都不是可选项）
 *
 * 1. **保存前自动备份**：写坏一个能跑的配置比不写更糟。备份成 `<name>.bak`，
 *    只保留一份（不无限增长）。
 * 2. **原子替换**：先写 `.tmp` 再 rename。POSIX 的 rename(2) 是原子的，
 *    这样"写一半断电"不会留下半截文件。
 * 3. **空内容先确认**：清空 `launcher.patch.yml` 会让 dsh 丢掉 `--patch` 那一层
 *    （启动器是 `[ -f "$LAUNCHER_PATCH" ]` 判的）。这可能是用户想要的，
 *    但必须让他知道后果，不能默默保存。
 * 4. **拒绝在实例运行中写入**：dsh 会 watch 配置文件，运行期改写会触发
 *    整树 recompose（等于给用户的运行中实例来一次重启级抖动）。
 *    启动器的 [com.dsh.core.writeLauncherPatchOverlay] 也遵守同一条。
 */
class DshFileEditDialog(
    private val activity: Activity,
    private val target: File,
    private val titleText: String,
    private val hintText: String,
    private val readOnly: Boolean,
    private val onSaved: () -> Unit
) : FCLDialog(activity) {

    private lateinit var editText: EditText
    private lateinit var cancelButton: Button
    private lateinit var saveButton: Button

    /**
     * 读文件失败时被置真。
     *
     * ★ 必须在 [init] **之前**声明并初始化：Kotlin 按声明顺序初始化属性，
     *   而 [init] 块里会读它。声明在后面的话编译报 "must be initialized"（已踩到）。
     */
    private var readOnlyEffective = false

    /** 打开时的原始内容，用来判断"有没有真的改过" */
    private var original: String = ""

    init {
        setContentView(R.layout.dialog_dsh_file_edit)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 软键盘弹出时不要把编辑区压没：让窗口自己 resize。
        // 不做这一步的话，Android 会默认 pan，把整个对话框顶上去、按钮跑出屏幕。
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // ★ 显式写 `findViewById<TextView>(...)!!`：FCLDialog 继承链上 findViewById 的返回
        //   在 Kotlin 里是平台类型 `T!`，推断成可空时下面每一行都要加 `?.`（实测编译报错）。
        //   这些 id 都在我们自己的布局里，`!!` 是安全的 —— 缺了就是布局写错，应该立刻崩。
        val titleView = findViewById<TextView>(R.id.edit_title)!!
        titleView.text = titleText
        val pathView = findViewById<TextView>(R.id.edit_path)!!
        pathView.text = target.absolutePath
        val hintView = findViewById<TextView>(R.id.edit_hint)!!
        hintView.text = hintText
        hintView.visibility = if (hintText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        editText = findViewById<EditText>(R.id.edit_text)!!
        cancelButton = findViewById<Button>(R.id.edit_cancel)!!
        saveButton = findViewById<Button>(R.id.edit_save)!!

        cancelButton.text = activity.getString(R.string.dialog_negative)
        saveButton.text = activity.getString(R.string.dialog_positive)

        // 读失败（权限/IO）不弹错误框，直接把原因放进提示区并切只读 ——
        // 用户至少能看到"为什么打不开"，而不是一个空白框。
        original = runCatching { if (target.isFile) target.readText(Charsets.UTF_8) else "" }
            .getOrElse { e ->
                readOnlyEffective = true
                hintView.visibility = android.view.View.VISIBLE
                hintView.text = activity.getString(R.string.dsh_file_edit_read_failed, e.message ?: "")
                ""
            }
        editText.setText(original)
        val editable = !(readOnly || readOnlyEffective)
        editText.isEnabled = editable
        saveButton.isEnabled = editable
        saveButton.alpha = if (editable) 1f else 0.4f

        cancelButton.setOnClickListener { dismiss() }
        saveButton.setOnClickListener { save() }
    }

    private fun save() {
        val text = editText.text?.toString() ?: ""

        // 没改就不动文件 —— 避免无意义地刷新 mtime、触发 dsh 的 watch。
        if (text == original) {
            dismiss()
            return
        }

        // 空内容要确认：清空 launcher.patch.yml 会让启动器不再传 --patch，
        // 那一层的两条优化（/assets 缓存头、HMR 轮询间隔）会一起失效。
        if (text.isBlank() && target.name == LAUNCHER_PATCH_NAME) {
            confirm(
                title = activity.getString(R.string.dsh_file_edit_empty_title),
                message = activity.getString(R.string.dsh_file_edit_empty_message)
            ) { writeAndClose(text) }
            return
        }

        writeAndClose(text)
    }

    private fun writeAndClose(text: String) {
        val outcome = runCatching { writeAtomically(target, text) }
        outcome.fold(
            onSuccess = {
                toast(activity.getString(R.string.dsh_file_edit_saved))
                onSaved()
                dismiss()
            },
            onFailure = { e ->
                // 写失败**不能**静默关掉对话框 —— 用户会以为保存成功了。
                alert(
                    title = activity.getString(R.string.dsh_file_edit_save_failed_title),
                    message = e.message ?: e.toString()
                )
            }
        )
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        com.tungsten.fcllibrary.component.dialog.FCLAlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.dialog_positive)) { onYes() }
            .setNegativeButton(activity.getString(R.string.dialog_negative), null)
            .create()
            .show()
    }

    private fun alert(title: String, message: String) {
        com.tungsten.fcllibrary.component.dialog.FCLAlertDialog.Builder(activity)
            .setAlertLevel(com.tungsten.fcllibrary.component.dialog.FCLAlertDialog.AlertLevel.ALERT)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(activity.getString(R.string.dialog_positive), null)
            .create()
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 与 [com.dsh.core.LAUNCHER_PATCH_OVERLAY] 一致（这里只用于"清空要确认"那一处判断） */
        private const val LAUNCHER_PATCH_NAME = "launcher.patch.yml"

        /**
         * 原子写 + 备份。
         *
         * 顺序是刻意的：
         * 1. 先备份原文件到 `<name>.bak`（覆盖式，只留一份）——
         *    必须在改动前，否则写坏了就没有可回滚的内容；
         * 2. 写 `<name>.tmp`；
         * 3. rename 覆盖目标。
         *
         * 为什么不先删目标再 rename：`renameTo` 在 POSIX 上本身就是原子覆盖，
         * 多一步删除只是多造一个"删成功了、rename 失败了"的毁数据窗口
         * （`writeLauncherPatchOverlay` 的注释里有同样的结论）。
         */
        fun writeAtomically(target: File, text: String) {
            target.parentFile?.mkdirs()
            if (target.isFile) {
                val backup = File(target.parentFile, target.name + ".bak")
                runCatching { target.copyTo(backup, overwrite = true) }
            }
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // rename 失败时 tmp 会留下；删掉它，别在实例目录里堆垃圾。
                runCatching { tmp.delete() }
                throw java.io.IOException("rename failed: ${tmp.absolutePath} -> ${target.absolutePath}")
            }
        }
    }
}
