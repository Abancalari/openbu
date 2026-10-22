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

    fun sanitizePrintFileName(rawName: String, inputStreamProvider: (() -> InputStream?)? = null): String {
        var name = rawName.trim()
        val lower = name.lowercase()

        // 1. Direct .3mf or .gcode
        if (lower.endsWith(".3mf") || lower.endsWith(".gcode")) {
            return name
        }

        // 2. Trailing .txt check (Chrome often appends .txt to .3mf or .gcode)
        if (lower.endsWith(".txt")) {
            val withoutTxt = name.substring(0, name.length - 4).trim()
            val withoutTxtLower = withoutTxt.lowercase()

            // e.g. "model.3mf.txt" -> "model.3mf"
            if (withoutTxtLower.endsWith(".3mf") || withoutTxtLower.endsWith(".gcode")) {
                return withoutTxt
            }

            // e.g. "model.3mf (1).txt" or "model.3mf_1.txt" -> "model (1).3mf"
            val regex3mf = Regex("^(.*)\\.3mf([_\\s\\-\\(\\)\\d]+)$", RegexOption.IGNORE_CASE)
            val match3mf = regex3mf.matchEntire(withoutTxt)
            if (match3mf != null) {
                return "${match3mf.groupValues[1]}${match3mf.groupValues[2]}.3mf"
            }

            val regexGcode = Regex("^(.*)\\.gcode([_\\s\\-\\(\\)\\d]+)$", RegexOption.IGNORE_CASE)
            val matchGcode = regexGcode.matchEntire(withoutTxt)
            if (matchGcode != null) {
                return "${matchGcode.groupValues[1]}${matchGcode.groupValues[2]}.gcode"
            }
        }

        // 3. Fallback: inspect magic bytes from input stream if provided
        if (inputStreamProvider != null) {
            try {
                inputStreamProvider()?.use { stream ->
                    val header = ByteArray(512)
                    val read = stream.read(header)
                    if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() && header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) {
                        // ZIP archive magic bytes (3MF is a ZIP archive)
                        val base = if (name.lowercase().endsWith(".txt")) name.substring(0, name.length - 4) else name
                        return "$base.3mf"
                    } else if (read > 0) {
                        val headerText = String(header, 0, read, Charsets.UTF_8).trimStart()
                        if (headerText.startsWith(";") || headerText.startsWith("G", ignoreCase = true) || headerText.startsWith("M", ignoreCase = true)) {
                            val base = if (name.lowercase().endsWith(".txt")) name.substring(0, name.length - 4) else name
                            return "$base.gcode"
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return name
    }

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

