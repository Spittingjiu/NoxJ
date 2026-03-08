package com.noxcore.noxdroid.core.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsLog {

    enum class Level { INFO, WARN, ERROR }

    private val lock = Any()
    private val tailLines = ArrayDeque<String>()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val _tailText = MutableStateFlow("")

    private var file: File? = null

    val tailText: StateFlow<String> = _tailText.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file != null) {
                return
            }
            file = File(context.filesDir, LOG_FILE_NAME)
            loadTailLocked()
            publishTailLocked()
        }
    }

    fun info(tag: String, message: String) {
        append(Level.INFO, tag, message)
    }

    fun warn(tag: String, message: String) {
        append(Level.WARN, tag, message)
    }

    fun error(tag: String, message: String) {
        append(Level.ERROR, tag, message)
    }

    private fun append(level: Level, tag: String, message: String) {
        val line = formatLine(level, tag, message)
        when (level) {
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
        }

        synchronized(lock) {
            tailLines.addLast(line)
            trimTailLocked()
            publishTailLocked()

            val logFile = file ?: return
            try {
                rotateIfNeededLocked(logFile)
                logFile.appendText(line + "\n")
            } catch (_: Exception) {
                // Best-effort diagnostics persistence only.
            }
        }
    }

    private fun loadTailLocked() {
        val logFile = file ?: return
        if (!logFile.exists()) {
            return
        }
        try {
            val lines = logFile.readLines()
            val start = (lines.size - MAX_TAIL_LINES).coerceAtLeast(0)
            for (i in start until lines.size) {
                tailLines.addLast(lines[i])
            }
            trimTailLocked()
        } catch (_: Exception) {
            tailLines.clear()
        }
    }

    private fun rotateIfNeededLocked(logFile: File) {
        val length = if (logFile.exists()) logFile.length() else 0L
        if (length < MAX_FILE_BYTES) {
            return
        }
        val rotated = File(logFile.parentFile, "$LOG_FILE_NAME.1")
        if (rotated.exists()) {
            rotated.delete()
        }
        logFile.renameTo(rotated)
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    private fun trimTailLocked() {
        while (tailLines.size > MAX_TAIL_LINES) {
            tailLines.removeFirst()
        }
    }

    private fun publishTailLocked() {
        _tailText.value = tailLines.joinToString(separator = "\n")
    }

    private fun formatLine(level: Level, tag: String, message: String): String {
        val timestamp = formatter.format(Date())
        val compactMessage = message.replace('\n', ' ')
        return "$timestamp ${level.name.padEnd(5, ' ')} [$tag] $compactMessage"
    }

    private const val LOG_FILE_NAME = "nox_vpn_diagnostics.log"
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_TAIL_LINES = 160
}
