package com.lunixdev.puppyterminal

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Installs the pinned Alpine base rootfs and relocatable Android PRoot runtime. */
internal class RootfsManager(context: Context) {
    private val app = context.applicationContext
    private val rootDir = File(app.filesDir, "distros/alpine/3.22.6")
    private val installDir = File(app.filesDir, "distros/alpine")

    data class Runtime(val rootfs: File, val proot: File, val loader: File, val prootTmp: File)

    fun isReady(): Boolean = marker.isFile && marker.readText() == VERSION &&
        File(rootDir, "bin/busybox").canExecute() && File(rootDir, "sbin/apk").canExecute() &&
        runtimeBinary().canExecute()

    fun runtime(): Runtime {
        check(isReady()) { "The Alpine environment is not installed. Start the bootstrap first." }
        installShellIntegration(rootDir)
        return Runtime(rootDir, runtimeBinary(), loaderBinary(), File(app.cacheDir, "proot-tmp").apply { mkdirs() })
    }

    @Throws(IOException::class)
    fun prepare(progress: (String, Int) -> Unit) {
        recoverInterruptedActivation()
        installProot(progress)
        if (isReady()) { progress("Alpine Linux is ready", 100); return }
        installDir.mkdirs()
        val archive = File(installDir, ".rootfs-$ARCH.tar.gz.part")
        val staging = File(installDir, ".rootfs-${UUID.randomUUID()}.staging")
        val backup = File(installDir, ".rootfs-${UUID.randomUUID()}.previous")
        try {
            progress("Downloading Alpine Linux $VERSION", 0)
            downloadRootfs(archive, progress)
            progress("Verifying Alpine root filesystem", 48)
            verifySha256(archive, ROOTFS_SHA256.getValue(ARCH))
            progress("Extracting the private Linux filesystem", 52)
            staging.mkdirs()
            SafeTarExtractor.extractGzipTar(FileInputStream(archive), staging, MAX_ROOTFS_BYTES)
            configureRootfs(staging)
            File(staging, MARKER_NAME).writeText(VERSION)
            installShellIntegration(staging)
            if (rootDir.exists() && !rootDir.renameTo(backup)) throw IOException("Could not preserve the existing Alpine filesystem")
            if (!staging.renameTo(rootDir)) {
                if (backup.exists()) backup.renameTo(rootDir)
                throw IOException("Could not atomically activate the prepared Alpine filesystem")
            }
            if (!isReady()) {
                deleteRecursively(rootDir)
                if (backup.exists() && !backup.renameTo(rootDir)) throw IOException("The new filesystem is invalid and the previous one could not be restored")
                throw IOException("The extracted Alpine filesystem is missing its shell or package manager")
            }
            deleteRecursively(backup)
            progress("Alpine Linux is ready", 100)
        } catch (error: Exception) {
            deleteRecursively(staging)
            if (!rootDir.exists() && backup.exists()) backup.renameTo(rootDir)
            if (error is IOException) throw error
            throw IOException("Linux bootstrap failed: ${error.message ?: error.javaClass.simpleName}", error)
        } finally {
            archive.delete()
        }
    }

    private val marker get() = File(rootDir, MARKER_NAME)

    private fun recoverInterruptedActivation() {
        val backups = installDir.listFiles()?.filter { it.name.startsWith(".rootfs-") && it.name.endsWith(".previous") }.orEmpty()
        if (!rootDir.exists()) {
            val backup = backups.maxByOrNull { it.lastModified() }
            if (backup != null && !backup.renameTo(rootDir)) throw IOException("Could not restore the previous Alpine filesystem")
        }
        backups.forEach { if (it.exists()) deleteRecursively(it) }
    }
    // Android 10+ blocks execve from ordinary app data directories for modern target SDKs.
    // Packaged JNI libraries are extracted to nativeLibraryDir and are an allowed exec location.
    private fun runtimeBinary() = File(app.applicationInfo.nativeLibraryDir, "libpuppyproot.so")
    private fun loaderBinary() = File(app.applicationInfo.nativeLibraryDir, "libpuppyloader.so")

    private fun installProot(progress: (String, Int) -> Unit) {
        progress("Preparing the Android Linux runtime", 0)
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: throw IOException("puppy currently supports arm64-v8a and x86_64 Android devices")
        if (!runtimeBinary().canExecute() || !loaderBinary().canExecute()) {
            throw IOException("The bundled PRoot runtime for $abi is missing or not executable")
        }
    }

