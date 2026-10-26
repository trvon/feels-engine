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
    private val tmp = File(context.filesDir, "ledger.jsonl.tmp")
    private val exitMark = File(context.filesDir, "ledger-exits")

    fun record(event: Ledger.Event, atMs: Long = System.currentTimeMillis()) =
        synchronized(LOCK) {
            runCatching {
                file.appendText(Ledger.encode(Ledger.Entry(atMs, event)) + "\n")
                if (file.length() > MAX_BYTES) {
                    // Write the kept half aside, then swap: a kill mid-write never loses the file.
                    val lines = file.readLines()
                    tmp.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
                    if (!tmp.renameTo(file)) tmp.delete()
                }
            }
        }

    fun entries(): List<Ledger.Entry> =
        synchronized(LOCK) {
            runCatching { file.readLines().mapNotNull(Ledger::decode) }.getOrDefault(emptyList())
        }

    /** The most recent screen-saver entry, for deduplication. */
    fun lastScreenSaver(): Ledger.Entry? =
        entries().lastOrNull { it.event is Ledger.Event.ScreenSaver }

    /**
     * Time of the newest imported process exit, so each exit is imported once. Kept beside the
     * ledger too: halving can drop every exit line.
     */
    fun lastExitAt(): Long? =
        synchronized(LOCK) {
            val mark = runCatching { exitMark.readText().trim().toLong() }.getOrNull()
            val inLog = entries().filter { it.event is Ledger.Event.Exit }.maxOfOrNull { it.atMs }
            listOfNotNull(mark, inLog).maxOrNull()
        }

    fun markExitsUpTo(atMs: Long) =
        synchronized(LOCK) { runCatching { exitMark.writeText(atMs.toString()) } }

    /** The raw file, for export. */
    fun text(): String = synchronized(LOCK) { runCatching { file.readText() }.getOrDefault("") }

    companion object {
        private val LOCK = Any()
        const val MAX_BYTES = 512L * 1024
    }
}
