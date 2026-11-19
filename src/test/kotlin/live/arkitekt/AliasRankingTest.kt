package live.arkitekt

import kotlin.test.Test
import kotlin.test.assertEquals

/** The order aliases are tried in: the server's ranking, narrowed by this client's policy. */
class AliasRankingTest {

    private fun alias(id: String, reach: String? = null, priority: Int? = null) =
            Alias(id = id, host = "$id.example", ssl = true, reach = reach, priority = priority)

    private fun ids(aliases: List<Alias>) = aliases.map { it.id }

    @Test
    fun `the server's priority beats the listed order`() {
        val aliases = listOf(alias("a", priority = 20), alias("b"), alias("c", priority = 10))
        // No priority comes after every alias with one.
        assertEquals(listOf("c", "a", "b"), ids(rankAliases(aliases)))
    }

    @Test
    fun `without priorities or a policy the order is unchanged`() {
        val aliases = listOf(alias("a", "public"), alias("b", "lan"), alias("c"))
        assertEquals(listOf("a", "b", "c"), ids(rankAliases(aliases, AliasPolicy())))
    }

    @Test
    fun `a policy drops excluded aliases and tries preferred reaches first`() {
        val aliases = listOf(
                alias("pub", "public", priority = 1),
                alias("lan2", "lan", priority = 5),
                alias("gone", "lan", priority = 0),
                alias("lan1", "lan", priority = 3),
                alias("mesh", "mesh", priority = 2),
                alias("int", "internal", priority = 0)
        )
        val policy = AliasPolicy.fromEnv(
                mapOf(
                        ALIAS_PREFER_REACH_ENV to " lan, ,",
                        ALIAS_EXCLUDE_REACH_ENV to "internal",
                        ALIAS_EXCLUDE_ENV to "gone"
                )::get
        )
        assertEquals(listOf("lan1", "lan2", "pub", "mesh"), ids(rankAliases(aliases, policy)))
    }
}
