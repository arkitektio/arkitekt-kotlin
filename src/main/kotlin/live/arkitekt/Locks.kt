package live.arkitekt

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * The locks an app's actions run under: one task holds a key at a time, the others wait their
 * turn in the order they asked.
 *
 * A task takes its keys in sorted order and gives them back in reverse, so two tasks that need
 * the same two locks cannot each hold one and wait for the other.
 */
internal class Locks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val holders = ConcurrentHashMap<String, String>()

    /** Whether [task] holds [key] right now. */
    fun holds(task: String, key: String): Boolean = holders[key] == task

    /**
     * Take [keys] for [task], waiting for each, and say each one as it is taken. What is held is
     * added to [taken] as it goes, so a task cancelled while waiting still gives back what it got.
     */
    suspend fun take(task: String, keys: Collection<String>, taken: MutableList<String>, said: suspend (String) -> Unit) {
        for (key in keys.distinct().sorted()) {
            mutexes.computeIfAbsent(key) { Mutex() }.lock(task)
            holders[key] = task
            taken += key
            said(key)
        }
    }

    /** Give back what [task] took, last taken first, saying each. */
    suspend fun release(task: String, taken: List<String>, said: suspend (String) -> Unit) {
        for (key in taken.asReversed()) {
            holders.remove(key, task)
            mutexes[key]?.unlock(task)
            said(key)
        }
    }
}
