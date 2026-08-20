package com.vayunmathur.fooddelivery.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Saved delivery addresses. Every accessor is `suspend` and hops to [io]: each one is a
 * SharedPreferences read plus a JSON decode, which must not run on the main thread (and so
 * must not run inside a `remember { }` initialiser either).
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AddressStore {

    private const val PREFS_NAME = "fooddelivery_addresses"
    private const val KEY = "saved_addresses"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Single writer, so each accessor's read-modify-write runs to completion before the next
     * one starts: two concurrent deletes would otherwise both start from the same list and
     * the second write would put the first one's row back.
     */
    private val io = Dispatchers.IO.limitedParallelism(1)

    suspend fun getAll(context: Context): List<SavedAddress> = withContext(io) {
        read(context)
    }

    suspend fun save(context: Context, address: SavedAddress) = withContext(io) {
        val list = read(context).toMutableList()
        val idx = list.indexOfFirst { it.id == address.id }
        val toSave = if (address.isDefault) {
            list.map { it.copy(isDefault = false) }.toMutableList()
        } else list
        if (idx >= 0) toSave[idx] = address else toSave.add(address)
        write(context, toSave)
    }

    suspend fun delete(context: Context, id: String) = withContext(io) {
        write(context, read(context).filter { it.id != id })
    }

    suspend fun setDefault(context: Context, id: String) = withContext(io) {
        write(context, read(context).map { it.copy(isDefault = it.id == id) })
    }

    suspend fun getDefault(context: Context): SavedAddress? = withContext(io) {
        val all = read(context)
        all.firstOrNull { it.isDefault } ?: all.firstOrNull()
    }

    private fun read(context: Context): List<SavedAddress> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SavedAddress>>(raw)
        } catch (_: Exception) { emptyList() }
    }

    private fun write(context: Context, list: List<SavedAddress>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY, json.encodeToString(list)) }
    }
}
