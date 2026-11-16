package dev.processdump

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Kernel32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import java.io.File

// MINIDUMP_TYPE flags (minidumpapiset.h).
private const val WITH_FULL_MEMORY = 0x2
private const val WITH_HANDLE_DATA = 0x4
private const val WITH_UNLOADED_MODULES = 0x20
private const val WITH_INDIRECTLY_REFERENCED_MEMORY = 0x40
private const val WITH_PROCESS_THREAD_DATA = 0x100
private const val WITH_FULL_MEMORY_INFO = 0x800
private const val WITH_THREAD_INFO = 0x1000
private const val IGNORE_INACCESSIBLE_MEMORY = 0x20000

private const val COMMON_FLAGS = WITH_HANDLE_DATA or WITH_UNLOADED_MODULES or WITH_PROCESS_THREAD_DATA or
    WITH_FULL_MEMORY_INFO or WITH_THREAD_INFO or IGNORE_INACCESSIBLE_MEMORY

enum class DumpKind(val id: String, val label: String, val flags: Int) {
    MINI("mini", "Mini", COMMON_FLAGS or WITH_INDIRECTLY_REFERENCED_MEMORY),
    FULL("full", "Full", COMMON_FLAGS or WITH_FULL_MEMORY);

    companion object {
        fun parse(text: String?): DumpKind? = entries.firstOrNull { it.id.equals(text?.trim(), ignoreCase = true) }
    }
}

sealed interface DumpOutcome {
    data class Written(val file: File, val bytes: Long, val millis: Long) : DumpOutcome
    data object Cancelled : DumpOutcome
    data class Failed(val message: String) : DumpOutcome
}

private const val PROCESS_DUP_HANDLE = 0x0040
private const val PROCESS_QUERY_INFORMATION = 0x0400
private const val PROCESS_VM_READ = 0x0010
private const val MEM_COMMIT = 0x1000
private const val PAGE_NOACCESS = 0x01
private const val PAGE_GUARD = 0x100
private const val HRESULT_CANCELLED = 0x800704C7.toInt()

// MINIDUMP_CALLBACK_TYPE values the callback handles.
private const val MODULE_CALLBACK = 0
private const val THREAD_CALLBACK = 1
private const val THREAD_EX_CALLBACK = 2
private const val INCLUDE_THREAD_CALLBACK = 3
private const val INCLUDE_MODULE_CALLBACK = 4
private const val CANCEL_CALLBACK = 6

// MINIDUMP_CALLBACK_INPUT is pack(4): ProcessId @0, ProcessHandle @4, CallbackType @12.
private const val CALLBACK_TYPE_OFFSET = 12L

internal interface DbgHelpLibrary : Library {
    @Suppress("FunctionName")
    fun MiniDumpWriteDump(
        process: WinNT.HANDLE,
        processId: Int,
        file: WinNT.HANDLE,
        dumpType: Int,
        exceptionParam: Pointer?,
        userStreamParam: Pointer?,
        callbackParam: MinidumpCallbackInformation?,
    ): Boolean
}

internal fun interface MinidumpCallback : Callback {
    fun invoke(param: Pointer?, input: Pointer, output: Pointer): Boolean
}

@Structure.FieldOrder("CallbackRoutine", "CallbackParam")
internal class MinidumpCallbackInformation : Structure() {
    @JvmField var CallbackRoutine: MinidumpCallback? = null
    @JvmField var CallbackParam: Pointer? = null
}

object MiniDumpWriter {
    private val kernel32: Kernel32 get() = Kernel32.INSTANCE

    // Loaded by full path so a dbghelp.dll next to the IDE executable can't shadow the system one.
    private val dbghelp: DbgHelpLibrary by lazy {
        val windows = System.getenv("SystemRoot") ?: "C:\\Windows"
        Native.load("$windows\\System32\\dbghelp.dll", DbgHelpLibrary::class.java)
    }

