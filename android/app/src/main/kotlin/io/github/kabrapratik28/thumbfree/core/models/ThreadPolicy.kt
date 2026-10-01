package io.github.kabrapratik28.thumbfree.core.models

import java.io.File

object ThreadPolicy {
    const val MAX_THREADS = 6

    /**
     * min(6, the CPUs in [allowedCpus] whose max frequency is at least 70% of the fastest CPU's), or min(6, all of
     * [allowedCpus]) when none of them is that fast; 4 when the list is empty. maxFreqsKHz[i] is CPU i's. A null set,
     * or one without a CPU the list knows, is ignored.
     */
    fun autoThreads(maxFreqsKHz: List<Long>, allowedCpus: Set<Int>? = null): Int =
        if (maxFreqsKHz.isEmpty()) 4 else minOf(MAX_THREADS, fastCpus(maxFreqsKHz, allowedCpus).size)

    /**
     * The CPUs [autoThreads] counts, in order: those in [allowedCpus] whose max frequency is at least 70% of the
     * fastest CPU's (any CPU, allowed or not), or all of [allowedCpus] when none is that fast. A null set, or one
     * without a CPU the list knows, stands for every CPU. Empty for an empty list.
     */
    fun fastCpus(maxFreqsKHz: List<Long>, allowedCpus: Set<Int>?): List<Int> {
        if (maxFreqsKHz.isEmpty()) return emptyList()
        val fastest = maxFreqsKHz.max()
        val cpus = allowed(maxFreqsKHz, allowedCpus)
        // Integer comparison (freq * 10 >= fastest * 7) instead of a float threshold: exact, no rounding.
        return cpus.filter { maxFreqsKHz[it] * 10 >= fastest * 7 }.ifEmpty { cpus }
    }

    /** The allowed CPUs [fastCpus] leaves out, in order: the efficiency cores, away from the ASR pool. */
    fun slowCpus(maxFreqsKHz: List<Long>, allowedCpus: Set<Int>?): List<Int> =
        allowed(maxFreqsKHz, allowedCpus) - fastCpus(maxFreqsKHz, allowedCpus).toSet()

    private fun allowed(maxFreqsKHz: List<Long>, allowedCpus: Set<Int>?) =
        maxFreqsKHz.indices.filter { allowedCpus == null || it in allowedCpus }.ifEmpty { maxFreqsKHz.indices.toList() }

    /** [autoThreads] for the process whose /proc/self/status text is [procStatus] (null: unreadable), at most [cap]. */
    fun engineThreads(cap: Int, maxFreqsKHz: List<Long>, procStatus: String?): Int =
        minOf(cap, autoThreads(maxFreqsKHz, procStatus?.let(::allowedCpus)))

    /** The CPUs in the Cpus_allowed_list line of a /proc/<pid>/status text, such as "0-6" or "0-2,4"; null if unparsable. */
    fun allowedCpus(procStatus: String): Set<Int>? {
        val list = procStatus.lineSequence().firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.trim() ?: return null
        return runCatching {
            list.split(',').flatMap { part ->
                val bounds = part.split('-').map(String::toInt)
                require(bounds.size <= 2)
                bounds.first()..bounds.last()
            }.toSet()
        }.getOrNull()
    }

    /** Reads each cpuN/cpufreq/cpuinfo_max_freq under [root]; empty on any error. */
    fun readMaxFreqsKHz(root: File = File("/sys/devices/system/cpu")): List<Long> = try {
        root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu[0-9]+")) }
            .orEmpty()
            .sortedBy { it.name.removePrefix("cpu").toInt() }
            .map { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }
    } catch (e: Exception) {
        emptyList()
    }
}
