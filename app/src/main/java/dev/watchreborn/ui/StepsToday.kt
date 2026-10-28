package dev.watchreborn.ui

/**
 * Today's steps from Android's TYPE_STEP_COUNTER (3.7.3).
 *
 * The sensor reports a cumulative count since the last reboot, not a
 * daily figure, so showing its raw value (as 3.7.2 and earlier would
 * have, had the permission ever been granted) reads as tens of
 * thousands of "steps". This folds each raw reading into a small
 * persisted [State] holding the day's baseline:
 *
 * - First reading ever: the baseline is that reading, so the count
 *   starts from zero at the moment tracking began.
 * - A new day: the baseline becomes the last reading seen before it.
 *   Steps taken between that reading and midnight count toward the
 *   new day — unavoidable without a wakeup at midnight, and small
 *   while the listener is registered (the launcher rarely dies).
 * - The counter going backwards means a reboot: the steps already
 *   counted today are carried and the new counter adds on from zero.
 */
object StepsToday {

    /** [day] is an epoch day; the other fields are raw counter values
     *  except [carried], which is steps already counted today before a
     *  reboot reset the counter. */
    data class State(val day: Long, val base: Long, val carried: Long, val last: Long)

    /** [prev] with one raw [counter] reading taken on [day] folded in. */
    fun fold(prev: State?, counter: Long, day: Long): State = when {
        prev == null -> State(day, counter, 0L, counter)
        counter < prev.last ->
            State(day, 0L, if (day == prev.day) today(prev) else 0L, counter)
        day != prev.day -> State(day, prev.last, 0L, counter)
        else -> prev.copy(last = counter)
    }

    /** Steps on [day] according to [state]; zero once the day has turned. */
    fun stepsOn(state: State?, day: Long): Long =
        if (state == null || state.day != day) 0L else today(state)

    /** Whether [next] differs from [saved] enough to be worth writing:
     *  any baseline change always, otherwise every [LAST_WRITE_EVERY]
     *  steps. A lagging [State.last] only blurs the midnight baseline
     *  or a reboot carry by at most that many steps; today's figure
     *  itself depends only on base and carried. */
    fun worthSaving(saved: State?, next: State): Boolean =
        saved == null || saved.day != next.day || saved.base != next.base ||
            saved.carried != next.carried || next.last - saved.last >= LAST_WRITE_EVERY

    const val LAST_WRITE_EVERY = 100L

    private fun today(s: State): Long = (s.carried + s.last - s.base).coerceAtLeast(0L)
}
