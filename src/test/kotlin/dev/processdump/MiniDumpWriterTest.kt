package dev.processdump

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class MiniDumpWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var child: Process
    private val pid get() = child.pid().toInt()

    @Before
    fun spawnTarget() {
        child = ProcessBuilder("ping", "-n", "120", "127.0.0.1").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    @After
    fun killTarget() {
        child.destroyForcibly()
    }

    @Test
    fun miniDumpHoldsThreadsAndModules() {
        val file = File(temp.root, "mini.dmp")
        var ticks = 0
        val outcome = MiniDumpWriter.write(pid, DumpKind.MINI, file) { ticks++; false }
        assertTrue("$outcome", outcome is DumpOutcome.Written)
        val dump = Minidump(file)
        assertEquals("MDMP", dump.signature)
        // Threads and modules are only included when the callback reads CallbackType at the right offset.
        assertTrue("no threads", dump.count(THREAD_LIST_STREAM) > 0)
        assertTrue("no modules", dump.count(MODULE_LIST_STREAM) > 0)
        assertTrue("cancel callback never ran", ticks > 0)
    }

    @Test
    fun fullDumpIsAboutCommittedMemory() {
        val committed = MiniDumpWriter.committedBytes(pid)!!
        val file = File(temp.root, "full.dmp")
        val outcome = MiniDumpWriter.write(pid, DumpKind.FULL, file) { false }
        assertTrue("$outcome", outcome is DumpOutcome.Written)
        val size = file.length()
        assertTrue("dump $size bytes vs committed $committed", size > committed / 2 && size < committed * 2 + (64L shl 20))
        assertTrue("no memory64 stream", Minidump(file).count(MEMORY64_LIST_STREAM) > 0)
    }

    @Test
    fun cancelDeletesPartialFile() {
        val file = File(temp.root, "cancel.dmp")
        assertEquals(DumpOutcome.Cancelled, MiniDumpWriter.write(pid, DumpKind.FULL, file) { true })
        assertFalse(file.exists())
    }

    @Test
    fun missingProcessFails() {
        val outcome = MiniDumpWriter.write(0x7FFFFFF0, DumpKind.MINI, File(temp.root, "none.dmp")) { false }
        assertTrue("$outcome", outcome is DumpOutcome.Failed)
    }

    /** Reads the stream directory of a .dmp (MINIDUMP_HEADER + MINIDUMP_DIRECTORY, little endian). */
    private class Minidump(file: File) {
        private val bytes = RandomAccessFile(file, "r").use { raf -> ByteArray(minOf(raf.length(), 1L shl 24).toInt()).also { raf.readFully(it) } }
        val signature = String(bytes, 0, 4, Charsets.US_ASCII)

        /** First u32 (thread / module / range count) of the given stream, or 0 when absent. */
        fun count(streamType: Int): Long {
            val streams = u32(8).toInt()
            val directory = u32(12).toInt()
            for (index in 0 until streams) {
                val entry = directory + index * 12
                if (u32(entry).toInt() == streamType) return u32(u32(entry + 8).toInt())
            }
            return 0
        }

        private fun u32(offset: Int): Long =
            (0 until 4).fold(0L) { acc, i -> acc or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }
    }

    private companion object {
        const val THREAD_LIST_STREAM = 3
        const val MODULE_LIST_STREAM = 4
        const val MEMORY64_LIST_STREAM = 9
    }
}
