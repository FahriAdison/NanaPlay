package com.opencloudgaming.opennow

import android.content.Context
import com.papahchan.nanaplay.BuildConfig
import org.webrtc.IceCandidate
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Mitigasi defensif, bukan fix akar: pencatatan persisten kandidat remote ICE
 * ke berkas ring berukuran terbatas di disk (filesDir).
 *
 * Penulisan dilakukan secara SINKRON ke OS page cache (tanpa fsync) tepat
 * sebelum addIceCandidate dieksekusi untuk mengeliminasi race condition
 * dengan SIGABRT tanpa membebani Main thread dengan I/O disk fisik.
 */
internal object RemoteIceLog {
    private const val LOG_FILE_NAME = "remote-ice-candidates.log"
    private const val MAX_LOG_BYTES = 65_536L // 64 KB
    private const val TRIM_TARGET_BYTES = 32_768 // 32 KB

    @Synchronized
    fun append(
        context: Context,
        rawCandidate: IceCandidate,
        postCandidate: IceCandidate,
        action: String,
        reason: String,
        lastIceState: String,
        remoteMLineCount: Int,
        remoteMids: List<String>,
        dtConnectedMs: Long,
        unresolvedPort0: Boolean,
    ) {
        runCatching {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                trimLogFile(file)
            }
            val entry = buildString {
                append(System.currentTimeMillis()).append(" ")
                append("vc=").append(BuildConfig.VERSION_CODE).append(" ")
                append("action=").append(action).append(" ")
                append("reason=").append(reason).append(" ")
                append("state=").append(lastIceState).append(" ")
                append("mLines=").append(remoteMLineCount).append(" ")
                append("mids=").append(remoteMids.joinToString(",", "[", "]")).append(" ")
                append("dtConnMs=").append(dtConnectedMs).append(" ")
                if (unresolvedPort0) append("flag=[unresolved_port_0] ")
                append(postCandidate.diagnosticSummary()).append(" ")
                append("rawPre=\"").append(rawCandidate.sdp.take(120)).append("\"")
                appendLine()
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(entry.toByteArray(Charsets.UTF_8))
                // Sengaja tidak memanggil fos.fd.sync(): penulisan ke OS page cache
                // selesai dalam mikrodetik dan tetap bertahan saat SIGABRT process death.
            }
        }
    }

    private fun trimLogFile(file: File) {
        runCatching {
            val length = file.length()
            if (length <= MAX_LOG_BYTES) return
            val bytesToRead = TRIM_TARGET_BYTES.coerceAtMost(length.toInt())
            val buffer = ByteArray(bytesToRead)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - bytesToRead)
                raf.readFully(buffer)
            }
            // Cari newline pertama agar tidak memotong baris di tengah
            val newlineIndex = buffer.indexOf('\n'.code.toByte())
            val trimmedBytes = if (newlineIndex >= 0 && newlineIndex < buffer.size - 1) {
                buffer.copyOfRange(newlineIndex + 1, buffer.size)
            } else {
                buffer
            }
            FileOutputStream(file, false).use { fos ->
                fos.write(trimmedBytes)
            }
        }
    }

    /**
     * Membaca isi log persisten untuk diagnostik share.
     */
    fun readLog(context: Context, maxBytes: Int = 32_768): String? {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val length = file.length()
            if (length == 0L) return null
            if (length <= maxBytes) {
                file.readText(Charsets.UTF_8)
            } else {
                val bytesToRead = maxBytes.coerceAtMost(length.toInt())
                val buffer = ByteArray(bytesToRead)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(length - bytesToRead)
                    raf.readFully(buffer)
                }
                val newlineIndex = buffer.indexOf('\n'.code.toByte())
                val finalBytes = if (newlineIndex >= 0 && newlineIndex < buffer.size - 1) {
                    buffer.copyOfRange(newlineIndex + 1, buffer.size)
                } else {
                    buffer
                }
                String(finalBytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, LOG_FILE_NAME).delete() }
    }
}

