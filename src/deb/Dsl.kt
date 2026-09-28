package deb

import kotlin.time.Clock
import kotlin.time.Instant

@DslMarker
annotation class DebDsl

/**
 * Entry point for the Debian package DSL.
 *
 * ```
 * val pkg = debPackage {
 *     control { ... }
 *     data    { ... }
 *     postinst("#!/bin/sh\n...")
 * }
 * ```
 */
fun debPackage(block: DebPackageBuilder.() -> Unit): DebPackage =
    DebPackageBuilder().apply(block).build()

@DebDsl
class DebPackageBuilder {
    /** Single mtime for every archive entry → reproducible builds. */
    var buildTime: Instant = Clock.System.now()

    private var meta: PackageMeta? = null
    private val entries = mutableListOf<DataEntry>()
    private var scripts = MaintainerScripts()

    fun control(block: ControlBuilder.() -> Unit) {
        meta = ControlBuilder().apply(block).build()
    }

    fun data(block: DataBuilder.() -> Unit) {
        entries += DataBuilder(buildTime).apply(block).entries
    }

    fun preinst(script: String) {
        scripts = scripts.copy(preinst = script)
    }

    fun postinst(script: String) {
        scripts = scripts.copy(postinst = script)
    }

    fun prerm(script: String) {
        scripts = scripts.copy(prerm = script)
    }

    fun postrm(script: String) {
        scripts = scripts.copy(postrm = script)
    }

    fun build(): DebPackage = DebPackage(
        meta = requireNotNull(meta) { "control { } block is required" },
        entries = entries.toList(),
        scripts = scripts,
    )
}

@DebDsl
class ControlBuilder {
    lateinit var packageName: String
    lateinit var version: String
    lateinit var architecture: String
    lateinit var maintainer: String
    lateinit var description: String
    var section: String? = null
    var priority: String? = null

    private val depends = mutableListOf<String>()

    fun depends(vararg specs: String) {
        depends += specs
    }

    fun build(): PackageMeta = PackageMeta(
        name = packageName,
        version = version,
        architecture = architecture,
        maintainer = maintainer,
        description = description,
        section = section,
        priority = priority,
        depends = depends.toList(),
    )
}

@DebDsl
class DataBuilder(private val defaultMtime: Instant) {
    val entries: MutableList<DataEntry> = mutableListOf()

    fun directory(path: String, mode: Int = 0b111_101_101) {
        entries += DataEntry.Directory(path, mode = mode, mtime = defaultMtime)
    }

    fun file(path: String, content: ByteArray, mode: Int = 0b110_100_100) {
        entries += DataEntry.File(path, content, mode = mode, mtime = defaultMtime)
    }

    fun file(path: String, content: String, mode: Int = 0b110_100_100): Unit =
        file(path, content.encodeToByteArray(), mode)
}
