package com.relay.app.data

import android.content.Context
import com.relay.app.protocol.ProtocolCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** A saved list of people (family, rescue team...). Sending to a group sends to each member. */
@Serializable
data class RelayGroup(
    val id: String,
    val name: String,
    val memberIds: List<String>,
    val memberNames: List<String>,
)

/** Groups are stored on this phone only (SharedPreferences, JSON). */
class GroupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relay_groups", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(RelayGroup.serializer())
    private val _groups = MutableStateFlow(load())
    val groups: StateFlow<List<RelayGroup>> = _groups

    fun create(name: String, members: List<Pair<String, String>>): RelayGroup {
        val group = RelayGroup(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(32).ifBlank { "Group" },
            memberIds = members.map { it.first },
            memberNames = members.map { it.second },
        )
        save(_groups.value + group)
        return group
    }

    fun delete(id: String) = save(_groups.value.filterNot { it.id == id })

    private fun load(): List<RelayGroup> =
        runCatching { ProtocolCodec.json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun save(groups: List<RelayGroup>) {
        prefs.edit().putString(KEY, ProtocolCodec.json.encodeToString(serializer, groups)).apply()
        _groups.value = groups
    }

    private companion object {
        const val KEY = "groups"
    }
}
