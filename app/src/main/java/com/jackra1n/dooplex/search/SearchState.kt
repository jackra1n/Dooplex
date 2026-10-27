package com.jackra1n.dooplex.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jackra1n.dooplex.details.MovieDetailsLoader
import com.jackra1n.dooplex.plex.PlexClient
import com.jackra1n.dooplex.plex.PlexHomeRow
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What was typed and what the server found. Each keystroke replaces the search before it. */
class SearchState(private val client: PlexClient, parentScope: CoroutineScope) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private var job: Job? = null
    var query by mutableStateOf("")
        private set
    var rows by mutableStateOf<List<PlexHomeRow>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val details = MovieDetailsLoader(client, scope)

    fun type(text: String) {
        query = text
        job?.cancel()
        val trimmed = text.trim()
        if (trimmed.length < MIN_QUERY) {
            rows = emptyList()
            searching = false
            error = null
            return
        }
        searching = true
        job = scope.launch {
            delay(TYPING_PAUSE_MS)
            try {
                rows = client.search(trimmed)
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                error = failure.message ?: "Plex could not search."
            }
            searching = false
        }
    }

    fun close() {
        lifetime.cancel()
    }

    companion object {
        const val MIN_QUERY = 2
        private const val TYPING_PAUSE_MS = 300L
    }
}
