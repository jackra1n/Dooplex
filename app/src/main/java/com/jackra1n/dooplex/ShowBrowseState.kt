package com.jackra1n.dooplex

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class ShowBrowseLevel { SEASONS, EPISODES }

/**
 * Child-only season/episode browser, 40-item pages: at most six cached pages and two requests.
 * Visible pages precede adjacent prefetch; moving the window evicts metadata, not just artwork.
 * Cancelled IO keeps its slot until unwound. Refresh retains cards, and revisions reject stale work.
 */
@Stable
internal class ShowBrowseState(
    val path: String,
    val title: String,
    val level: ShowBrowseLevel,
    val connection: PlexConnection,
    parentScope: CoroutineScope,
) {
    var total by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private val client = PlexClient(connection)
    private val pages = mutableStateMapOf<Int, PlexBrowsePage>()
    private val stale = mutableSetOf<Int>()
    private val failures = mutableMapOf<Int, String>()
    private val running = linkedMapOf<Long, Request>()
    private var revision = 0
    private var nextId = 0L
    private var closed = false
    private var totalKnown = false
    private var firstIndex = 0
    private var lastIndex = 0
    private var wanted = listOf(0)

    private data class Request(val page: Int, val job: Job)

    init {
        pump()
    }

    fun itemAt(index: Int): PlexBrowseItem? {
        if (index !in 0 until total) return null
        val page = pages[index / PAGE_SIZE] ?: return null
        return page.items.getOrNull(index - page.start)
    }

    fun requestRange(firstIndex: Int, lastIndex: Int) {
        if (closed) return
        this.firstIndex = firstIndex.coerceAtLeast(0)
        this.lastIndex = lastIndex.coerceAtLeast(this.firstIndex)
        updateWindow()
        pump()
    }

    fun retry() {
        if (closed) return
        failures.clear()
        pump()
    }

    /** Refetch the retained viewport after playback without replacing its cards with placeholders. */
    fun refresh() {
        if (closed) return
        revision++
        running.values.forEach { it.job.cancel() }
        stale.addAll(pages.keys)
        failures.clear()
        if (total == 0) totalKnown = false
        updateWindow()
        pump()
    }

    fun close() {
        if (closed) return
        closed = true
        revision++
        lifetime.cancel()
        pages.clear()
        stale.clear()
        failures.clear()
        loading = false
    }

    private fun updateWindow() {
        if (totalKnown && total == 0) {
            wanted = emptyList()
        } else {
            val lastPage = if (totalKnown) (total - 1) / PAGE_SIZE else 0
            val first = (firstIndex / PAGE_SIZE).coerceAtMost(lastPage)
            val last = (lastIndex / PAGE_SIZE).coerceIn(first, minOf(lastPage, first + MAX_PAGES - 3))
            wanted = buildList {
                addAll(first..last)
                if (last < lastPage) add(last + 1)
                if (first > 0) add(first - 1)
            }
        }
        pages.keys.toList().filter { it !in wanted }.forEach { pages.remove(it) }
        stale.retainAll(wanted.toSet())
        failures.keys.retainAll(wanted.toSet())
        running.values.forEach { if (it.page !in wanted) it.job.cancel() }
    }

    private fun pump() {
        if (closed || !lifetime.isActive) return
        while (running.size < 2) {
            val page = wanted.firstOrNull { candidate ->
                (candidate !in pages || candidate in stale) && candidate !in failures &&
                    running.values.none { it.page == candidate }
            } ?: break
            val id = ++nextId
            val version = revision
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val start = page * PAGE_SIZE
                    val result = client.browse(path, start)
                    currentCoroutineContext().ensureActive()
                    if (closed || version != revision || page !in wanted) return@launch
                    val expected = (result.total.toLong() - start).coerceIn(0, PAGE_SIZE.toLong()).toInt()
                    if (result.start != start || result.total < 0 || result.items.size != expected) {
                        throw IOException("Plex returned an incomplete or mismatched page. Try again.")
                    }
                    totalKnown = true
                    total = result.total
                    // A shrunken final page must not leave old cards addressable beyond its new end.
                    pages.keys.toList().forEach { cachedPage ->
                        val cached = pages[cachedPage] ?: return@forEach
                        val keep = (total.toLong() - cached.start).coerceIn(0, PAGE_SIZE.toLong()).toInt()
                        if (keep == 0) pages.remove(cachedPage)
                        else if (cached.items.size > keep) pages[cachedPage] = cached.copy(items = cached.items.take(keep), total = total)
                    }
                    pages[page] = result
                    stale.remove(page)
                    failures.remove(page)
                    updateWindow()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    if (!closed && version == revision && page in wanted) {
                        failures[page] = failure.message ?: "Couldn't load this collection. Try again."
                    }
                } finally {
                    running.remove(id)
                    if (!closed) pump()
                }
            }
            running[id] = Request(page, job)
            job.start()
        }
        error = wanted.firstNotNullOfOrNull { failures[it] }
        loading = wanted.any { (it !in pages || it in stale) && it !in failures }
    }

    private companion object {
        const val PAGE_SIZE = 40
        const val MAX_PAGES = 6
    }
}