    private fun downloadRootfs(destination: File, progress: (String, Int) -> Unit) {
        val file = "alpine-minirootfs-$VERSION-$ARCH.tar.gz"
        try {
            progress("Copying bundled Alpine Linux $VERSION", 0)
            app.assets.open("rootfs/$ARCH/alpine-minirootfs-$VERSION-$ARCH.bin").use { input ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        if (bytes > MAX_DOWNLOAD_BYTES) throw IOException("Bundled Alpine archive exceeds the safety limit")
                        output.write(buffer, 0, n)
                        progress("Copying bundled Alpine Linux $VERSION", (bytes * 45 / 4_000_000).toInt().coerceIn(1, 45))
                    }
                }
            }
            return
        } catch (_: java.io.FileNotFoundException) {
            // Keep a verified HTTPS fallback for development builds missing the bundled asset.
        }
        var url = URL("https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/$ARCH/$file")
        var connection: HttpURLConnection? = null
        try {
            repeat(6) {
                if (url.protocol != "https") throw IOException("Root filesystem server attempted a non-HTTPS redirect")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "puppy-Android/$VERSION")
                }
                val response = connection!!.responseCode
                if (response in 300..399) {
                    val location = connection!!.getHeaderField("Location") ?: throw IOException("Root filesystem server returned an invalid redirect")
                    url = URL(url, location); connection!!.disconnect(); connection = null
                } else if (response == HttpURLConnection.HTTP_OK) {
                    val expectedLength = connection!!.contentLengthLong
                    if (expectedLength > MAX_DOWNLOAD_BYTES) throw IOException("Root filesystem archive exceeds the safety limit")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    BufferedInputStream(connection!!.inputStream).use { input ->
                        BufferedOutputStream(FileOutputStream(destination)).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                bytes += n
                                if (bytes > MAX_DOWNLOAD_BYTES) throw IOException("Root filesystem archive exceeds the safety limit")
                                digest.update(buffer, 0, n); output.write(buffer, 0, n)
                                val percent = if (expectedLength > 0) (bytes * 45 / expectedLength).toInt().coerceIn(1, 45) else 1
                                progress("Downloading Alpine Linux $VERSION", percent)
                            }
                        }
                    }
                    val actual = digest.digest().toHex()
                    if (actual != ROOTFS_SHA256.getValue(ARCH)) throw IOException("Alpine root filesystem SHA-256 mismatch; the partial download was rejected")
                    return
                } else throw IOException("Root filesystem download failed with HTTP $response")
            }
            throw IOException("Too many redirects while downloading Alpine Linux")
        } finally { connection?.disconnect() }
    }

    private fun configureRootfs(root: File) {
        listOf("home/puppy", "tmp", "proc", "sys", "dev", "run", "storage", "usr/local/bin").forEach { File(root, it).mkdirs() }
        File(root, "tmp").setWritable(true, false)
        File(root, "tmp").setExecutable(true, false)
        val pkg = File(root, "usr/local/bin/pkg")
        pkg.writeText("""#!/bin/sh
case "${'$'}{1:-}" in
  install|add) shift; exec apk add "${'$'}@" ;;
  search) shift; exec apk search "${'$'}@" ;;
  update) exec apk update ;;
  upgrade) exec apk upgrade ;;
  remove|del) shift; exec apk del "${'$'}@" ;;
  *) exec apk "${'$'}@" ;;
esac
""")
        if (!pkg.setExecutable(true, false)) throw IOException("Could not enable the pkg command")
        val dns = File(root, "etc/resolv.conf")
        if (!dns.exists() || dns.length() == 0L) {
            val servers = systemDnsServers()
            dns.writeText((servers.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }).joinToString("\n") { "nameserver $it\n" })
        }
        val repositories = File(root, "etc/apk/repositories")
        if (!repositories.exists()) throw IOException("Alpine root filesystem has no apk repository configuration")
    }

    /** Installs versioned puppy hooks without replacing any existing user shell configuration. */
    private fun installShellIntegration(root: File) {
        val home = File(root, "home/puppy")
        val integration = File(home, ".puppy/integration").apply { mkdirs() }
        val scripts = listOf("posix.sh", "bash.sh", "zsh.zsh", "fish.fish")
        for (name in scripts) {
            val target = File(integration, name)
            app.assets.open("integration/$name").use { input ->
                val bytes = input.readBytes()
                if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { it.write(bytes) }
                    target.setReadable(true, true)
                    target.setWritable(true, true)
                }
            }
        }
        val dispatch = """
            # >>> puppy shell integration >>>
            case "${'$'}-" in *i*)
                if [ "${'$'}{PUPPY_SHELL_INTEGRATION:-1}" != 0 ] && [ "${'$'}{PUPPY_SHELL_INTEGRATION_LOADED:-}" != 1 ]; then
                    if [ -n "${'$'}{BASH_VERSION:-}" ]; then . "${'$'}HOME/.puppy/integration/bash.sh"
                    elif [ -n "${'$'}{ZSH_VERSION:-}" ]; then . "${'$'}HOME/.puppy/integration/zsh.zsh"
                    else . "${'$'}HOME/.puppy/integration/posix.sh"; fi
                fi ;;
            esac
            # <<< puppy shell integration <<<
        """.trimIndent()
        listOf(".profile", ".bash_profile", ".bashrc", ".zprofile", ".zshrc").forEach {
            appendManagedBlock(File(home, it), dispatch)
        }
        val fishConfig = File(home, ".config/fish/config.fish")
        appendManagedBlock(fishConfig, """
            # >>> puppy shell integration >>>
            if status is-interactive; and test "${'$'}PUPPY_SHELL_INTEGRATION" != 0
                source "${'$'}HOME/.puppy/integration/fish.fish"
            end
            # <<< puppy shell integration <<<
        """.trimIndent())
        appendManagedBlock(File(root, "etc/profile.d/puppy.sh"), """
            # >>> puppy shell integration >>>
            case "${'$'}-" in *i*)
                if [ "${'$'}{PUPPY_SHELL_INTEGRATION:-1}" != 0 ] && [ "${'$'}{PUPPY_SHELL_INTEGRATION_LOADED:-}" != 1 ]; then
                    if [ -n "${'$'}{BASH_VERSION:-}" ]; then . "${'$'}HOME/.puppy/integration/bash.sh"
                    elif [ -n "${'$'}{ZSH_VERSION:-}" ]; then . "${'$'}HOME/.puppy/integration/zsh.zsh"
                    else . "${'$'}HOME/.puppy/integration/posix.sh"; fi
                fi ;;
            esac
            # <<< puppy shell integration <<<
        """.trimIndent())
    }

    private fun appendManagedBlock(file: File, body: String) {
        file.parentFile?.mkdirs()
        val old = if (file.isFile) file.readText() else ""
        if (old.contains("# >>> puppy shell integration >>>")) return
        FileOutputStream(file, true).bufferedWriter().use { writer ->
            if (old.isNotEmpty() && !old.endsWith('\n')) writer.newLine()
            writer.append(body).append('\n')
        }
    }

    private fun systemDnsServers(): List<String> = listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4").mapNotNull { key ->
        try {
            val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
            val value = process.inputStream.bufferedReader().use { it.readText().trim() }
            if (process.waitFor() == 0 && value.matches(Regex("[0-9a-fA-F:.]+"))) value else null
        } catch (_: Exception) { null }
    }.distinct()

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().toHex()
        if (actual != expected) throw IOException("Alpine root filesystem SHA-256 mismatch")
    }

    private fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun deleteRecursively(file: File) {
        if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return }
        if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
        if (file.exists() && !file.delete()) throw IOException("Could not clean incomplete bootstrap path ${file.name}")
    }

    companion object {
        const val VERSION = "3.22.6"
        private const val MARKER_NAME = ".puppy-rootfs-version"
        private const val MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024
        private const val MAX_ROOTFS_BYTES = 128L * 1024 * 1024
        private val ARCH = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "x86_64" -> "x86_64"
            else -> "aarch64"
        }
        private val ROOTFS_SHA256 = mapOf(
            "aarch64" to "821565fa8f3953eefd12497b166b4b50add2f7c57fb312e75862f5867e06fefe",
            "x86_64" to "27694aaa55fd7a9e3ef596e0ad4eb66802308bb20172b17030cd5f4d8ae9bac2"
        )
    }
}

