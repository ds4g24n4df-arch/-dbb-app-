package cc.luxiaofei.app

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 本机语音缓存：合成过一次的句子，之后都从机内文件播（开播 0 延迟）。
 * key = 文本 + 音色 + 类型（少一个维度就串音）。
 */
object VoiceCache {
    private const val DIR = "voice"
    private const val MAX_FILES = 800

    fun key(text: String, ref: String, kind: String): String {
        val raw = "$kind\u0000$ref\u0000${text.trim()}"
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            Integer.toHexString(raw.hashCode())
        }
    }

    fun file(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$key.mp3")
    }

    fun get(ctx: Context, key: String): File? {
        val f = file(ctx, key)
        return if (f.exists() && f.length() > 0) f else null
    }

    fun put(ctx: Context, key: String, bytes: ByteArray): File? {
        if (bytes.isEmpty()) return null
        return try {
            val f = file(ctx, key)
            val tmp = File(f.parentFile, "${f.name}.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.writeBytes(bytes)
                tmp.delete()
            }
            trim(ctx)
            f
        } catch (_: Exception) {
            null
        }
    }

    private fun trim(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, DIR)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
        } catch (_: Exception) {
        }
    }
}
