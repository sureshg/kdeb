import deb.debPackage
import deb.writeTo
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

fun main() {
    val binaryBytes: ByteArray = "#!/bin/sh\necho hello1\n".encodeToByteArray()
    val copyrightText: String = "Copyright (c) 2025 Jane Doe. MIT License.\n"

    val pkg = debPackage {
        control {
            packageName = "hello1"
            version = "1.0-1"
            architecture = "amd64"
            maintainer = "Jane Doe <jane@example.com>"
            description = """
            Friendly greeting program
            Prints hello1. A longer description goes here.
            
            Multiple paragraphs are supported.
        """.trimIndent()
            section = "utils"
            priority = "optional"
            depends("libc6 (>= 2.14)")
        }
        data {
            directory("/usr/bin")
            directory("/usr/share/doc/hello1")
            file("/usr/bin/hello1", binaryBytes, mode = 0b111_101_101)
            file("/usr/share/doc/hello1/copyright", copyrightText)
        }
        postinst(
            """
        #!/bin/sh
        set -e
        echo "hello1 installed"
    """.trimIndent()
        )
    }

    SystemFileSystem.sink(Path("hello1_1.0-0_amd64.deb")).buffered().use { pkg.writeTo(it) }
}
