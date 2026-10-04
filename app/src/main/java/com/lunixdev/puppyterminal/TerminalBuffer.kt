package com.lunixdev.puppyterminal

/** A bounded, batched cell buffer with the CSI controls needed by common shells. */
internal class TerminalBuffer(private var columns: Int = 80, private var rows: Int = 24, private val historyLimit: Int = 10_000) {
    data class Cell(
        val char: Char = ' ', val fg: Int = DEFAULT_FG, val bg: Int = DEFAULT_BG,
        val bold: Boolean = false, val dim: Boolean = false, val italic: Boolean = false, val underline: Boolean = false
    )
    enum class ShellMode { AT_SHELL_PROMPT, EXECUTING_COMMAND, FULLSCREEN_TUI, RAW_INPUT_APPLICATION }
    private val scrollback = arrayOfNulls<MutableList<Cell>>(historyLimit.coerceAtLeast(1))
    private var historyHead = 0
    private var historyCount = 0
    private var screen = Array(rows) { blankRow() }
    private var savedScreen: Array<MutableList<Cell>>? = null
    var cursorX = 0; private set
    var cursorY = 0; private set
    private var fg = DEFAULT_FG; private var bg = DEFAULT_BG; private var bold = false
    private var dim = false; private var italic = false; private var underline = false
    var shellMode: ShellMode = ShellMode.RAW_INPUT_APPLICATION; private set
    var lastCommandExitStatus: Int? = null; private set
    private var state = State.TEXT
    private val control = StringBuilder()
    private enum class State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    fun feed(text: String) {
        for (c in text) when (state) {
            State.TEXT -> when (c) {
                '\u001b' -> state = State.ESCAPE
                '\n' -> lineFeed()
                '\r' -> cursorX = 0
                '\b' -> cursorX = (cursorX - 1).coerceAtLeast(0)
                '\u007f' -> cursorX = (cursorX - 1).coerceAtLeast(0)
                '\t' -> cursorX = ((cursorX / 8 + 1) * 8).coerceAtMost(columns - 1)
                else -> if (c >= ' ') put(c)
            }
            State.ESCAPE -> {
                if (c == '[') { control.setLength(0); state = State.CSI }
                else if (c == ']') { control.setLength(0); state = State.OSC }
                else if (c == '7') { savedX = cursorX; savedY = cursorY; state = State.TEXT }
                else if (c == '8') { cursorX = savedX.coerceIn(0, columns - 1); cursorY = savedY.coerceIn(0, rows - 1); state = State.TEXT }
                else state = State.TEXT
            }
            State.CSI -> if (c in '@'..'~') { executeCsi(c, control.toString()); state = State.TEXT } else if (control.length < 128) control.append(c)
            State.OSC -> when (c) {
                '\u0007' -> { processOsc(control.toString()); control.setLength(0); state = State.TEXT }
                '\u001b' -> state = State.OSC_ESCAPE
                else -> if (control.length < 256) control.append(c)
            }
            State.OSC_ESCAPE -> if (c == '\\') { processOsc(control.toString()); control.setLength(0); state = State.TEXT } else {
                if (control.length < 256) { control.append('\u001b'); control.append(c) }
                state = State.OSC
            }
        }
    }

    /** Consumes explicit shell markers only; ordinary PTY input remains untouched. */
    private fun processOsc(value: String) {
        if (!value.startsWith("133;")) return
        val marker = value.removePrefix("133;")
        when (marker) {
            "A", "B" -> if (shellMode != ShellMode.FULLSCREEN_TUI) shellMode = ShellMode.AT_SHELL_PROMPT
            "C" -> if (shellMode != ShellMode.FULLSCREEN_TUI) shellMode = ShellMode.EXECUTING_COMMAND
            else -> if (marker.startsWith("D;")) {
                lastCommandExitStatus = marker.substringAfter(';').toIntOrNull()
                if (shellMode != ShellMode.FULLSCREEN_TUI) shellMode = ShellMode.EXECUTING_COMMAND
            }
        }
    }

    fun noteInput(text: String) {
        if (shellMode == ShellMode.AT_SHELL_PROMPT && ('\r' in text || '\n' in text)) shellMode = ShellMode.EXECUTING_COMMAND
    }

