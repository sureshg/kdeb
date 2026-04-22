package deb

import kotlinx.io.Sink

/**
 * POSIX `ustar` tar writer with a GNU `LongLink` ('L') extension for paths
 * longer than 100 bytes. This matches the output of `dpkg-deb` / `jdeb`
 * and is required for long binary paths that exceed the ustar name field.
 *
 * Layout: 512-byte header + content padded to 512; archive ends with two
 * zero blocks (GNU tar convention). Paths are emitted with a leading "./"
 * to match `dpkg-deb` output.
 */
class TarWriter(private val sink: Sink) {

    fun writeFile(
        name: String,
        content: ByteArray,
        mode: Int = 0b110_100_100,
        uid: Int = 0,
        gid: Int = 0,
        user: String = "root",
        group: String = "root",
        mtime: Long,
    ) {
        writeEntry(name, TYPE_FILE, content.size.toLong(), mode, uid, gid, user, group, mtime)
        sink.write(content)
        padToBlock(content.size)
    }

    fun writeDirectory(
        name: String,
        mode: Int = 0b111_101_101,
        uid: Int = 0,
        gid: Int = 0,
        user: String = "root",
        group: String = "root",
        mtime: Long,
    ) {
        val normalized = if (name.endsWith('/')) name else "$name/"
        writeEntry(normalized, TYPE_DIR, 0L, mode, uid, gid, user, group, mtime)
    }

    /** Two zero blocks mark the end of the archive. */
    fun finish() {
        sink.write(ByteArray(BLOCK * 2))
    }

    // ---- internals ---------------------------------------------------------

    private fun writeEntry(
        name: String,
        typeflag: Byte,
        size: Long,
        mode: Int,
        uid: Int,
        gid: Int,
        user: String,
        group: String,
        mtime: Long,
    ) {
        val nameBytes = name.encodeToByteArray()
        val headerName = if (nameBytes.size <= NAME_MAX) {
            nameBytes
        } else {
            // Emit a GNU LongLink ('L') entry with the full path as payload,
            // then a regular header carrying a truncated name (dpkg accepts this).
            val payload = nameBytes + 0.toByte()
            writeHeader(LONGLINK_NAME, TYPE_LONGLINK, payload.size.toLong(),
                mode = 0, uid = 0, gid = 0, user = "root", group = "root", mtime = 0)
            sink.write(payload)
            padToBlock(payload.size)
            nameBytes.copyOf(NAME_MAX)
        }
        writeHeader(headerName, typeflag, size, mode, uid, gid, user, group, mtime)
    }

    private fun writeHeader(
        name: String,
        typeflag: Byte,
        size: Long,
        mode: Int, uid: Int, gid: Int, user: String, group: String, mtime: Long,
    ) = writeHeader(name.encodeToByteArray(), typeflag, size, mode, uid, gid, user, group, mtime)

    private fun writeHeader(
        nameBytes: ByteArray,
        typeflag: Byte,
        size: Long,
        mode: Int, uid: Int, gid: Int, user: String, group: String, mtime: Long,
    ) {
        val userBytes = user.encodeToByteArray()
        val groupBytes = group.encodeToByteArray()
        require(nameBytes.size <= NAME_MAX) { "ustar name >$NAME_MAX bytes" }
        require(userBytes.size <= UNAME_MAX) { "ustar uname >$UNAME_MAX bytes" }
        require(groupBytes.size <= UNAME_MAX) { "ustar gname >$UNAME_MAX bytes" }

        val h = ByteArray(BLOCK)
        nameBytes.copyInto(h, destinationOffset = 0)
        writeOctal(h, offset = 100, width = 8,  value = mode.toLong() and 0xFFF)
        writeOctal(h, offset = 108, width = 8,  value = uid.toLong())
        writeOctal(h, offset = 116, width = 8,  value = gid.toLong())
        writeOctal(h, offset = 124, width = 12, value = size)
        writeOctal(h, offset = 136, width = 12, value = mtime)
        h.fill(SP, fromIndex = 148, toIndex = 156)      // checksum placeholder (8 spaces)
        h[156] = typeflag
        // 157..257: linkname — unused (no symlinks are emitted).
        USTAR_MAGIC.copyInto(h, destinationOffset = 257)
        userBytes.copyInto(h, destinationOffset = 265)
        groupBytes.copyInto(h, destinationOffset = 297)
        // devmajor/devminor/prefix left as NUL — not needed for regular files.

        writeChecksum(h)
        sink.write(h)
    }

    /** Compute and write the ustar header checksum (POSIX: sum of all bytes
     *  with the checksum field initially treated as 8 spaces). */
    private fun writeChecksum(h: ByteArray) {
        var sum = 0
        for (b in h) sum += (b.toInt() and 0xFF)
        val digits = sum.toString(radix = 8).padStart(6, '0')
        for (i in 0 until 6) h[148 + i] = digits[i].code.toByte()
        h[154] = 0
        h[155] = SP
    }

    private fun padToBlock(writtenBytes: Int) {
        val rem = writtenBytes % BLOCK
        if (rem != 0) sink.write(ByteArray(BLOCK - rem))
    }

    private fun writeOctal(buf: ByteArray, offset: Int, width: Int, value: Long) {
        require(value >= 0) { "negative octal value: $value" }
        // ustar: (width - 1) octal digits, zero-padded, NUL-terminated.
        val s = value.toString(radix = 8).padStart(width - 1, '0')
        require(s.length == width - 1) { "value $value overflows $width-byte octal field" }
        for (i in 0 until width - 1) buf[offset + i] = s[i].code.toByte()
        buf[offset + width - 1] = 0
    }

    private companion object {
        const val BLOCK = 512
        const val NAME_MAX = 100
        const val UNAME_MAX = 31

        const val TYPE_FILE: Byte = '0'.code.toByte()
        const val TYPE_DIR: Byte = '5'.code.toByte()
        const val TYPE_LONGLINK: Byte = 'L'.code.toByte()
        const val SP: Byte = ' '.code.toByte()

        const val LONGLINK_NAME = "././@LongLink"

        /** "ustar\0" + version "00" — the POSIX ustar magic. */
        val USTAR_MAGIC: ByteArray = "ustar\u000000".encodeToByteArray()
    }
}
