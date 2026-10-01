package io.github.kabrapratik28.thumbfree.testing

import android.app.ActivityManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** :engine's memory from /proc (the app's own user may read its other process), in kB; 0 when it is not up. */
object EngineMemory {
    /** Proportional set size, from smaps_rollup. */
    fun pssKb(): Long = read("smaps_rollup", "Pss:")

    /** Peak resident set size so far, VmHWM. */
    fun hwmKb(): Long = read("status", "VmHWM:")

    private fun read(file: String, key: String): Long {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val pid = app.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty()
            .firstOrNull { it.processName == "${app.packageName}:engine" }?.pid ?: return 0
        return runCatching {
            File("/proc/$pid/$file").readLines().first { it.startsWith(key) }.split(Regex("\\s+"))[1].toLong()
        }.getOrDefault(0)
    }
}