/** A deliberately small TAR extractor: supports only regular files, directories and links. */
internal object SafeTarExtractor {
    private const val BLOCK = 512

    fun extractGzipTar(source: InputStream, destination: File, maxExpandedBytes: Long) {
        val root = destination.canonicalFile.toPath()
        val seen = hashSetOf<String>()
        val links = ArrayList<Pair<String, String>>()
        val directories = ArrayList<Pair<File, Int>>()
        var expanded = 0L
        GZIPInputStream(BufferedInputStream(source)).use { tar ->
            val header = ByteArray(BLOCK)
            while (readBlock(tar, header)) {
                if (header.all { it == 0.toByte() }) break
                validateHeaderChecksum(header)
                val name = field(header, 0, 100)
                val prefix = field(header, 345, 155)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                val relative = normalizeArchivePath(fullName)
                val size = parseOctal(header, 124, 12)
                val mode = parseOctal(header, 100, 8).toInt() and 0x1ff
                val type = header[156].toInt().toChar()
                if (relative.isNotEmpty() && !seen.add(relative)) throw IOException("Archive contains duplicate path: $relative")
                if (size < 0 || size > maxExpandedBytes || expanded + size > maxExpandedBytes) throw IOException("Root filesystem archive exceeds the extraction limit")
                val target = if (relative.isEmpty()) root.toFile() else root.resolve(relative).toFile()
                when (type) {
                    '\u0000', '0' -> {
                        ensureSafeParents(root, target.toPath().parent)
                        target.parentFile?.mkdirs()
                        if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Archive would overwrite an existing path")
                        FileOutputStream(target).use { output -> copyExact(tar, output, size) }
                        applyMode(target, mode, directory = false)
                        skipPadding(tar, size)
                    }
                    '5' -> {
                        if (size != 0L) throw IOException("Invalid directory entry in root filesystem archive")
                        target.mkdirs(); directories.add(target to mode)
                    }
                    '2' -> {
                        if (size != 0L) throw IOException("Invalid symbolic link entry in root filesystem archive")
                        val link = field(header, 157, 100)
                        validateLink(relative, link)
                        links.add(relative to link)
                    }
                    else -> throw IOException("Unsupported root filesystem archive entry type: $type")
                }
                expanded += size
            }
        }
        directories.sortedByDescending { it.first.toPath().nameCount }.forEach { (dir, mode) -> applyMode(dir, mode, directory = true) }
        for ((relative, link) in links) {
            val path = root.resolve(relative)
            ensureSafeParents(root, path.parent)
            Files.createSymbolicLink(path, Paths.get(link))
        }
    }