    /** Committed, readable memory of [pid]: what a full dump contains, so roughly its size. */
    fun committedBytes(pid: Int): Long? {
        val process = open(pid, PROCESS_QUERY_INFORMATION) ?: return null
        try {
            val info = WinNT.MEMORY_BASIC_INFORMATION()
            val infoSize = BaseTSD.SIZE_T(info.size().toLong())
            var address = 0L
            var total = 0L
            while (kernel32.VirtualQueryEx(process, Pointer(address), info, infoSize).toLong() != 0L) {
                val regionSize = info.regionSize.toLong()
                val readable = info.protect.toInt() and (PAGE_NOACCESS or PAGE_GUARD) == 0
                if (info.state.toInt() == MEM_COMMIT && readable) total += regionSize
                val next = Pointer.nativeValue(info.baseAddress) + regionSize
                if (next <= address) break
                address = next
            }
            return total
        } finally {
            kernel32.CloseHandle(process)
        }
    }

    /**
     * Writes a dump of [pid] to [file]. [onTick] runs periodically on the calling thread while
     * dbghelp works; returning true cancels the dump (the partial file is deleted).
     */
    fun write(pid: Int, kind: DumpKind, file: File, onTick: () -> Boolean): DumpOutcome {
        val start = System.currentTimeMillis()
        val process = open(pid, PROCESS_QUERY_INFORMATION or PROCESS_VM_READ or PROCESS_DUP_HANDLE)
            ?: return DumpOutcome.Failed("Cannot open process $pid: ${errorText(Native.getLastError())}")
        try {
            file.absoluteFile.parentFile?.mkdirs()
            val handle = kernel32.CreateFile(
                file.path, WinNT.GENERIC_WRITE, 0, null, WinNT.CREATE_ALWAYS, WinNT.FILE_ATTRIBUTE_NORMAL, null,
            )
            if (handle == null || handle == WinBase.INVALID_HANDLE_VALUE) {
                return DumpOutcome.Failed("Cannot create ${file.path}: ${errorText(Native.getLastError())}")
            }
            val callback = CancelCallback(onTick)
            val callbackInfo = MinidumpCallbackInformation().apply { CallbackRoutine = callback }
            val written: Boolean
            val error: Int
            try {
                written = dbghelp.MiniDumpWriteDump(process, pid, handle, kind.flags, null, null, callbackInfo)
                error = Native.getLastError()
            } finally {
                kernel32.CloseHandle(handle)
            }
            if (written) return DumpOutcome.Written(file, file.length(), System.currentTimeMillis() - start)
            file.delete()
            if (callback.cancelled || error == HRESULT_CANCELLED) return DumpOutcome.Cancelled
            return DumpOutcome.Failed("MiniDumpWriteDump failed: ${errorText(error)}")
        } finally {
            kernel32.CloseHandle(process)
        }
    }

    private fun open(pid: Int, access: Int): WinNT.HANDLE? =
        kernel32.OpenProcess(access, false, pid)?.takeIf { it.pointer != null }

    private fun errorText(code: Int): String {
        val message = runCatching {
            if (code ushr 16 == 0) Kernel32Util.formatMessageFromLastErrorCode(code)
            else Kernel32Util.formatMessage(WinNT.HRESULT(code))
        }.getOrNull()?.trim()
        return "0x%08X".format(code) + (message?.let { " ($it)" } ?: "")
    }

    // Default handling for every callback except cancel; modules and threads must return TRUE to be included.
    private class CancelCallback(private val onTick: () -> Boolean) : MinidumpCallback {
        @Volatile var cancelled = false

        override fun invoke(param: Pointer?, input: Pointer, output: Pointer): Boolean =
            when (input.getInt(CALLBACK_TYPE_OFFSET)) {
                MODULE_CALLBACK, THREAD_CALLBACK, THREAD_EX_CALLBACK,
                INCLUDE_THREAD_CALLBACK, INCLUDE_MODULE_CALLBACK -> true
                CANCEL_CALLBACK -> {
                    if (!cancelled) cancelled = runCatching(onTick).getOrDefault(false)
                    output.setInt(0, 1) // CheckCancel: keep calling back
                    output.setInt(4, if (cancelled) 1 else 0) // Cancel
                    true
                }
                else -> false
            }
    }
}
