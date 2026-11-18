package live.arkitekt

import kotlinx.serialization.json.Json
import live.arkitekt.rekuest.graphql.type.PortKind
import kotlin.test.Test
import kotlin.test.assertEquals

// What the processor makes of a @State class and of an @Action function that takes it. Both are
// compiled with it (kspTest), so `StagePositionState` and `MoveToAction` are its real output.

/**
 * Where the stage is.
 *
 * @property x Microns from the origin, along x
 */
@State(locks = ["stage"])
data class StagePosition(
        val x: Double = 0.0,
        /** Microns from the origin, along y */
        val y: Double = 0.0,
        val visited: List<String> = emptyList(),
        val label: String? = null
)

/** Move the stage. */
@Action(locks = ["motor"])
suspend fun moveTo(x: Double, y: Double, stage: StateMut<StagePosition>, task: Task): Double {
    task.progress(50, "moving")
    stage.update { it.copy(x = x, y = y, visited = it.visited + "$x,$y") }
    return x
}

/** What a panel of the stage app is: the source is all the app says of it. */
const val STAGE_PANEL =
        """<Blok kind="agent" context="panel"><Card><Text>{self.StagePosition.x}</Text><Button onClick={self.move_to(x: 0, y: 0)}>Home</Button></Card></Blok>"""

class DeclaredStateTest {

    @Test
    fun `an app registers its states, the locks its actions run under, and its bloks as their source`() {
        // The state: a port per property, and its value as the JSON that is published.
        assertEquals("StagePosition", StagePositionState.name)
        assertEquals(listOf("stage"), StagePositionState.locks)
        assertEquals(listOf("x", "y", "visited", "label"), StagePositionState.ports.map { it.key })
        assertEquals(
                listOf(PortKind.FLOAT, PortKind.FLOAT, PortKind.LIST, PortKind.STRING),
                StagePositionState.ports.map { it.kind }
        )
        // A field is described in the class's KDoc (`@property`) or on the property itself.
        assertEquals(
                listOf("Microns from the origin, along x", "Microns from the origin, along y", null, null),
                StagePositionState.ports.map { it.description.getOrNull() }
        )
        assertEquals(listOf(false, false, false, true), StagePositionState.ports.map { it.nullable.getOrNull() })
        assertEquals(
                Json.parseToJsonElement("""{"x":1.5,"y":0.0,"visited":["a"],"label":null}"""),
                StagePositionState.snapshot(StagePosition(x = 1.5, visited = listOf("a")))
        )

        val declared =
                App("stage", "0.1.0")
                        .state(StagePositionState, StagePosition())
                        .action(MoveToAction)
                        .blok("panel", STAGE_PANEL, description = "Where the stage is")
                        .declaration()

        // The action: the state and the task are no ports; it holds its own lock and the state's.
        val moveTo = declared.implementations.getOrNull()!!.single()
        assertEquals(listOf("x", "y"), moveTo.definition.args.getOrNull()!!.map { it.key })
        assertEquals(true, moveTo.definition.stateful.getOrNull())
        assertEquals(listOf("motor", "stage"), moveTo.locks.getOrNull())
        assertEquals(listOf("StagePosition"), moveTo.manipulates.getOrNull())

        assertEquals(listOf("StagePosition"), declared.states.getOrNull()!!.map { it.`interface` })
        assertEquals(listOf("motor", "stage"), declared.locks.getOrNull()!!.map { it.key })

        // The blok: its source and what was said beside it. Its context and what it depends on
        // are not sent: the server reads them off the source.
        val blok = declared.bloks.getOrNull()!!.single()
        assertEquals("panel", blok.key)
        assertEquals(STAGE_PANEL, blok.source.getOrNull())
        assertEquals(2, blok.vocab)
        assertEquals(null, blok.context.getOrNull())
        assertEquals(null, blok.dependencies.getOrNull())
        assertEquals(null, blok.components.getOrNull())
    }
}
