package io.github.kabrapratik28.thumbfree.testing

import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Main-thread StrictMode: detect disk reads, disk writes and network; penaltyDeath. The log line names the call. */
class StrictModeRule : ExternalResource() {
    private var saved: StrictMode.ThreadPolicy? = null

    override fun before() = onMain {
        saved = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                .penaltyLog().penaltyDeath().build(),
        )
    }

    override fun after() = onMain { StrictMode.setThreadPolicy(saved) }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
