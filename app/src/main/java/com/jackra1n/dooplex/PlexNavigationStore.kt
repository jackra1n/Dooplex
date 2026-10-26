package com.jackra1n.dooplex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An ID-only source choice, resolved against the account's currently authorized sources.
 * Missing remembered servers or libraries stay unresolved; they never silently select a replacement.
 * Without a remembered choice, only an unambiguous server or library may be selected automatically.
 */
internal data class PlexNavigationSelection(
    val serverId: String? = null,
    val movieLibraryKey: String? = null,
    val showLibraryKey: String? = null,
    val activeType: String = "movie",
) {
    fun serverFrom(servers: List<PlexServer>): PlexServer? =
        if (serverId == null) servers.singleOrNull() else servers.firstOrNull { it.id == serverId }

    fun libraryFor(libraries: List<PlexLibrary>, type: String): PlexLibrary? {
        if (type != "movie" && type != "show") return null
        val key = libraryKey(type)
        return if (key == null) {
            libraries.singleOrNull { it.type == type }
        } else {
            libraries.firstOrNull { it.type == type && it.key == key }
        }
    }

    private fun libraryKey(type: String): String? = when (type) {
        "movie" -> movieLibraryKey
        "show" -> showLibraryKey
        else -> null
    }
}

/**
 * Persists only non-secret server IDs, movie/show library keys, and the last active supported type.
 * Tokens, server URLs, and movie/episode IDs never enter these private navigation preferences;
 * encrypted account credentials remain the responsibility of [PlexAccountStore].
 *
 * Library keys and active types are namespaced by server ID. Selecting a server preserves that
 * server's previous choices without reusing another server's keys. Loading does not discard stale
 * IDs: [PlexNavigationSelection] requires explicit source selection when an ID is no longer offered.
 */
internal class PlexNavigationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dooplex-navigation",
        Context.MODE_PRIVATE,
    )

    suspend fun load(): PlexNavigationSelection = withContext(Dispatchers.IO) {
        val serverId = preferences.getString(SERVER_ID, null)
            ?: return@withContext PlexNavigationSelection()
        val activeType = preferences.getString(activeTypeKey(serverId), "movie")
            .takeIf { it == "movie" || it == "show" } ?: "movie"
        PlexNavigationSelection(
            serverId = serverId,
            movieLibraryKey = preferences.getString(libraryKey(serverId, "movie"), null),
            showLibraryKey = preferences.getString(libraryKey(serverId, "show"), null),
            activeType = activeType,
        )
    }

    suspend fun rememberServer(serverId: String): Unit = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(SERVER_ID, serverId).commit()) { SAVE_ERROR }
    }

    suspend fun rememberLibrary(serverId: String, library: PlexLibrary): Unit = withContext(Dispatchers.IO) {
        require(library.type == "movie" || library.type == "show") {
            "Choose a Movies or Shows library to save your source selection."
        }
        check(
            preferences.edit()
                .putString(SERVER_ID, serverId)
                .putString(libraryKey(serverId, library.type), library.key)
                .putString(activeTypeKey(serverId), library.type)
                .commit(),
        ) { SAVE_ERROR }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) {
            "Saved Plex sources could not be cleared. Free device storage and try disconnecting again."
        }
    }

    private fun libraryKey(serverId: String, type: String): String = "library.$type.$serverId"

    private fun activeTypeKey(serverId: String): String = "activeType.$serverId"

    private companion object {
        const val SERVER_ID = "serverId"
        const val SAVE_ERROR =
            "Your Plex source selection could not be saved. Free device storage and choose your source again."
    }
}
