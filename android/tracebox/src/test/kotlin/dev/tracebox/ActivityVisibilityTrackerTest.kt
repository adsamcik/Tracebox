package dev.tracebox

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityVisibilityTrackerTest {
    @Test
    fun activity_started_before_watchdog_attach_is_published_on_attach() {
        val tracker = ActivityVisibilityTracker()
        val eligibility = mutableListOf<Boolean>()

        tracker.activityStarted()
        tracker.attach(eligibility::add)

        assertEquals(listOf(true), eligibility)
    }

    @Test
    fun overlapping_activity_handoff_remains_eligible_until_both_stop() {
        val tracker = ActivityVisibilityTracker()
        val eligibility = mutableListOf<Boolean>()
        tracker.attach(eligibility::add)

        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        tracker.activityStopped()

        assertEquals(listOf(false, true, true, true, false), eligibility)
    }

    @Test
    fun detached_watchdog_misses_no_state_when_a_replacement_attaches() {
        val tracker = ActivityVisibilityTracker()
        val first = mutableListOf<Boolean>()
        val replacement = mutableListOf<Boolean>()
        tracker.attach(first::add)
        tracker.activityStarted()

        tracker.detach()
        tracker.activityStopped()
        tracker.activityStarted()
        tracker.attach(replacement::add)

        assertEquals(listOf(false, true), first)
        assertEquals(listOf(true), replacement)
    }
}
