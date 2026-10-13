package com.tungsten.fcllibrary.component.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.dsh.fcl.androidlauncher.R
import com.tungsten.fcllibrary.component.view.FCLEditText
import com.dsh.fcl.androidlauncher.databinding.DialogEditBinding
import com.tungsten.fcllibrary.util.ConvertUtils
import java.util.function.Consumer

/**
 * 通用文本输入对话框（FCL 基座共享组件：设置页、[com.tungsten.fcllibrary.component.view.FCLNumberSeekBar]、
 * dsh 各设置页都在用）。
 *
 * @param defaultText 打开时预填的文本
 * @param allowEmpty 空白确认是否被接受（fix2 / BL-090）。
 *   - `false`（默认，既有调用方语义）：空白确认**不再静默吞掉**（旧行为是点了没反应，用户以为
 *     按钮坏了）—— 改为 Toast 提示 [R.string.dsh_edit_blank_hint] 并保持对话框打开；
 *     非空白输入照常回传。
 *   - `true`：空白确认回传**空串**并关闭对话框。"留空 = 自动 / 清除"这类语义由调用方解释
 *     （例：实例详情页的端口行，空串 = 端口 0 = 自动分配）。
 * @param callback 确认且输入有效时收到文本（可能为空串，只在 [allowEmpty] = true 时）
 *
 * ★ 向后兼容说明（fix2 添加 [allowEmpty] 时）：
 * - 新参数带默认值，且**末尾的 [callback] 位置不变** —— 既有 Kotlin 调用点
 *   `EditDialog(context) { ... }` / `EditDialog(context, "x") { ... }`（尾随 lambda）源码零改动；
 * - `@JvmOverloads` 的生成规则（按"逐个省略从右数的默认参数"生成重载）不变，
 *   原先可用的 `(Context, Consumer)` / `(Context, String, Consumer)` Java 签名照旧生成。
 */
class EditDialog @JvmOverloads constructor(
    context: Context,
    private val defaultText: String = "",
    private val allowEmpty: Boolean = false,
    private val callback: Consumer<String>
) : FCLDialog(context),
    View.OnClickListener {
    var binding: DialogEditBinding
    var onCancelListener: (() -> Unit)? = null

    init {
        setCancelable(false)
        window?.setLayout(ConvertUtils.dip2px(context, 400f), ViewGroup.LayoutParams.WRAP_CONTENT)
        binding = DialogEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            positive.setOnClickListener(this@EditDialog)
            negative.setOnClickListener(this@EditDialog)
            editText.setText(defaultText)
        }
    }

    override fun onClick(v: View?) {
        binding.apply {
            if (v === positive) {
                val s = editText.getText().toString()
                if (!s.trim { it <= ' ' }.isEmpty()) {
                    callback.accept(s)
                    dismiss()
                } else if (allowEmpty) {
                    // 空白是有效值：回传空串（"留空=自动/清除"的语义由调用方解释）。BL-090
                    callback.accept("")
                    dismiss()
                } else {
                    // 旧行为是静默无反应（BL-090 的病根）：明确提示，对话框保持打开让用户改。
                    // 注：这里的 `context` 是 Dialog.getContext()（构造参数不携带到成员函数里）。
                    Toast.makeText(context, R.string.dsh_edit_blank_hint, Toast.LENGTH_SHORT)
                        .show()
                }
            }
            if (v === negative) {
                onCancelListener?.invoke()
                dismiss()
            }
        }
    }

    override fun setTitle(titleId: Int) {
        binding.title.setText(titleId)
    }

    override fun setTitle(title: CharSequence?) {
        binding.title.text = title
    }

    @SuppressLint("SetTextI18n")
    fun appendTitle(title: String) {
        binding.title.text = "${binding.title.text} $title"
    }

    fun getEditText(): FCLEditText {
        return binding.editText
    }
}
