package com.lunixdev.puppyterminal

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

@SuppressLint("ViewConstructor")
internal class TerminalView(context: Context, private val input: (String) -> Unit, private val resizePty: (Int, Int) -> Unit) : View(context) {
    val buffer = TerminalBuffer()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("monospace", Typeface.NORMAL); textSize = 16f }
    private var cellW = 9f; private var cellH = 20f
    init { isFocusable = true; isFocusableInTouchMode = true; setBackgroundColor(TerminalBuffer.DEFAULT_BG) }
    fun append(value: String) { buffer.feed(value); if (windowVisibility == View.VISIBLE) postInvalidateOnAnimation() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) postInvalidateOnAnimation()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cols = (w / cellW).toInt().coerceAtLeast(20); val rows = (h / cellH).toInt().coerceAtLeast(5)
        buffer.resize(cols, rows)
        resizePty(rows, cols)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(TerminalBuffer.DEFAULT_BG)
        paint.textSize = 16f; cellW = paint.measureText("M"); cellH = paint.fontSpacing
        val rows = (height / cellH).toInt().coerceAtLeast(1); val cols = (width / cellW).toInt().coerceAtLeast(1)
        buffer.resize(cols, rows)
        val startLine = (buffer.totalLines() - rows).coerceAtLeast(0)
        val fm = paint.fontMetrics; val base = -fm.ascent
        for (dy in 0 until rows) {
            val lineNo = startLine + dy
            if (lineNo >= buffer.totalLines()) break
            val line = buffer.historicalLine(lineNo)
            for (x in 0 until minOf(cols, line.size)) {
                val cell = line[x]; val left = x * cellW; val top = dy * cellH
                if (cell.bg != TerminalBuffer.DEFAULT_BG) { paint.color=cell.bg; canvas.drawRect(left,top,left+cellW,top+cellH,paint) }
                if (cell.char != ' ') {
                    paint.color = cell.fg
                    paint.alpha = if (cell.dim) 150 else 255
                    paint.isFakeBoldText = cell.bold
                    paint.textSkewX = if (cell.italic) -0.18f else 0f
                    canvas.drawText(cell.char.toString(), left, top + base, paint)
                    if (cell.underline) canvas.drawRect(left, top + cellH - 2f, left + cellW, top + cellH - 1f, paint)
                    paint.alpha = 255
                    paint.textSkewX = 0f
                }
            }
        }
        paint.isFakeBoldText=false; paint.alpha=255; paint.textSkewX=0f; paint.color=0xfff0c674.toInt()
        val cy = (buffer.cursorY + buffer.totalLines() - rows).coerceIn(0,rows-1)
        canvas.drawRect(buffer.cursorX*cellW, cy*cellH, buffer.cursorX*cellW+cellW, cy*cellH+2f, paint)
    }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean { if (text != null) input(text.toString()); return true }
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return true
                val seq = when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_ENTER -> "\r"
                    android.view.KeyEvent.KEYCODE_DEL -> "\u007f"
                    android.view.KeyEvent.KEYCODE_TAB -> "\t"
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
                    android.view.KeyEvent.KEYCODE_ESCAPE -> "\u001b"
                    else -> return super.sendKeyEvent(event)
                }; input(seq); return true
            }
        }
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed && event.unicodeChar != 0) { input((event.unicodeChar and 0x1f).toChar().toString()); return true }
        return super.onKeyDown(keyCode,event)
    }
}
