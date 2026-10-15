package dev.fengine.wallpaper.data

import android.content.Context
import java.io.File

/**
 * The ledger file in the app's private storage. Appends are tiny and rare (a rotation, a screen
 * shown or hidden), so they are synchronous and cheap. Kept under [MAX_BYTES]: when it grows past
 * that, the older half is dropped. Never leaves the phone unless the owner exports it.
 */
class LedgerStore(context: Context) {
    private val file = File(context.filesDir, "ledger.jsonl")

    fun record(event: Ledger.Event, atMs: Long = System.currentTimeMillis()) = synchronized(LOCK) {
        runCatching {
            file.appendText(Ledger.encode(Ledger.Entry(atMs, event)) + "\n")
            if (file.length() > MAX_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun entries(): List<Ledger.Entry> = synchronized(LOCK) {
        runCatching { file.readLines().mapNotNull(Ledger::decode) }.getOrDefault(emptyList())
    }

    /** The raw file, for export. */
    fun text(): String = synchronized(LOCK) { runCatching { file.readText() }.getOrDefault("") }

    companion object {
        private val LOCK = Any()
        const val MAX_BYTES = 512L * 1024
    }
}
