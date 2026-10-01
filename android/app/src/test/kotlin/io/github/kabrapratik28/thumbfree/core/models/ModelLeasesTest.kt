package io.github.kabrapratik28.thumbfree.core.models

import com.google.common.truth.Truth.assertThat
import kotlin.concurrent.thread
import org.junit.Test

// Takes and retranscriptions hold a lease on their model; a delete must get the model to itself.
class ModelLeasesTest {
    private val leases = ModelLeases()
    private val model = Catalog.PARAKEET_UNIFIED_Q8
    private val other = Catalog.CANARY_180M_FLASH_Q8

    @Test
    fun aHeldModelCannotBeDeleted() {
        val lease = leases.hold(model)
        assertThat(lease).isNotEqualTo(0L)

        assertThat(leases.tryDelete(model)).isEqualTo(0L)
        assertThat(leases.tryDelete(other)).isNotEqualTo(0L) // per model

        leases.release(model, lease)
        assertThat(leases.tryDelete(model)).isNotEqualTo(0L)
    }

    @Test
    fun aModelBeingDeletedGivesNoLeaseUntilTheDeleteEnds() {
        val reservation = leases.tryDelete(model)

        assertThat(leases.hold(model)).isEqualTo(0L)
        leases.release(model, 0L) // a take that got no lease has nothing to give back

        leases.deleted(model, reservation)
        assertThat(leases.hold(model)).isNotEqualTo(0L)
    }

    // A take starts on the main thread and may end on another: a lease is a stamp, not a lock a thread owns.
    @Test
    fun aLeaseCanEndOnAnotherThread() {
        val lease = leases.hold(model)

        thread { leases.release(model, lease) }.join()

        assertThat(leases.tryDelete(model)).isNotEqualTo(0L)
    }
}
