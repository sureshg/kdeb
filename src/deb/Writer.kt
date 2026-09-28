package deb

import com.rafambn.kflate.KFlate
import com.rafambn.kflate.compression.Gzip
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.MD5
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlin.time.Clock

/**
 * Serializes a [DebPackage] into the canonical Debian binary package format:
 *
 * ```
 * !<arch>
 * debian-binary     "2.0\n"
 * control.tar.gz    metadata (control, md5sums, maintainer scripts)
 * data.tar.gz       payload (files + directories)
 * ```
 *
 * Member order is mandated by deb(5) and enforced below. The output is
 * accepted by `dpkg` / `apt` on all supported Debian and Ubuntu releases.
 */
fun DebPackage.writeTo(sink: Sink) {
    val mtime: Long = entries.firstOrNull()?.mtime?.epochSeconds ?: Clock.System.now().epochSeconds

    val controlTarGz = buildControlTarGz(mtime)
    val dataTarGz = buildDataTarGz()

    val ar = ArWriter(sink)
    ar.writeMember("debian-binary", mtime, DEBIAN_BINARY)
    ar.writeMember("control.tar.gz", mtime, controlTarGz)
    ar.writeMember("data.tar.gz", mtime, dataTarGz)
}

// ---- control.tar.gz ---------------------------------------------------------

private fun DebPackage.buildControlTarGz(mtime: Long): ByteArray = gzipTar { tar ->
    tar.writeDirectory("./", mtime = mtime)
    tar.writeFile("./control", controlFile().encodeToByteArray(), mtime = mtime)

    val md5 = md5sums(entries)
    if (md5.isNotEmpty()) tar.writeFile("./md5sums", md5.encodeToByteArray(), mtime = mtime)

    // Maintainer scripts MUST be mode 0755 — dpkg refuses otherwise.
    writeScript(tar, "preinst", scripts.preinst, mtime)
    writeScript(tar, "postinst", scripts.postinst, mtime)
    writeScript(tar, "prerm", scripts.prerm, mtime)
    writeScript(tar, "postrm", scripts.postrm, mtime)
}

private fun writeScript(tar: TarWriter, name: String, body: String?, mtime: Long) {
    if (body == null) return
    tar.writeFile("./$name", body.encodeToByteArray(), mode = SCRIPT_MODE, mtime = mtime)
}

// ---- data.tar.gz ------------------------------------------------------------

private fun DebPackage.buildDataTarGz(): ByteArray = gzipTar { tar ->
    tar.writeDirectory("./", mtime = entries.firstOrNull()?.mtime?.epochSeconds ?: 0L)

    // dpkg requires every parent directory to exist in the archive; we synthesize
    // the ones the caller did not declare explicitly.
    val declaredDirs = entries.filterIsInstance<DataEntry.Directory>().map { it.targetPath.normalizeDir() }.toSet()
    val synthesizedDirs = mutableSetOf<String>()

    for (entry in entries) {
        val parents = parentDirs(entry.targetPath)
        for (parent in parents) {
            val key = parent.normalizeDir()
            if (key in declaredDirs || key in synthesizedDirs) continue
            synthesizedDirs += key
            tar.writeDirectory("./$key", mtime = entry.mtime.epochSeconds)
        }
        when (entry) {
            is DataEntry.File -> tar.writeFile(
                name = "./" + entry.targetPath.trimStart('/'),
                content = entry.content,
                mode = entry.mode, uid = entry.uid, gid = entry.gid,
                user = entry.user, group = entry.group,
                mtime = entry.mtime.epochSeconds,
            )

            is DataEntry.Directory -> tar.writeDirectory(
                name = "./" + entry.targetPath.trimStart('/'),
                mode = entry.mode, uid = entry.uid, gid = entry.gid,
                user = entry.user, group = entry.group,
                mtime = entry.mtime.epochSeconds,
            )
        }
    }
}

// ---- control file -----------------------------------------------------------

private fun DebPackage.controlFile(): String = buildString {
    appendLine("Package: ${meta.name}")
    appendLine("Version: ${meta.version}")
    appendLine("Architecture: ${meta.architecture}")
    appendLine("Maintainer: ${meta.maintainer}")
    appendLine("Installed-Size: ${installedSizeKiB(entries)}")
    meta.section?.let { appendLine("Section: $it") }
    meta.priority?.let { appendLine("Priority: $it") }
    if (meta.depends.isNotEmpty()) appendLine("Depends: ${meta.depends.joinToString(", ")}")
    appendLine("Description: ${foldDescription(meta.description)}")
}

/** Debian description folding: continuation lines are prefixed with a single
 *  space; blank continuation lines become " .". */
private fun foldDescription(text: String): String {
    val lines = text.lines()
    if (lines.size <= 1) return text
    val head = lines.first()
    val tail = lines.drop(1).joinToString(separator = "") { line ->
        "\n" + if (line.isBlank()) " ." else " $line"
    }
    return head + tail
}

/** Installed-Size is reported in KiB, rounded up, summing file content sizes
 *  (Debian policy §5.6.20). Directories contribute nothing. */
private fun installedSizeKiB(entries: List<DataEntry>): Long {
    val totalBytes = entries.filterIsInstance<DataEntry.File>().sumOf { it.content.size.toLong() }
    return (totalBytes + 1023) / 1024
}

// ---- md5sums ---------------------------------------------------------------

private val md5Hasher = CryptographyProvider.Default.get(MD5).hasher()

private fun md5sums(entries: List<DataEntry>): String = buildString {
    for (entry in entries) {
        if (entry !is DataEntry.File) continue
        val path = entry.targetPath.trimStart('/')        // md5sums: no leading '/', no './'
        append(md5Hasher.hashBlocking(entry.content).toHexString())
        append("  ")
        append(path)
        append('\n')
    }
}

// ---- helpers ---------------------------------------------------------------

private val DEBIAN_BINARY: ByteArray = "2.0\n".encodeToByteArray()
private const val SCRIPT_MODE: Int = 0b111_101_101   // 0755

private fun gzipTar(block: (TarWriter) -> Unit): ByteArray {
    val buf = Buffer()
    val tar = TarWriter(buf)
    block(tar)
    tar.finish()
    return KFlate.compress(buf.readByteArray(), Gzip())
}

/** Returns every ancestor directory of [path], top-down: e.g.
 *  `/usr/share/doc/hello/copyright` → `["usr/", "usr/share/", "usr/share/doc/", "usr/share/doc/hello/"]`. */
private fun parentDirs(path: String): List<String> {
    val trimmed = path.trimStart('/').trimEnd('/')
    val segments = trimmed.split('/').dropLast(1).filter { it.isNotEmpty() }
    if (segments.isEmpty()) return emptyList()
    val out = ArrayList<String>(segments.size)
    val sb = StringBuilder()
    for (segment in segments) {
        sb.append(segment).append('/')
        out += sb.toString()
    }
    return out
}

/** Canonical form: no leading `/`, exactly one trailing `/`. */
private fun String.normalizeDir(): String {
    val trimmed = trimStart('/').trimEnd('/')
    return if (trimmed.isEmpty()) "" else "$trimmed/"
}
