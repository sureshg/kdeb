# 📦 kdeb

Kotlin Multiplatform library for building Debian (`.deb`) packages with a type-safe DSL. Pure Kotlin — no `dpkg-deb` or
other native tools required.

## Usage

```kotlin
val pkg = debPackage {
    control {
        packageName = "hello"
        version = "1.0-1"
        architecture = "amd64"
        maintainer = "Jane Doe <jane@example.com>"
        description = "Friendly greeting program"
        section = "utils"
        priority = "optional"
        depends("libc6 (>= 2.14)")
    }
    data {
        directory("/usr/bin")
        file("/usr/bin/hello", scriptBytes, mode = 0b111_101_101)
        file("/usr/share/doc/hello/copyright", copyrightText)
    }
    postinst("#!/bin/sh\nset -e\necho 'hello installed'")
}

SystemFileSystem.sink(Path("hello_1.0-1_amd64.deb")).buffered().use { pkg.writeTo(it) }
```

## What it does

- Creates spec-compliant `.deb` archives (`ar` + `control.tar.gz` + `data.tar.gz`)
- Generates control files, md5sums, and maintainer scripts (`preinst`, `postinst`, `prerm`, `postrm`)
- Creates missing parent directories automatically
- Supports reproducible builds with a fixed `buildTime`

## Building

The project uses [Amper](https://github.com/JetBrains/amper). From the project root:

```sh
./amper build -v release
```

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.
