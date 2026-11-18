package live.arkitekt

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How much a [Task.log] line matters. The names are the wire's. */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL
}

/** What a [Task] reports through, and the states it changes: the agent running it. */
internal interface TaskHost {
    val states: States
    suspend fun report(event: AgentEvent)
}

/**
 * The task an action is running as: how it says how far it is, and where it gets the app's
 * states. An `@Action` function takes one as a parameter; a handler written by hand asks for
 * [current].
 *
 *     @Action
 *     suspend fun acquire(count: Int, task: Task): Int {
 *         repeat(count) { frame ->
 *             …
 *             task.progress(100 * (frame + 1) / count, "frame ${frame + 1}")
 *         }
 *         return count
 *     }
 */
class Task internal constructor(val id: String, private val host: TaskHost) {
    /** Say how far the task is: [percent] of 100, and a line for whoever watches. */
    suspend fun progress(percent: Int? = null, message: String? = null) {
        host.report(AgentEvent.Progress(id, percent, message))
    }

    /** Write a line to the task's log. */
    suspend fun log(message: String, level: LogLevel = LogLevel.INFO) {
        host.report(AgentEvent.Log(id, message, level.name))
    }

    /** A state of the app, to change as this task: what a [StateMut] parameter is filled with. */
    fun <T> state(declaration: StateDeclaration<T>): StateMut<T> =
            StateMut(host.states, host.states.cell(declaration), id)

    /** A state of the app, to consult: what a [StateRef] parameter is filled with. */
    fun <T> stateRef(declaration: StateDeclaration<T>): StateRef<T> =
            StateRef(host.states, host.states.cell(declaration))

    companion object {
        /**
         * The task this coroutine is running.
         *
         * @throws IllegalStateException outside an action's handler.
         */
        suspend fun current(): Task =
                currentCoroutineContext()[TaskElement]?.task
                        ?: throw IllegalStateException("No task is running here: Task.current() is for an action's handler")
    }
}

/** Carries a handler's [Task] in its coroutine, so [Handler] need not take it. */
internal class TaskElement(val task: Task) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskElement>
}
