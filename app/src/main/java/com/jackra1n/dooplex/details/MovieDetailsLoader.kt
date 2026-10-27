package com.jackra1n.dooplex.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackra1n.dooplex.plex.PlexBrowseItem
import com.jackra1n.dooplex.plex.PlexClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Loads a movie's full details for its page, showing what the list already knew meanwhile. */
class MovieDetailsLoader(private val client: PlexClient, private val scope: CoroutineScope) {
    var movie by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun open(item: PlexBrowseItem) {
        job?.cancel()
        movie = item
        loading = true
        error = null
        job = scope.launch {
            try {
                movie = client.details(item.ratingKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Plex could not load these details."
            }
            loading = false
        }
    }

    /** Shows a title the server doesn't have, with only what the list knew. */
    fun show(item: PlexBrowseItem) {
        job?.cancel()
        movie = item
        loading = false
        error = null
    }

    fun close() {
        job?.cancel()
        movie = null
        loading = false
        error = null
    }
}
