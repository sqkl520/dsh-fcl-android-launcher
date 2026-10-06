package com.dsh.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * dsh API key 的安全存取。
 *
 * ## 存储
 * API key 用 Android Keystore 里的 AES-GCM 主密钥加密后落盘（密文在 App 私有目录，
 * 主密钥不出安全硬件，明文不落任何普通文件）。
 *
 * ## 运行期注入（本次改动）
 * **不再**把明文写进 `credentials.env`。原来那套"启动前写明文文件 + 停止后删除"有两个问题：
 * 1. 进程崩溃/被系统杀掉时没人执行删除，明文就长期留在数据目录里；
 * 2. `File.setReadable/setWritable` 在 Android 上基本无效（返回值被忽略），等于假的权限加固。
 * 现在密钥通过**子进程环境变量**（[ProotCommand.Spec.procEnv]）传给 proot，
 * 既不进 argv（`ps` 看不到），也不落盘。start-dsh.sh 的旧 CRED_FILE 逻辑保留（Termux 手工用），
 * 但 App 路径不再产生该文件；[cleanupLegacyFiles] 负责清掉历史残留。
 *
 * ## 健壮性
 * - 密文格式带版本号 + IV，并以 instanceId 作为 GCM 的 AAD：密文被拷到别的实例会解密失败，
 *   不会出现"A 实例的 key 被 B 实例静默使用"。
 * - [status] 能区分"没配"和"配了但解不开"（换机恢复/锁屏密码变更会导致 Keystore 密钥失效）。
 *   原来 `hasCredential` 只看文件存在，会把解不开的密文当成已配置，用户完全摸不着头脑。
 */
object DshCredentials {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsh_api_key_master"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12
    private const val FORMAT_VERSION: Byte = 1

    /** 凭据状态 */
    sealed class Status {
        /** 未配置 */
        object None : Status()

        /** 已配置且可解密 */
        object Ok : Status()

        /** 有密文但解不开（Keystore 主密钥失效/数据损坏） */
        data class Unreadable(val reason: String) : Status()
    }

    /** 加密存储某实例的 API key（密文落盘）。返回是否成功。 */
    fun save(context: Context, instanceId: String, apiKey: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        cipher.updateAAD(instanceId.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val ct = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val f = encFile(instanceId)
        f.parentFile?.mkdirs()
        // 原子写：密文是"版本号+IV+密文"一整块，写到一半被杀就会留下半截文件，
        // 之后 status() 只能报 Unreadable（用户看到一个解不开的 key，还以为是自己输错了）。
        // 先写临时文件再 rename，保证磁盘上要么是旧内容、要么是完整新内容。
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeBytes(byteArrayOf(FORMAT_VERSION) + iv + ct)
        if (!tmp.renameTo(f)) {
            f.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        // 顺手清掉历史明文残留
        cleanupLegacyFiles(instanceId)
        DshLogBus.registerSecret(apiKey.trim())
        true
    }.getOrDefault(false)

    /** 读回明文 API key；未配置或解不开返回 null（用 [status] 区分原因） */
    fun load(context: Context, instanceId: String): String? = when (val st = status(context, instanceId)) {
        is Status.Ok -> decrypt(instanceId)
        else -> {
            if (st is Status.Unreadable) {
                // 实例级：解不开的是**这个实例**的密钥，用户要在它的详情页知道"为什么起不来/连不上"
                // （换机恢复、锁屏密码变更会让 Keystore 主密钥失效，这时只有重填 key 能救）
                DshLogBus.appendFor(instanceId, "[credentials] 实例 $instanceId 的密钥无法解密：${st.reason}")
            }
            null
        }
    }

    /** 状态判定：文件在不在 + 能不能解开 */
    fun status(context: Context, instanceId: String): Status {
        val f = encFile(instanceId)
        if (!f.isFile || f.length() <= IV_LEN) return Status.None
        return runCatching {
            // 只做一次解密校验，顺便确认主密钥可用
            val plain = decrypt(instanceId)
            if (plain.isNullOrEmpty()) Status.Unreadable("密文为空") else Status.Ok
        }.getOrElse { Status.Unreadable(it.message ?: it.toString()) }
    }

    /** 是否有"可用"的密钥（解不开不算） */
    fun hasUsable(context: Context, instanceId: String): Boolean =
        status(context, instanceId) is Status.Ok

    private fun decrypt(instanceId: String): String? {
        val blob = encFile(instanceId).readBytes()
        if (blob.size <= 1 + IV_LEN) return null
        val version = blob[0]
        if (version != FORMAT_VERSION) throw IllegalStateException("未知的密文格式版本 $version")
        val iv = blob.copyOfRange(1, 1 + IV_LEN)
        val ct = blob.copyOfRange(1 + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(instanceId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** 删除该实例的密钥密文 */
    fun clear(context: Context, instanceId: String) {
        runCatching { encFile(instanceId).delete() }
        cleanupLegacyFiles(instanceId)
    }

    /**
     * 清掉历史版本的明文凭据文件（改动前会留下 credentials.env）。
     * 由 [save] / [clear] 调用，两处都带明确的 instanceId。
     */
    fun cleanupLegacyFiles(instanceId: String) {
        runCatching {
            val f = DshPaths.instanceLegacyCredentials(instanceId)
            if (f.exists()) {
                f.delete()
                // 实例级：删掉的是**这个实例**目录下的明文文件（"为什么我的 key 没了/被清了"要看这里）
                DshLogBus.appendFor(instanceId, "[credentials] 已清理历史明文凭据文件 ${f.name}")
            }
        }
    }

    /** 界面显示用的脱敏形式：sk-abcd…wxyz */
    fun mask(apiKey: String?): String {
        if (apiKey.isNullOrBlank()) return "—"
        if (apiKey.length <= 10) return "****"
        return apiKey.take(6) + "…" + apiKey.takeLast(4)
    }

    private fun encFile(instanceId: String): File = DshPaths.instanceCredentialsEnc(instanceId)

    /** 获取或生成 Keystore 里的 AES 主密钥 */
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不要求用户认证：dsh 进程需要在无人交互时也能起
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }
}
