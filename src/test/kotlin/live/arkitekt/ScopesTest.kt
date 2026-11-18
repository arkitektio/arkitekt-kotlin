package live.arkitekt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scopes an app asks for are the ones its operations need. The server's schema says what
 * each root field takes (`@auth(required_scopes: […])`), so the list in the code is held to the
 * schema and the operations checked in beside it: an operation added without its scope would
 * be refused only at run time, with a token that was never asked to carry it.
 */
class ScopesTest {

    @Test
    fun `every app asks for what the library's rekuest operations need, and a service for its own`() {
        assertEquals(requiredScopes(File("src/main/graphql/rekuest")), App.REKUEST_SCOPES.sorted())

        val mikro = Service("mikro", "live.arkitekt.mikro", scopes = listOf("mikro_read", "mikro_write")) { Any() }
        val app = App("scoped", "0.1.0").service(mikro).scopes(Unlok.SCOPES).scope("mikro_read")
        assertEquals(listOf("openid", "rekuest_agent", "mikro_read", "mikro_write", "lok_read"), app.scopes)
    }
}

/**
 * What the operations in [folder] (`*.graphql`) need, by the schema beside them (`*.graphqls`):
 * the `required_scopes` of every root field an operation selects, sorted.
 */
fun requiredScopes(folder: File): List<String> {
    val files = folder.listFiles().orEmpty()
    val schema = files.filter { it.extension == "graphqls" }.joinToString("\n") { it.readText() }
    val operation = Regex("""(?m)^(?:query|mutation|subscription)\s+\w+[^{]*\{\s*(\w+)""")
    val selected = files.filter { it.extension == "graphql" }.flatMap { operation.findAll(it.readText()).map { m -> m.groupValues[1] } }
    return selected
            .flatMap { field ->
                val declared =
                        Regex("""(?m)^  ${Regex.escape(field)}\b[^\n]*@auth\(([^)]*)\)""").find(schema)
                                ?: error("$field has no @auth in the schema of ${folder.name}")
                val scopes = Regex("""required_scopes:\s*\[([^\]]*)]""").find(declared.groupValues[1])?.groupValues?.get(1).orEmpty()
                Regex(""""([^"]+)"""").findAll(scopes).map { it.groupValues[1] }.toList()
            }
            .distinct()
            .sorted()
}
