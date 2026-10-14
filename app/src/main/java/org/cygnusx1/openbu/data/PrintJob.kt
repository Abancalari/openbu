package org.cygnusx1.openbu.data

import android.net.Uri
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class PrintOptions(
    val bedLeveling: Boolean = true,
    val flowCali: Boolean = false,
    val vibrationCali: Boolean = false,
    val timelapse: Boolean = false,
    val useAms: Boolean = true,
    val amsMapping: List<Int> = listOf(0),
    val plateIndex: Int = 1,
)

data class PrintJobRequest(
    val uri: Uri? = null,
    val remotePath: String? = null,
    val fileName: String,
    val fileSize: Long = -1L,
    val md5: String = "",
)

object ModelPackageHelper {
    data class PackageMetadata(
        val md5: String = "",
        val plateCount: Int = 1,
    )

    fun inspect3mf(input: InputStream, plateIndex: Int = 1): PackageMetadata {
        var foundMd5: String? = null
        var computedMd5: String? = null
        var maxPlate = 1
        try {
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                val targetMd5Name = "metadata/plate_$plateIndex.gcode.md5"
                val targetGcodeName = "metadata/plate_$plateIndex.gcode"
                while (entry != null) {
                    val name = entry.name.lowercase().replace('\\', '/')
                    if (name == targetMd5Name || name.endsWith("/plate_$plateIndex.gcode.md5")) {
                        val text = zis.bufferedReader().readText().trim()
                        if (text.isNotEmpty()) {
                            val match = Regex("[0-9a-fA-F]{32}").find(text)
                            foundMd5 = match?.value ?: text
                        }
                    } else if (name == targetGcodeName || name.endsWith("/plate_$plateIndex.gcode")) {
                        val md = MessageDigest.getInstance("MD5")
                        val buf = ByteArray(8192)
                        var n = zis.read(buf)
                        while (n != -1) {
                            md.update(buf, 0, n)
                            n = zis.read(buf)
                        }
                        computedMd5 = md.digest().joinToString("") { "%02x".format(it) }
                    }
                    val plateMatch = Regex("plate_(\\d+)\\.gcode").find(name)
                    if (plateMatch != null) {
                        val p = plateMatch.groupValues[1].toIntOrNull() ?: 1
                        if (p > maxPlate) maxPlate = p
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // Ignore extraction errors and fallback to empty md5
        }
        return PackageMetadata(
            md5 = foundMd5 ?: computedMd5 ?: "",
            plateCount = maxPlate,
        )
    }
}

