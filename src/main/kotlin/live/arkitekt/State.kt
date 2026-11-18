package live.arkitekt

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import live.arkitekt.rekuest.graphql.type.ReturnPortInput

// States, as `@app.state` in Python and `#[derive(State)]` in Rust: what an app is doing right
// now, kept where everyone can see it.
//
//     /** Where the stage is. */
//     @State(locks = ["stage"])
//     data class StagePosition(val x: Double = 0.0, val y: Double = 0.0)
//
//     @Action
//     suspend fun moveTo(x: Double, y: Double, stage: StateMut<StagePosition>): Double {
//         stage.update { it.copy(x = x, y = y) }
//         return x
//     }
//
//     val app = App("stage", "0.1.0").state(StagePositionState, StagePosition()).action(MoveToAction)
//
// Every change is published (a STATE_PATCH per JSON Patch operation), so whoever watches the app
// knows its state. Do not write actions that only read a state back: reading is what watching
// is for.

/**
 * Make a state of a top-level class: a data class, so a change is a copy.
 *
 * - **Fields** are the properties of the primary constructor: `String`, `Int`/`Long`,
 *   `Float`/`Double`, `Boolean` and a `List` of those. A nullable type is a nullable field.
 * - **Words** come from the KDoc: `@property x …` on the class, or the KDoc on the property.
 *
 * The processor writes `<Class>State` beside the class: declare it with [App.state].
 *
 * @param name what the deployment knows the state as; the class's name if empty.
 * @param locks the locks a task holds to change it. An action that takes the state as a
 *   [StateMut] holds them without saying so.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class State(val name: String = "", val locks: Array<String> = [])

/** A state as the processor writes it: declared on an app with [App.state]. */
interface StateDeclaration<T> {
    /** What the deployment knows the state as, and what a blok reads it by (`self.<name>`). */
    val name: String

    /** The locks a task holds to change it. */
    val locks: List<String>

    /** A port per field. */
    val ports: List<ReturnPortInput>

    /** A value as it is published: an object with a member per field. */
    fun snapshot(value: T): JsonObject
}

/** A state an app declares, with the value it starts from. */
class DeclaredState<T>(val declaration: StateDeclaration<T>, val initial: T)

/** One state's current value. */
internal class StateCell<T>(val declaration: StateDeclaration<T>, @Volatile var value: T) {
    fun snapshot(): JsonObject = declaration.snapshot(value)
}

/** A state, to consult: for an action that needs to know it while doing something else. */
open class StateRef<T> internal constructor(internal val states: States, internal val cell: StateCell<T>) {
    /** The value right now. */
    fun get(): T = cell.value
}

/**
 * A state, to change. Every change is published, in the order it was made.
 *
 * As an `@Action` parameter it is the running task's: the change names the task, and the action
 * holds the state's locks while it runs. From [Context.state] it belongs to no task, and can
 * only change a state that needs no lock.
 */
class StateMut<T> internal constructor(states: States, cell: StateCell<T>, private val task: String?) :
        StateRef<T>(states, cell) {
    /**
     * Change the state to what [change] makes of it, and publish what differs.
     *
     * @throws IllegalStateException when the state needs a lock the writer does not hold.
     */
    fun update(change: (T) -> T): T = states.change(cell, task, change)

    /** Replace the state. */
    fun set(value: T): T = update { value }
}

/**
 * An app's states while it runs: their values, and where their changes go. A [Context] has one;
 * the agent serving the app opens it and publishes from then on.
 */
class States internal constructor() {
    private val cells = LinkedHashMap<String, StateCell<*>>()

    /** Where changes go once the session is announced: state, operations, task. */
    private var publish: ((String, List<PatchOp>, String?) -> Unit)? = null

    /** Whether a task holds a lock: the agent's to say. */
    @Volatile internal var holds: (task: String, lock: String) -> Boolean = { _, _ -> false }

    /** Hold the states an app declares, each at its initial value. Those already held stay. */
    @Synchronized
    internal fun adopt(declared: Collection<DeclaredState<*>>) {
        for (state in declared) adoptOne(state)
    }

    private fun <T> adoptOne(state: DeclaredState<T>) {
        if (state.declaration.name !in cells) {
            cells[state.declaration.name] = StateCell(state.declaration, state.initial)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    internal fun <T> cell(declaration: StateDeclaration<T>): StateCell<T> =
            cells[declaration.name] as StateCell<T>?
                    ?: throw IllegalStateException(
                            "The app does not declare the state '${declaration.name}': add it with App.state"
                    )

    /** Every state as it is now, by name. */
    @Synchronized
    internal fun snapshots(): Map<String, JsonElement> = cells.mapValues { it.value.snapshot() }

    /**
     * Start publishing: [announce] is handed the states as they are now (the session's
     * baseline), and every change after it goes to [publish]. One step, so no change falls
     * between the two or is said before the baseline.
     */
    @Synchronized
    internal fun open(
            announce: (Map<String, JsonElement>) -> Unit,
            publish: (String, List<PatchOp>, String?) -> Unit
    ) {
        announce(snapshots())
        this.publish = publish
    }

    /** Change one state and publish what differs, under one lock: changes go out in order. */
    @Synchronized
    internal fun <T> change(cell: StateCell<T>, task: String?, change: (T) -> T): T {
        val name = cell.declaration.name
        val locks = cell.declaration.locks
        if (locks.isNotEmpty()) {
            if (task == null) {
                throw IllegalStateException(
                        "'$name' is changed under ${locks.joinToString()}: only an action that takes it " +
                                "(a StateMut parameter) can change it"
                )
            }
            val missing = locks.filterNot { holds(task, it) }
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                        "'$name' is changed under ${missing.joinToString()}, which this task does not hold: " +
                                "take the state as a StateMut parameter, or name the lock in @Action(locks = …)"
                )
            }
        }
        val before = cell.snapshot()
        val next = change(cell.value)
        val ops = diffOps(before, cell.declaration.snapshot(next))
        cell.value = next
        if (ops.isNotEmpty()) publish?.invoke(name, ops, task)
        return next
    }
}
