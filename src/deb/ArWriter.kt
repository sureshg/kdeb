package deb

import kotlinx.io.Sink
import kotlinx.io.writeString

/**
 * Writes the Debian `ar` archive layout (see deb(5)).
 *
 * Each member header is 60 bytes of ASCII, space-padded; member data is
 * 2-byte aligned (pad with '\n' when size is odd). All three `.deb` members
 * use uid/gid 0 and file mode 100644.
 *
 * Debian `ar` uses **plain** member names (no BSD trailing '/') — this
 * matches the output of `dpkg-deb` and is required for byte-for-byte
 * identical packages across tools.
 */
class ArWriter(private val sink: Sink) {

    init {
        sink.writeString(MAGIC)
    }

    fun writeMember(name: String, mtimeEpochSec: Long, data: ByteArray) {
        require(name.length <= 16) { "ar member name >16 chars: '$name'" }
        field(name, width = 16)                     // name (space-padded)
        field(mtimeEpochSec.toString(), width = 12) // mtime
        field("0", width = 6)                       // uid
        field("0", width = 6)                       // gid
        field("100644", width = 8)                  // mode (S_IFREG | 0644)
        field(data.size.toString(), width = 10)     // size
        sink.writeString(HEADER_END)                // "`\n" end-of-header marker
        sink.write(data)
        if (data.size % 2 == 1) sink.writeByte(NL)  // 2-byte alignment padding
    }

    private fun field(value: String, width: Int) {
        require(value.length <= width) { "ar field '$value' exceeds width $width" }
        sink.writeString(value)
        repeat(width - value.length) { sink.writeByte(SP) }
    }

    private companion object {
        const val MAGIC = "!<arch>\n"
        const val HEADER_END = "`\n"
        const val SP: Byte = ' '.code.toByte()
        const val NL: Byte = '\n'.code.toByte()
    }
}
