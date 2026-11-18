package live.arkitekt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// What changed between two values of a state, as RFC 6902 (JSON Patch) operations: what a
// STATE_PATCH carries. The same walk as arkirust's `patch::diff_ops`, so the three SDKs say one
// change the same way.

/** One operation: `replace`, `add` or `remove` at a JSON pointer. A `remove` carries null. */
internal data class PatchOp(val op: String, val path: String, val value: JsonElement)

/** The operations that turn [old] into [new], in the order they apply. */
internal fun diffOps(old: JsonElement, new: JsonElement): List<PatchOp> {
    val ops = mutableListOf<PatchOp>()
    diff(old, new, "", ops)
    return ops
}

private fun diff(left: JsonElement, right: JsonElement, pointer: String, ops: MutableList<PatchOp>) {
    when {
        left is JsonObject && right is JsonObject -> {
            for ((key, value) in right) {
                val at = pointer + token(key)
                val before = left[key]
                if (before == null) ops += PatchOp("add", at, value) else diff(before, value, at, ops)
            }
            for (key in left.keys) {
                if (key !in right) ops += PatchOp("remove", pointer + token(key), JsonNull)
            }
        }
        left is JsonArray && right is JsonArray -> {
            // An item removed moves the ones after it up: the next index is said one lower.
            var shift = 0
            for (index in 0 until maxOf(left.size, right.size)) {
                val at = pointer + "/" + (index - shift)
                val before = left.getOrNull(index)
                val after = right.getOrNull(index)
                when {
                    before != null && after != null -> diff(before, after, at, ops)
                    before != null -> {
                        shift += 1
                        ops += PatchOp("remove", at, JsonNull)
                    }
                    after != null -> ops += PatchOp("add", at, after)
                }
            }
        }
        left != right -> ops += PatchOp("replace", pointer, right)
    }
}

/** `/key`, escaped as a pointer token (`~` as `~0`, `/` as `~1`). */
private fun token(key: String): String = "/" + key.replace("~", "~0").replace("/", "~1")
