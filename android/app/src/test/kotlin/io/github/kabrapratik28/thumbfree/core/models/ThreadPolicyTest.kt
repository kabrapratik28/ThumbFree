package io.github.kabrapratik28.thumbfree.core.models

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreadPolicyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun autoThreads() {
        val mixed = listOf(3_780_000L) + List(5) { 3_050_000L } + List(2) { 2_250_000L }
        assertThat(ThreadPolicy.autoThreads(mixed)).isEqualTo(6)

        assertThat(ThreadPolicy.autoThreads(List(8) { 2_000_000L })).isEqualTo(6)

        val halfSlow = List(4) { 2_800_000L } + List(4) { 1_800_000L }
        assertThat(ThreadPolicy.autoThreads(halfSlow)).isEqualTo(4)

        assertThat(ThreadPolicy.autoThreads(emptyList())).isEqualTo(4)

        assertThat(ThreadPolicy.autoThreads(listOf(2_000_000L, 2_000_000L))).isEqualTo(2)
    }

    @Test
    fun autoThreadsCountsOnlyAllowedCpus() {
        assertThat(ThreadPolicy.autoThreads(PIXEL_10, ThreadPolicy.allowedCpus(status("0-6")))).isEqualTo(5)

        assertThat(ThreadPolicy.autoThreads(PIXEL_10, ThreadPolicy.allowedCpus(status("0-7")))).isEqualTo(6)

        // Unreadable or unparsable, or no CPU the frequencies know: today's rule.
        assertThat(ThreadPolicy.allowedCpus(status("0-x"))).isNull()
        assertThat(ThreadPolicy.allowedCpus("Name:\tio.github.kabrapratik28.thumbfree\n")).isNull()
        assertThat(ThreadPolicy.autoThreads(PIXEL_10, null)).isEqualTo(6)
        assertThat(ThreadPolicy.autoThreads(PIXEL_10, setOf(9))).isEqualTo(6)
    }

    @Test
    fun onlySlowCoresAllowedUsesThemAll() {
        assertThat(ThreadPolicy.autoThreads(PIXEL_10, setOf(0, 1))).isEqualTo(2)
    }

    @Test
    fun engineThreadsCountsTheCallersOwnCpusetUpToTheCap() {
        assertThat(ThreadPolicy.engineThreads(6, PIXEL_10, status("0-6"))).isEqualTo(5)
        assertThat(ThreadPolicy.engineThreads(4, PIXEL_10, status("0-6"))).isEqualTo(4)
        assertThat(ThreadPolicy.engineThreads(6, PIXEL_10, null)).isEqualTo(6) // /proc/self/status unreadable
    }

    // The ASR pool's CPUs are the ones autoThreads counts, and Silero's slow cores are the rest of the cpuset.
    @Test
    fun fastAndSlowCpus() {
        val pixel = ThreadPolicy.allowedCpus(status("0-6"))
        assertThat(ThreadPolicy.fastCpus(PIXEL_10, pixel)).containsExactly(2, 3, 4, 5, 6).inOrder()
        assertThat(ThreadPolicy.slowCpus(PIXEL_10, pixel)).containsExactly(0, 1).inOrder()

        // top-app's cpuset includes the X4: it is fast too.
        assertThat(ThreadPolicy.fastCpus(PIXEL_10, ThreadPolicy.allowedCpus(status("0-7"))))
            .containsExactly(2, 3, 4, 5, 6, 7).inOrder()
        // background's cpuset has one fast core.
        assertThat(ThreadPolicy.fastCpus(PIXEL_10, setOf(0, 1, 2))).containsExactly(2)
        assertThat(ThreadPolicy.slowCpus(PIXEL_10, setOf(0, 1, 2))).containsExactly(0, 1).inOrder()

        // Only slow cores allowed: they are all the pool has, and none is left for Silero.
        assertThat(ThreadPolicy.fastCpus(PIXEL_10, setOf(0, 1))).containsExactly(0, 1).inOrder()
        assertThat(ThreadPolicy.slowCpus(PIXEL_10, setOf(0, 1))).isEmpty()

        // A 4 + 4 phone, all CPUs allowed.
        val fourFour = List(4) { 1_800_000L } + List(4) { 2_800_000L }
        assertThat(ThreadPolicy.fastCpus(fourFour, null)).containsExactly(4, 5, 6, 7).inOrder()
        assertThat(ThreadPolicy.slowCpus(fourFour, null)).containsExactly(0, 1, 2, 3).inOrder()

        // All CPUs alike (the emulator): all fast. An unknown or empty set stands for every CPU.
        assertThat(ThreadPolicy.fastCpus(List(4) { 2_000_000L }, null)).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(ThreadPolicy.fastCpus(PIXEL_10, setOf(9))).containsExactly(2, 3, 4, 5, 6, 7).inOrder()
        assertThat(ThreadPolicy.fastCpus(emptyList(), null)).isEmpty()
    }

    @Test
    fun allowedCpusParsesRangesAndSingles() {
        assertThat(ThreadPolicy.allowedCpus(status("0-2,4,6-7"))).containsExactly(0, 1, 2, 4, 6, 7)
    }

    /** The lines of /proc/self/status around Cpus_allowed_list. */
    private fun status(cpus: String) =
        "Name:\tio.github.kabrapratik28.thumbfree\nCpus_allowed:\t7f\nCpus_allowed_list:\t$cpus\nMems_allowed:\t1\n"

    private companion object {
        // CPUs 0-1 at 2,246 MHz, 2-6 at 3,052 MHz, 7 at 3,782 MHz. Its cpuset for both ThumbFree processes is 0-6.
        val PIXEL_10 = List(2) { 2_246_000L } + List(5) { 3_052_000L } + 3_782_000L
    }

    @Test
    fun readMaxFreqsKHz() {
        File(tmp.newFolder("cpu0", "cpufreq"), "cpuinfo_max_freq").writeText("2000000")
        File(tmp.newFolder("cpu1", "cpufreq"), "cpuinfo_max_freq").writeText("1500000")

        assertThat(ThreadPolicy.readMaxFreqsKHz(tmp.root)).isEqualTo(listOf(2_000_000L, 1_500_000L))
    }
}