    internal fun normalizeArchivePath(raw: String): String {
        if (raw.startsWith('/')) throw IOException("Absolute path in Linux archive")
        val parts = ArrayDeque<String>()
        for (part in raw.removePrefix("./").split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (parts.isEmpty()) throw IOException("Path traversal in Linux archive") else parts.removeLast()
            else -> parts.addLast(part)
        }
        return parts.joinToString("/")
    }

    private fun validateLink(path: String, target: String) {
        if (target.isBlank() || target.indexOf('\u0000') >= 0) throw IOException("Invalid symbolic link in Linux archive")
        val parent = path.substringBeforeLast('/', "")
        val virtualTarget = if (target.startsWith('/')) target.removePrefix("/") else if (parent.isEmpty()) target else "$parent/$target"
        normalizeArchivePath(virtualTarget)
    }

    private fun ensureSafeParents(root: Path, parent: Path?) {
        if (parent == null || !parent.startsWith(root)) throw IOException("Archive path escaped the Linux root")
        var p = root
        for (segment in root.relativize(parent)) {
            p = p.resolve(segment)
            if (Files.isSymbolicLink(p)) throw IOException("Archive path traverses a symbolic link")
            if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(p)
            if (!Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Archive parent path is not a directory")
        }
    }

    private fun applyMode(file: File, mode: Int, directory: Boolean) {
        file.setReadable(mode and 0x124 != 0, false)
        file.setWritable(mode and 0x092 != 0, false)
        file.setExecutable(mode and 0x049 != 0 || directory, false)
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val count = input.read(block, offset, block.size - offset)
            if (count < 0) { if (offset == 0) return false; throw IOException("Truncated TAR header") }
            offset += count
        }
        return true
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(bytes: ByteArray, offset: Int, length: Int): Long {
        val value = String(bytes, offset, length, Charsets.US_ASCII).trim('\u0000', ' ')
        if (value.isEmpty()) return 0
        if (value.any { it !in '0'..'7' }) throw IOException("Invalid TAR numeric field")
        return value.toLongOrNull(8) ?: throw IOException("TAR numeric field overflow")
    }

    private fun validateHeaderChecksum(header: ByteArray) {
        val expected = parseOctal(header, 148, 8)
        var actual = 0L
        for (i in header.indices) actual += if (i in 148..155) 32 else header[i].toInt() and 0xff
        if (actual != expected) throw IOException("Invalid TAR header checksum")
    }

    private fun copyExact(input: InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size; val buffer = ByteArray(64 * 1024)
        while (remaining > 0) { val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) throw IOException("Truncated file in TAR archive"); output.write(buffer, 0, count); remaining -= count }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        var remaining = (BLOCK - size % BLOCK) % BLOCK
        while (remaining > 0) { val skipped = input.skip(remaining); if (skipped > 0) remaining -= skipped else { if (input.read() < 0) throw IOException("Truncated TAR padding"); remaining-- } }
    }
}
