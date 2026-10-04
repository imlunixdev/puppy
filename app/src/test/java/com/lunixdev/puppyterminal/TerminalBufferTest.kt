package com.lunixdev.puppyterminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalBufferTest {
    @Test fun writesTextAndMovesCursor() {
        val b = TerminalBuffer(8, 3)
        b.feed("hello\u001b[2;3Hok")
        assertEquals('h', b.cellAt(0, 0).char)
        assertEquals('o', b.cellAt(2, 1).char)
        assertEquals(4, b.cursorX)
        assertEquals(1, b.cursorY)
    }
    @Test fun handlesTrueColorAndReset() {
        val b = TerminalBuffer(8, 2)
        b.feed("\u001b[38;2;12;34;56mX\u001b[0mY")
        assertEquals(0xff0c2238.toInt(), b.cellAt(0,0).fg)
        assertEquals(TerminalBuffer.DEFAULT_FG, b.cellAt(1,0).fg)
    }
    @Test fun retainsBoundedScrollback() {
        val b = TerminalBuffer(4, 2, 2)
        b.feed("1\r\n2\r\n3\r\n4\r\n5")
        assertTrue(b.totalLines() <= 4)
    }
    @Test fun clearsDisplay() {
        val b = TerminalBuffer(4, 2)
        b.feed("test\u001b[2J")
        assertEquals(' ', b.cellAt(0,0).char)
    }

    @Test fun readsShellIntegrationStateWithoutConsumingVisibleText() {
        val b = TerminalBuffer(20, 2)
        b.feed("\u001b]133;A\u0007prompt")
        assertEquals(TerminalBuffer.ShellMode.AT_SHELL_PROMPT, b.shellMode)
        assertEquals('p', b.cellAt(0, 0).char)
        b.noteInput("\r")
        assertEquals(TerminalBuffer.ShellMode.EXECUTING_COMMAND, b.shellMode)
        b.feed("\u001b]133;D;127\u0007\u001b]133;A\u001b\\")
        assertEquals(127, b.lastCommandExitStatus)
        assertEquals(TerminalBuffer.ShellMode.AT_SHELL_PROMPT, b.shellMode)
    }

    @Test fun alternateScreenDisablesShellPromptMode() {
        val b = TerminalBuffer(20, 2)
        b.feed("\u001b]133;A\u0007")
        b.feed("\u001b[?1049h")
        assertEquals(TerminalBuffer.ShellMode.FULLSCREEN_TUI, b.shellMode)
        b.feed("\u001b[?1049l")
        assertEquals(TerminalBuffer.ShellMode.EXECUTING_COMMAND, b.shellMode)
    }

    @Test fun supportsItalicDimAndUnderlineAttributesAndReset() {
        val b = TerminalBuffer(8, 2)
        b.feed("\u001b[2;3;4mX\u001b[0mY")
        assertTrue(b.cellAt(0, 0).dim)
        assertTrue(b.cellAt(0, 0).italic)
        assertTrue(b.cellAt(0, 0).underline)
        assertFalse(b.cellAt(1, 0).dim)
        assertFalse(b.cellAt(1, 0).italic)
        assertFalse(b.cellAt(1, 0).underline)
    }

    @Test fun safelyExtractsFilesAndDefersLinks() {
        val target = Files.createTempDirectory("puppy-rootfs-test").toFile()
        SafeTarExtractor.extractGzipTar(ByteArrayInputStream(tarGzip(listOf(
            TarEntry("etc", '5'),
            TarEntry("etc/os-release", '0', "NAME=Alpine\n".toByteArray()),
            TarEntry("bin/sh", '2', link = "/bin/busybox"),
            TarEntry("bin/busybox", '0', byteArrayOf(0x42), mode = 0b111101101)
        ))), target, 1024)
        assertEquals("NAME=Alpine\n", File(target, "etc/os-release").readText())
        assertTrue(Files.isSymbolicLink(File(target, "bin/sh").toPath()))
        assertTrue(File(target, "bin/busybox").canExecute())
        target.deleteRecursively()
    }

    @Test fun rejectsTarPathTraversalBeforeWritingOutsideRoot() {
        val parent = Files.createTempDirectory("puppy-safe-tar").toFile()
        val target = File(parent, "rootfs").apply { mkdirs() }
        try {
            SafeTarExtractor.extractGzipTar(ByteArrayInputStream(tarGzip(listOf(TarEntry("../escaped", '0', byteArrayOf(1))))), target, 1024)
            fail("path traversal archive should be rejected")
        } catch (_: java.io.IOException) { }
        assertFalse(File(parent, "escaped").exists())
        target.deleteRecursively(); parent.deleteRecursively()
    }

    @Test fun rejectsAbsoluteTarPaths() {
        try {
            SafeTarExtractor.normalizeArchivePath("/etc/passwd")
            fail("absolute paths should be rejected")
        } catch (_: java.io.IOException) { }
    }

    private data class TarEntry(val name: String, val type: Char, val bytes: ByteArray = byteArrayOf(), val link: String = "", val mode: Int = 0b111101101)
    private fun tarGzip(entries: List<TarEntry>): ByteArray {
        val tar = ByteArrayOutputStream()
        for (entry in entries) {
            val header = ByteArray(512)
            fun put(offset: Int, length: Int, value: String) {
                val bytes = value.toByteArray(StandardCharsets.US_ASCII)
                bytes.copyInto(header, offset, 0, minOf(bytes.size, length - 1))
            }
            fun octal(offset: Int, length: Int, value: Long) { put(offset, length, value.toString(8).padStart(length - 1, '0')) }
            put(0, 100, entry.name); octal(100, 8, entry.mode.toLong()); octal(108, 8, 0); octal(116, 8, 0)
            octal(124, 12, entry.bytes.size.toLong()); octal(136, 12, 0)
            for (i in 148 until 156) header[i] = 32
            header[156] = entry.type.code.toByte(); put(157, 100, entry.link); put(257, 6, "ustar"); put(263, 2, "00")
            val checksum = header.sumOf { it.toInt() and 0xff }
            checksum.toString(8).padStart(6, '0').toByteArray(StandardCharsets.US_ASCII).copyInto(header, 148)
            header[154] = 0; header[155] = 32
            tar.write(header); tar.write(entry.bytes); repeat((512 - entry.bytes.size % 512) % 512) { tar.write(0) }
        }
        tar.write(ByteArray(1024))
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(tar.toByteArray()) }
        return compressed.toByteArray()
    }
}
