package deb

import kotlin.time.Instant

/** Control-file metadata for a Debian binary package (see deb-control(5)). */
data class PackageMeta(
    val name: String,
    val version: String,
    val architecture: String,          // "amd64", "arm64", "all", ...
    val maintainer: String,            // "Name <email>"
    val description: String,           // first line = summary; rest = long desc
    val section: String? = null,
    val priority: String? = null,
    val depends: List<String> = emptyList(),
)

/** A single file or directory to be installed by the package. */
sealed interface DataEntry {
    val targetPath: String
    val mode: Int
    val uid: Int
    val gid: Int
    val user: String
    val group: String
    val mtime: Instant

    data class File(
        override val targetPath: String,
        val content: ByteArray,
        override val mode: Int = 0b110_100_100,          // 0644
        override val uid: Int = 0,
        override val gid: Int = 0,
        override val user: String = "root",
        override val group: String = "root",
        override val mtime: Instant,
    ) : DataEntry

    data class Directory(
        override val targetPath: String,
        override val mode: Int = 0b111_101_101,          // 0755
        override val uid: Int = 0,
        override val gid: Int = 0,
        override val user: String = "root",
        override val group: String = "root",
        override val mtime: Instant,
    ) : DataEntry
}

/** Optional maintainer scripts; always written with mode 0755 by [writeTo]. */
data class MaintainerScripts(
    val preinst: String? = null,
    val postinst: String? = null,
    val prerm: String? = null,
    val postrm: String? = null,
)

/** A fully-described Debian package, ready to be serialized via [writeTo]. */
data class DebPackage(
    val meta: PackageMeta,
    val entries: List<DataEntry>,
    val scripts: MaintainerScripts = MaintainerScripts(),
)