    private var savedX = 0; private var savedY = 0
    private fun put(c: Char) {
        screen[cursorY][cursorX] = Cell(c, fg, bg, bold, dim, italic, underline)
        cursorX++
        if (cursorX >= columns) { cursorX = 0; lineFeed() }
    }
    private fun lineFeed() {
        if (cursorY == rows - 1) {
            if (historyLimit > 0) {
                val slot = if (historyCount < historyLimit) {
                    (historyHead + historyCount++) % historyLimit
                } else {
                    historyHead.also { historyHead = (historyHead + 1) % historyLimit }
                }
                scrollback[slot] = screen[0]
            }
            for (i in 0 until rows - 1) screen[i] = screen[i + 1]
            screen[rows - 1] = blankRow()
        } else cursorY++
    }
    private fun executeCsi(command: Char, raw: String) {
        val values = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun p(i: Int, default: Int = 1) = (values.getOrNull(i) ?: 0).let { if (it == 0) default else it }
        when (command) {
            'H', 'f' -> { cursorY = (p(0) - 1).coerceIn(0, rows - 1); cursorX = (p(1) - 1).coerceIn(0, columns - 1) }
            'A' -> cursorY = (cursorY - p(0)).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + p(0)).coerceAtMost(rows - 1)
            'C' -> cursorX = (cursorX + p(0)).coerceAtMost(columns - 1)
            'D' -> cursorX = (cursorX - p(0)).coerceAtLeast(0)
            'G' -> cursorX = (p(0) - 1).coerceIn(0, columns - 1)
            'J' -> when (values.firstOrNull() ?: 0) { 2, 3 -> { screen = Array(rows) { blankRow() }; if (values.first() == 3) { historyHead=0; historyCount=0; scrollback.fill(null) }; cursorX = 0; cursorY = 0 }; 0 -> { clearRowFromCursor(); for (y in cursorY + 1 until rows) screen[y] = blankRow() } }
            'K' -> when (values.firstOrNull() ?: 0) { 1 -> for (x in 0..cursorX) screen[cursorY][x] = Cell(); 2 -> screen[cursorY] = blankRow(); else -> clearRowFromCursor() }
            'm' -> sgr(values)
            'h', 'l' -> if (raw.startsWith("?1049")) {
                if (command == 'h') { savedScreen = screen; screen = Array(rows) { blankRow() }; cursorX = 0; cursorY = 0; shellMode = ShellMode.FULLSCREEN_TUI }
                else { screen = savedScreen ?: screen; savedScreen = null; cursorX = 0; cursorY = 0; shellMode = ShellMode.EXECUTING_COMMAND }
            }
            's' -> { savedX = cursorX; savedY = cursorY }
            'u' -> { cursorX = savedX.coerceIn(0, columns - 1); cursorY = savedY.coerceIn(0, rows - 1) }
        }
    }
    private fun clearRowFromCursor() { for (x in cursorX until columns) screen[cursorY][x] = Cell() }
    private fun sgr(v: List<Int>) {
        var i = 0
        while (i < v.size) {
            when (v[i]) {
                0 -> { fg = DEFAULT_FG; bg = DEFAULT_BG; bold = false; dim = false; italic = false; underline = false }
                1 -> bold = true
                2 -> dim = true
                3 -> italic = true
                4 -> underline = true
                22 -> { bold = false; dim = false }
                23 -> italic = false
                24 -> underline = false
                in 30..37 -> fg = ansi(v[i] - 30)
                in 90..97 -> fg = ansi(v[i] - 90 + 8)
                in 40..47 -> bg = ansi(v[i] - 40)
                in 100..107 -> bg = ansi(v[i] - 100 + 8)
                39 -> fg = DEFAULT_FG
                49 -> bg = DEFAULT_BG
                38, 48 -> if (v.getOrNull(i + 1) == 2 && v.size > i + 4) {
                    val color = rgb(v[i + 2], v[i + 3], v[i + 4])
                    if (v[i] == 38) fg = color else bg = color
                    i += 4
                } else if (v.getOrNull(i + 1) == 5 && v.size > i + 2) {
                    if (v[i] == 38) fg = ansi256(v[i + 2]) else bg = ansi256(v[i + 2]); i += 2
                }
            }; i++
        }
    }
    fun resize(cols: Int, rowCount: Int) {
        val nc = cols.coerceAtLeast(1); val nr = rowCount.coerceAtLeast(1)
        if (nc == columns && nr == rows) return
        val old = screen; val copyRows = minOf(rows, nr); val new = Array(nr) { blankRow(nc) }
        for (y in 0 until copyRows) for (x in 0 until minOf(columns, nc)) new[y][x] = old[y][x]
        screen = new; columns = nc; rows = nr; cursorX = cursorX.coerceIn(0, nc - 1); cursorY = cursorY.coerceIn(0, nr - 1)
    }
    fun cellAt(x: Int, y: Int): Cell = screen[y][x]
    fun totalLines() = historyCount + rows
    fun historicalLine(y: Int): List<Cell> = if (y < historyCount) scrollback[(historyHead + y) % historyLimit]!! else screen[y - historyCount]
    private fun blankRow(width: Int = columns) = MutableList(width) { Cell() }
    companion object {
        const val DEFAULT_FG = 0xffd5d9e0.toInt(); const val DEFAULT_BG = 0xff111318.toInt()
        fun ansi(n: Int): Int = COLORS[n.coerceIn(0,15)]
        fun ansi256(n: Int): Int = when {
            n < 16 -> ansi(n)
            n >= 232 -> { val v = 8 + (n - 232) * 10; rgb(v,v,v) }
            else -> {
                val k = n - 16
                fun ch(v: Int): Int = if (v == 0) 0 else 55 + v * 40
                rgb(ch(k / 36), ch((k / 6) % 6), ch(k % 6))
            }
        }
        private fun rgb(r:Int,g:Int,b:Int) = (0xff shl 24) or (r.coerceIn(0,255) shl 16) or (g.coerceIn(0,255) shl 8) or b.coerceIn(0,255)
        private val COLORS = intArrayOf(0xff16181d.toInt(),0xffe06c75.toInt(),0xff98c379.toInt(),0xffe5c07b.toInt(),0xff61afef.toInt(),0xffc678dd.toInt(),0xff56b6c2.toInt(),0xffabb2bf.toInt(),0xff5c6370.toInt(),0xffe06c75.toInt(),0xff98c379.toInt(),0xffe5c07b.toInt(),0xff61afef.toInt(),0xffc678dd.toInt(),0xff56b6c2.toInt(),0xffffffff.toInt())
    }
}
