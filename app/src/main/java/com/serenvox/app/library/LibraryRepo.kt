// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LibraryItem(
    val id: String,
    val title: String,
    val kind: String,
    val chars: Int,
    val addedAt: Long,
    val openedAt: Long,
    val position: Int = 0,
    val preview: String = "",
)

/** Recent documents stored privately on the device (text + small JSON index). */
class LibraryRepo(context: Context) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items

    private fun load(): List<LibraryItem> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            LibraryItem(it.getString("id"), it.getString("title"), it.optString("kind", "txt"), it.optInt("chars"),
                it.optLong("addedAt"), it.optLong("openedAt"), it.optInt("position"), it.optString("preview"))
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.openedAt }

    private fun save(list: List<LibraryItem>) {
        val sorted = list.sortedByDescending { it.openedAt }
        _items.value = sorted
        val a = JSONArray()
        sorted.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("kind", it.kind).put("chars", it.chars)
            .put("addedAt", it.addedAt).put("openedAt", it.openedAt).put("position", it.position).put("preview", it.preview)) }
        val tmp = File(dir, "index.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(index)
    }

    fun add(title: String, kind: String, text: String): LibraryItem {
        val now = System.currentTimeMillis()
        val existing = _items.value.firstOrNull { it.title == title && it.chars == text.length }
        if (existing != null) { touch(existing.id); return existing }
        val item = LibraryItem(UUID.randomUUID().toString(), title, kind, text.length, now, now,
            preview = text.take(160).replace(Regex("\\s+"), " "))
        File(dir, item.id + ".txt").writeText(text)
        save(listOf(item) + _items.value.take(199))
        return item
    }

    fun text(id: String): String? = File(dir, "$id.txt").takeIf { it.exists() }?.readText()
    fun touch(id: String) = save(_items.value.map { if (it.id == id) it.copy(openedAt = System.currentTimeMillis()) else it })
    fun setPosition(id: String, pos: Int) {
        if (_items.value.any { it.id == id && it.position != pos }) save(_items.value.map { if (it.id == id) it.copy(position = pos) else it })
    }
    fun delete(id: String) { File(dir, "$id.txt").delete(); save(_items.value.filterNot { it.id == id }) }
}
