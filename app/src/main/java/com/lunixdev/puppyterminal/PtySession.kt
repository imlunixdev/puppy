package com.lunixdev.puppyterminal

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class PtySession(private val onOutput: (String) -> Unit, private val onFailure: (String) -> Unit) {
    companion object { init { System.loadLibrary("puppypty") } }
    private external fun nativeStart(rootfs: String, proot: String, loader: String, prootTmp: String, rows: Int, cols: Int, shellIntegration: Boolean): Long
    private external fun nativeRead(handle: Long, target: ByteArray): Int
    private external fun nativeWrite(handle: Long, source: ByteArray): Int
    private external fun nativeResize(handle: Long, rows: Int, cols: Int)
    private external fun nativeClose(handle: Long)
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val outputLock = Any()
    private val pendingOutput = StringBuilder()
    private val flushScheduled = AtomicBoolean(false)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pendingBytes = ByteArray(0)
    @Volatile private var handle = -1L
    fun start(runtime: RootfsManager.Runtime, shellIntegration: Boolean) {
        if (running.get()) return
        handle = nativeStart(runtime.rootfs.absolutePath, runtime.proot.absolutePath, runtime.loader.absolutePath, runtime.prootTmp.absolutePath, 24, 80, shellIntegration); running.set(true)
        pool.execute {
            val buffer = ByteArray(8192)
            try {
                while (running.get()) {
                    val count = nativeRead(handle, buffer)
                    if (count > 0) {
                        val chunk = decodeUtf8(buffer, count)
                        if (chunk.isNotEmpty()) publishOutput(chunk)
                    } else if (count == -2) {
                        if (running.getAndSet(false)) main.post { onFailure("Shell process exited") }
                        break
                    } else Thread.sleep(8)
                }
            } catch (e: Exception) { if (running.get()) main.post { onFailure(e.message ?: "Terminal process stopped unexpectedly") } }
        }
    }

    private fun publishOutput(chunk: String) {
        synchronized(outputLock) { pendingOutput.append(chunk) }
        if (flushScheduled.compareAndSet(false, true)) main.postDelayed(::flushOutput, 16)
    }

    private fun flushOutput() {
        val chunk = synchronized(outputLock) {
            val value = pendingOutput.toString()
            pendingOutput.setLength(0)
            value
        }
        flushScheduled.set(false)
        if (chunk.isNotEmpty() && running.get()) onOutput(chunk)
        val hasMore = synchronized(outputLock) { pendingOutput.isNotEmpty() }
        if (hasMore && flushScheduled.compareAndSet(false, true)) main.postDelayed(::flushOutput, 16)
    }
    private fun decodeUtf8(bytes: ByteArray, count: Int): String {
        val input = ByteBuffer.allocate(pendingBytes.size + count).put(pendingBytes).put(bytes, 0, count)
        input.flip()
        val chars = CharBuffer.allocate((input.remaining() * decoder.maxCharsPerByte()).toInt() + 4)
        decoder.decode(input, chars, false)
        pendingBytes = ByteArray(input.remaining()).also { input.get(it) }
        chars.flip()
        return chars.toString()
    }
    @Synchronized fun send(text: String) {
        val current = handle; if (!running.get() || current < 0L) return
        try { nativeWrite(current, text.toByteArray(StandardCharsets.UTF_8)) }
        catch (e: IOException) { main.post { onFailure(e.message ?: "Could not write to terminal") } }
    }
    fun resize(rows: Int, cols: Int) { val current=handle; if (running.get() && current >= 0L) nativeResize(current, rows, cols) }
    @Synchronized fun close() {
        if (running.getAndSet(false)) { val current=handle; handle=-1L; if(current>=0L) nativeClose(current) }
        synchronized(outputLock) { pendingOutput.setLength(0) }
        pool.shutdownNow()
    }
}
