package dev.tracebox.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PolicyTransitionJournalTest {
    @Test
    fun restart_selects_rollback_before_local_durability_and_roll_forward_after_it() {
        val root = Files.createTempDirectory("tracebox-policy-transition")
        val path = root.resolve("transition")
        val previous = PolicySnapshot(7, 3, disabled = false)
        val target = PolicySnapshot(8, 15, disabled = true)
        val journal = PolicyTransitionJournal(path)

        journal.begin(previous, target)
        assertEquals(
            PolicyTransitionPhase.INTENT,
            assertIs<PolicyTransitionLoad.Active>(PolicyTransitionJournal(path).load())
                .transition.phase,
        )
        journal.markPrepared(target.epoch)
        val prepared = assertIs<PolicyTransitionLoad.Active>(PolicyTransitionJournal(path).load())
            .transition
        assertEquals(previous, prepared.previous)
        assertEquals(target, prepared.target)
        assertTrue(prepared.phase.ordinal < PolicyTransitionPhase.LOCAL_DURABLE.ordinal)

        journal.markLocalDurable(target.epoch)
        val durable = assertIs<PolicyTransitionLoad.Active>(PolicyTransitionJournal(path).load())
            .transition
        assertEquals(PolicyTransitionPhase.LOCAL_DURABLE, durable.phase)
        assertTrue(durable.phase.ordinal >= PolicyTransitionPhase.LOCAL_DURABLE.ordinal)

        journal.complete(target.epoch)
        assertEquals(PolicyTransitionLoad.Empty, PolicyTransitionJournal(path).load())
        assertEquals(target, PolicyTransitionJournal(path).lastCompletedTarget())
        assertTrue(journal.slotPaths.all(Files::isRegularFile))
    }

    @Test
    fun corrupt_new_slot_falls_back_to_last_forced_generation() {
        val root = Files.createTempDirectory("tracebox-policy-slot")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        val previous = PolicySnapshot(1, 0)
        val target = PolicySnapshot(2, 1)
        journal.begin(previous, target)
        journal.markPrepared(target.epoch)

        // begin writes slot A and markPrepared writes slot B.
        val newest = journal.slotPaths[1]
        Files.write(newest, byteArrayOf(1, 2, 3))

        val recovered = assertIs<PolicyTransitionLoad.Active>(
            PolicyTransitionJournal(root.resolve("transition")).load(),
        ).transition
        assertEquals(PolicyTransitionPhase.INTENT, recovered.phase)
        assertEquals(previous, recovered.previous)
        assertEquals(target, recovered.target)
    }

    @Test
    fun idempotent_same_transition_is_allowed_but_conflicts_and_phase_skips_fail() {
        val root = Files.createTempDirectory("tracebox-policy-idempotent")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        val previous = PolicySnapshot(4, 0)
        val target = PolicySnapshot(5, 8)
        assertEquals(journal.begin(previous, target), journal.begin(previous, target))
        assertFailsWith<PolicyTransitionException.Conflicting> {
            journal.begin(previous, PolicySnapshot(6, 9))
        }
        assertFailsWith<PolicyTransitionException.InvalidPhase> {
            journal.markLocalDurable(target.epoch)
        }
    }

    @Test
    fun no_valid_slot_with_existing_bytes_is_corrupt_not_empty() {
        val root = Files.createTempDirectory("tracebox-policy-corrupt")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        Files.write(journal.slotPaths.first(), byteArrayOf(1))
        assertEquals(PolicyTransitionLoad.Corrupt, journal.load())
        assertFailsWith<PolicyTransitionException.Corrupt> {
            journal.begin(PolicySnapshot(1, 0), PolicySnapshot(2, 0))
        }
    }

    @Test
    fun recovery_can_resolve_from_either_side_of_the_durability_boundary() {
        val rollbackRoot = Files.createTempDirectory("tracebox-policy-rollback")
        val rollback = PolicyTransitionJournal(rollbackRoot.resolve("transition"))
        val rollbackTarget = PolicySnapshot(3, 2)
        rollback.begin(PolicySnapshot(2, 1), rollbackTarget)
        rollback.resolveAfterRecovery(3)
        assertEquals(PolicyTransitionLoad.Empty, rollback.load())
        assertEquals(rollbackTarget.epoch, rollback.highWaterEpoch())

        val rollForwardRoot = Files.createTempDirectory("tracebox-policy-roll-forward")
        val rollForward = PolicyTransitionJournal(rollForwardRoot.resolve("transition"))
        rollForward.begin(PolicySnapshot(8, 4), PolicySnapshot(9, 7))
        rollForward.markPrepared(9)
        rollForward.markLocalDurable(9)
        rollForward.resolveAfterRecovery(9)
        assertEquals(PolicyTransitionLoad.Empty, rollForward.load())
        assertFailsWith<PolicyTransitionException.Conflicting> {
            rollForward.resolveAfterRecovery(10)
        }
    }

    @Test
    fun equal_generation_slots_must_have_identical_transitions() {
        val root = Files.createTempDirectory("tracebox-policy-equal-generation")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        val expected = PolicyTransition(
            PolicySnapshot(1, 0),
            PolicySnapshot(2, 1),
            PolicyTransitionPhase.INTENT,
        )
        journal.begin(expected.previous, expected.target)

        Files.copy(
            journal.slotPaths[0],
            journal.slotPaths[1],
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertEquals(expected, assertIs<PolicyTransitionLoad.Active>(journal.load()).transition)

        val otherRoot = Files.createTempDirectory("tracebox-policy-equal-generation-other")
        val other = PolicyTransitionJournal(otherRoot.resolve("transition"))
        other.begin(PolicySnapshot(10, 3), PolicySnapshot(11, 7))
        Files.copy(
            other.slotPaths[0],
            journal.slotPaths[1],
            StandardCopyOption.REPLACE_EXISTING,
        )

        assertEquals(PolicyTransitionLoad.Corrupt, journal.load())
        assertEquals(11L, journal.highWaterEpoch())
        assertFailsWith<PolicyTransitionException.Corrupt> {
            journal.begin(expected.previous, expected.target)
        }
    }

    @Test
    fun reinitialize_completed_replaces_corruption_with_forced_generations() {
        val root = Files.createTempDirectory("tracebox-policy-reinitialize")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        Files.write(journal.slotPaths[0], byteArrayOf(1, 2, 3))
        val previous = PolicySnapshot(20, 4)
        val target = PolicySnapshot(21, 15, disabled = true)

        journal.reinitializeCompleted(previous, target)

        assertEquals(PolicyTransitionLoad.Empty, journal.load())
        assertEquals(target, journal.lastCompletedTarget())
        assertEquals(target.epoch, journal.highWaterEpoch())
        assertEquals(1L, generationOf(journal.slotPaths[0]))
        assertEquals(2L, generationOf(journal.slotPaths[1]))
        assertFailsWith<IllegalArgumentException> {
            journal.reinitializeCompleted(PolicySnapshot(-1, 0), target)
        }
        assertFailsWith<IllegalArgumentException> {
            journal.reinitializeCompleted(target, target)
        }
    }

    @Test
    fun reinitialize_preserves_first_completed_slot_when_second_write_fails() {
        val root = Files.createTempDirectory("tracebox-policy-reinitialize-partial")
        val journal = PolicyTransitionJournal(root.resolve("transition"))
        Files.createDirectory(journal.slotPaths[1])
        val previous = PolicySnapshot(30, 1)
        val target = PolicySnapshot(31, 7, disabled = true)

        assertFailsWith<PolicyTransitionException.Unavailable> {
            journal.reinitializeCompleted(previous, target)
        }

        assertEquals(PolicyTransitionLoad.Empty, journal.load())
        assertEquals(target, journal.lastCompletedTarget())
        assertEquals(target.epoch, journal.highWaterEpoch())
        assertEquals(1L, generationOf(journal.slotPaths[0]))
    }

    @Test
    fun reinitialize_completed_dominates_divergent_slot_when_second_write_fails_before_open() {
        val root = Files.createTempDirectory("tracebox-policy-reinitialize-divergent")
        val path = root.resolve("transition")
        val journal = PolicyTransitionJournal(path)
        journal.begin(PolicySnapshot(1, 0), PolicySnapshot(2, 1))
        val otherRoot = Files.createTempDirectory("tracebox-policy-reinitialize-divergent-other")
        val other = PolicyTransitionJournal(otherRoot.resolve("transition"))
        other.begin(PolicySnapshot(10, 3), PolicySnapshot(11, 7))
        Files.copy(
            other.slotPaths[0],
            journal.slotPaths[1],
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertEquals(PolicyTransitionLoad.Corrupt, journal.load())
        val repaired = PolicySnapshot(12, Long.MAX_VALUE, disabled = true)
        val failing = PolicyTransitionJournal(path) { slot, generation ->
            if (slot == journal.slotPaths[1] && generation == 3L) {
                throw IOException("injected failure before second slot open")
            }
        }

        assertFailsWith<PolicyTransitionException.Unavailable> {
            failing.reinitializeCompleted(PolicySnapshot(11, 7), repaired)
        }

        val recovered = PolicyTransitionJournal(path)
        assertEquals(PolicyTransitionLoad.Empty, recovered.load())
        assertEquals(repaired, recovered.lastCompletedTarget())
        assertEquals(2L, generationOf(journal.slotPaths[0]))
        assertEquals(1L, generationOf(journal.slotPaths[1]))
    }

    @Test
    fun supersede_completed_first_failure_preserves_active_enabling_transition() {
        val root = Files.createTempDirectory("tracebox-policy-supersede-first-failure")
        val path = root.resolve("transition")
        val journal = PolicyTransitionJournal(path)
        val disabled = PolicySnapshot(40, Long.MAX_VALUE, disabled = true)
        val enabling = PolicySnapshot(41, 0, disabled = false)
        journal.begin(disabled, enabling)
        journal.markPrepared(enabling.epoch)
        val failing = PolicyTransitionJournal(path) { slot, generation ->
            if (slot == journal.slotPaths[0] && generation == 3L) {
                throw IOException("injected failure before first slot open")
            }
        }

        assertFailsWith<PolicyTransitionException.Unavailable> {
            failing.supersedeCompleted(enabling, disabled.copy(epoch = 42))
        }

        val active = assertIs<PolicyTransitionLoad.Active>(PolicyTransitionJournal(path).load())
        assertEquals(PolicyTransitionPhase.PREPARED, active.transition.phase)
        assertEquals(enabling, active.transition.target)
        assertEquals(2L, generationOf(journal.slotPaths[1]))
    }

    @Test
    fun supersede_completed_second_failure_leaves_new_disabled_target_dominant() {
        val root = Files.createTempDirectory("tracebox-policy-supersede-second-failure")
        val path = root.resolve("transition")
        val journal = PolicyTransitionJournal(path)
        val disabled = PolicySnapshot(50, Long.MAX_VALUE, disabled = true)
        val enabling = PolicySnapshot(51, 0, disabled = false)
        val repaired = PolicySnapshot(52, Long.MAX_VALUE, disabled = true)
        journal.begin(disabled, enabling)
        journal.markPrepared(enabling.epoch)
        val failing = PolicyTransitionJournal(path) { slot, generation ->
            if (slot == journal.slotPaths[1] && generation == 4L) {
                throw IOException("injected failure before second slot open")
            }
        }

        assertFailsWith<PolicyTransitionException.Unavailable> {
            failing.supersedeCompleted(enabling, repaired)
        }

        val recovered = PolicyTransitionJournal(path)
        assertEquals(PolicyTransitionLoad.Empty, recovered.load())
        assertEquals(repaired, recovered.lastCompletedTarget())
        assertEquals(repaired.epoch, recovered.highWaterEpoch())
        assertEquals(3L, generationOf(journal.slotPaths[0]))
        assertEquals(2L, generationOf(journal.slotPaths[1]))
    }

    private fun generationOf(path: java.nio.file.Path): Long =
        ByteBuffer.wrap(Files.readAllBytes(path))
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong(8)
}
