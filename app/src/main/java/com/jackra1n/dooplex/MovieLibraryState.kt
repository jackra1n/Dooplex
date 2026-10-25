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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Main-thread-confined, bounded movie window: 96 titles/page, at most 12 pages and two requests.
 * Cancelled work retains its request slot until its IO has unwound. Query changes discard old pages;
 * alphabet counts follow the filter, not the sort, and use a separate revision to reject stale work.
 * Jumps publish repeatable title-sort events only after page totals agree with server bucket counts.
 * Playback refreshes preserve the viewport generation while invalidating Unwatched membership/counts.
 */
@Stable
class MovieLibraryState(
    val library: PlexLibrary,
    val connection: PlexConnection,
    parentScope: CoroutineScope,
) {
    val pageSize: Int = 96
    var total by mutableIntStateOf(0)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var currentSort by mutableStateOf(MovieSort.TITLE)
    val sort: MovieSort get() = currentSort
    private var unwatchedFilter by mutableStateOf(false)
    val unwatchedOnly: Boolean get() = unwatchedFilter
    var generation by mutableIntStateOf(0)
        private set
    var selectedMovie by mutableStateOf<PlexBrowseItem?>(null)
        private set
    var detailsLoading by mutableStateOf(false)
        private set
    var detailsError by mutableStateOf<String?>(null)
        private set
    var alphabet by mutableStateOf<List<MovieLetter>>(emptyList())
        private set
    var alphabetLoading by mutableStateOf(true)
        private set
    var alphabetError by mutableStateOf<String?>(null)
        private set
    var jumpRequest by mutableStateOf<MovieJump?>(null)
        private set

    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime + Dispatchers.Main.immediate)
    private val client = PlexClient(connection)
    private val pages = mutableStateMapOf<Int, PlexBrowsePage>()
    private val stalePages = mutableSetOf<Int>()
    private val pageFailures = mutableMapOf<Int, String>()
    private val running = linkedMapOf<Long, Running>()
    private val refreshes = linkedMapOf<String, MetadataWork>()
    private val failedRefreshes = mutableSetOf<String>()
    private val metadataVersions = mutableMapOf<String, Long>()
    private val overrides = mutableMapOf<String, MetadataOverride>()
    private var pendingDetails: MetadataWork? = null
    private var refreshError: String? = null
    private var initialized = false
    private var totalReady = false
    private var alphabetReady = false
    private var alphabetPending = true
    private var alphabetVersion = 0L
    private var jumpId = 0L
    private var pendingJump: String? = null
    private var jumpPrefetchPage: Int? = null
    private var closed = false
    private var epoch = 0
    private var workId = 0L
    private var metadataId = 0L
    private var metadataRevision = 0L
    private var detailsVersion = 0L
    private var firstIndex = 0
    private var lastIndex = 0
    private var visiblePages = listOf(0)
    private var wantedPages = listOf(0)

    private sealed interface Work {
        val epoch: Int
    }

    private data class PageWork(
        override val epoch: Int,
        val page: Int,
        val sort: MovieSort,
        val unwatched: Boolean,
        val metadataRevision: Long,
    ) : Work

    private data class MetadataWork(
        override val epoch: Int,
        val ratingKey: String,
        val version: Long,
        val detailsVersion: Long?,
    ) : Work

    private data class AlphabetWork(
        override val epoch: Int,
        val version: Long,
        val unwatched: Boolean,
    ) : Work

    private data class Running(val work: Work, val job: Job)
    private data class MetadataOverride(val item: PlexBrowseItem, val revision: Long)

    init {
        pump()
    }

    fun itemAt(index: Int): PlexBrowseItem? {
        if (index !in 0 until total) return null
        val page = pages[index / pageSize] ?: return null
        return page.items.getOrNull(index - page.start)
    }

    fun requestRange(firstIndex: Int, lastIndex: Int) {
        if (closed) return
        this.firstIndex = firstIndex.coerceAtLeast(0)
        this.lastIndex = lastIndex.coerceAtLeast(this.firstIndex)
        jumpPrefetchPage?.let { page ->
            if (page in this.firstIndex / pageSize..this.lastIndex / pageSize) jumpPrefetchPage = null
        }
        updateWindow()
        running.values.forEach { active ->
            val page = active.work as? PageWork
            if (page != null && page.page !in wantedPages) active.job.cancel()
        }
        updateError()
        pump()
    }

    fun setSort(sort: MovieSort) {
        if (closed || this.sort == sort) return
        currentSort = sort
        resetQuery()
    }

    fun setUnwatchedOnly(unwatchedOnly: Boolean) {
        if (closed || this.unwatchedOnly == unwatchedOnly) return
        unwatchedFilter = unwatchedOnly
        invalidateAlphabet()
        resetQuery()
    }

    fun jumpToLetter(key: String) {
        if (closed || alphabet.none { it.key == key && it.count > 0 }) return
        if (sort != MovieSort.TITLE) setSort(MovieSort.TITLE)
        pendingJump = key
        publishPendingJump()
        pump()
    }

    fun retryAlphabet() {
        if (closed || alphabetLoading) return
        // Refresh both snapshots: a changed library can make only retrying the counts disagree forever.
        invalidateMembership(0)
        pump()
    }

    fun retry() {
        if (closed) return
        pageFailures.clear()
        val retryKeys = failedRefreshes.toList()
        failedRefreshes.clear()
        refreshError = null
        retryKeys.forEach { queueRefresh(it) }
        selectedMovie?.takeIf { detailsError != null }?.let { selectMovie(it) }
        loading = !initialized
        updateError()
        pump()
    }

    fun selectMovie(movie: PlexBrowseItem) {
        if (closed) return
        detailsVersion++
        selectedMovie = cachedMovie(movie.ratingKey) ?: movie
        detailsLoading = true
        detailsError = null
        running.values.forEach { active ->
            val metadata = active.work as? MetadataWork
            if (metadata != null && (metadata.detailsVersion != null || metadata.ratingKey == movie.ratingKey)) {
                active.job.cancel()
            }
        }
        refreshes.remove(movie.ratingKey)
        pendingDetails = newMetadataWork(movie.ratingKey, detailsVersion)
        pruneMetadata()
        pump()
    }

    fun closeDetails() {
        if (closed) return
        detailsVersion++
        selectedMovie = null
        detailsLoading = false
        detailsError = null
        pendingDetails = null
        running.values.forEach { active ->
            if ((active.work as? MetadataWork)?.detailsVersion != null) active.job.cancel()
        }
        pruneMetadata()
        pump()
    }

    fun refreshMovie(ratingKey: String) {
        if (closed) return
        queueRefresh(ratingKey)
        pump()
    }

    fun close() {
        if (closed) return
        closed = true
        lifetime.cancel()
        pendingDetails = null
        refreshes.clear()
        pages.clear()
        stalePages.clear()
        pageFailures.clear()
        failedRefreshes.clear()
        metadataVersions.clear()
        overrides.clear()
        selectedMovie = null
        alphabet = emptyList()
        alphabetLoading = false
        clearJump()
    }

    private fun resetQuery() {
        epoch++
        generation++
        running.values.forEach { if (it.work !is AlphabetWork) it.job.cancel() }
        pendingDetails = null
        refreshes.clear()
        pages.clear()
        stalePages.clear()
        pageFailures.clear()
        failedRefreshes.clear()
        metadataVersions.clear()
        overrides.clear()
        refreshError = null
        selectedMovie = null
        detailsVersion++
        detailsLoading = false
        detailsError = null
        clearJump()
        total = 0
        initialized = false
        totalReady = false
        loading = true
        error = null
        firstIndex = 0
        lastIndex = 0
        updateWindow()
        pump()
    }

    private fun clearJump() {
        pendingJump = null
        jumpPrefetchPage = null
        jumpRequest = null
    }

    private fun invalidateAlphabet() {
        alphabetVersion++
        alphabet = emptyList()
        alphabetReady = false
        alphabetPending = true
        alphabetLoading = true
        alphabetError = null
        clearJump()
        running.values.forEach { if (it.work is AlphabetWork) it.job.cancel() }
    }

    private fun reconcileAlphabet() {
        if (!alphabetReady || !totalReady) return
        val bucketTotal = alphabet.lastOrNull()?.let { it.start + it.count } ?: 0
        if (bucketTotal != total) {
            // Separate PMS snapshots can disagree during a library update; never guess an offset.
            alphabet = emptyList()
            alphabetReady = false
            alphabetError = "Plex alphabet counts changed; retry the alphabet."
            clearJump()
            return
        }
        publishPendingJump()
    }

    private fun publishPendingJump() {
        val key = pendingJump ?: return
        if (sort != MovieSort.TITLE || !totalReady || !alphabetReady || alphabetLoading) return
        val letter = alphabet.firstOrNull { it.key == key && it.count > 0 } ?: return
        pendingJump = null
        jumpPrefetchPage = letter.start / pageSize
        firstIndex = letter.start
        lastIndex = letter.start
        updateWindow()
        jumpRequest = MovieJump(++jumpId, letter.start)
    }

    private fun updateWindow() {
        if (!initialized) {
            visiblePages = listOf(0)
            wantedPages = visiblePages
        } else if (total == 0) {
            visiblePages = emptyList()
            wantedPages = emptyList()
        } else {
            val lastPage = (total - 1) / pageSize
            val first = firstIndex.coerceAtMost(total - 1) / pageSize
            // A real viewport is small; even an oversized caller range cannot grow the cache/queue.
            val requestedLast = minOf(lastIndex.coerceAtMost(total - 1) / pageSize, first + MAX_PAGES - 1)
            val jumpPage = jumpPrefetchPage?.takeIf { it in 0..lastPage }
            val last = if (jumpPage != null && jumpPage !in first..requestedLast &&
                requestedLast - first + 1 == MAX_PAGES
            ) requestedLast - 1 else requestedLast
            visiblePages = (first..last).toList()
            wantedPages = buildList {
                addAll(visiblePages)
                if (jumpPage != null && jumpPage !in visiblePages) add(jumpPage)
                for (neighbor in listOf(last + 1, first - 1, last + 2)) {
                    if (size < MAX_PAGES && neighbor in 0..lastPage && neighbor !in this) add(neighbor)
                }
            }
        }
        pageFailures.keys.retainAll(wantedPages.toSet())
    }

    private fun needsPage(page: Int): Boolean =
        (page !in pages || page in stalePages) && page !in pageFailures &&
            running.values.none { (it.work as? PageWork)?.let { work -> work.epoch == epoch && work.page == page } == true }

    private fun nextWork(): Work? {
        pendingDetails?.let {
            pendingDetails = null
            return it
        }
        // Details are interactive; visible/activated pages outrank background metadata and alphabet.
        val visible = visiblePages.firstOrNull(::needsPage)
            ?: jumpPrefetchPage?.takeIf(::needsPage)
        if (visible != null) return PageWork(epoch, visible, sort, unwatchedOnly, metadataRevision)
        refreshes.entries.firstOrNull()?.let { (key, work) ->
            refreshes.remove(key)
            return work
        }
        if (alphabetPending) {
            alphabetPending = false
            return AlphabetWork(epoch, alphabetVersion, unwatchedOnly)
        }
        val page = wantedPages.firstOrNull(::needsPage) ?: return null
        return PageWork(epoch, page, sort, unwatchedOnly, metadataRevision)
    }

    private fun pump() {
        if (closed || !lifetime.isActive) return
        val urgent = pendingDetails != null || refreshes.isNotEmpty() || visiblePages.any(::needsPage)
        if (urgent && running.size >= MAX_REQUESTS) {
            running.values.forEach { active ->
                val work = active.work as? PageWork
                if (work != null && work.page !in visiblePages && work.page != jumpPrefetchPage) active.job.cancel()
            }
        }
        while (running.size < MAX_REQUESTS) {
            val work = nextWork() ?: break
            val id = ++workId
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    when (work) {
                        is PageWork -> loadPage(work)
                        is MetadataWork -> loadMetadata(work)
                        is AlphabetWork -> loadAlphabet(work)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (isCurrent(work)) recordFailure(work, failure)
                } finally {
                    running.remove(id)
                    if (!closed && lifetime.isActive) {
                        pruneMetadata()
                        loading = !initialized && 0 !in pageFailures
                        updateError()
                        pump()
                    }
                }
            }
            running[id] = Running(work, job)
            job.start()
        }
    }

    private suspend fun loadPage(work: PageWork) {
        val page = client.moviePage(library.key, work.page * pageSize, pageSize, work.sort, work.unwatched)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(work)) return
        val expectedCount = (page.total.toLong() - page.start).coerceIn(0, pageSize.toLong()).toInt()
        if (page.start != work.page * pageSize || page.items.size != expectedCount) {
            throw IOException("Plex returned inconsistent movie page information.")
        }
        val needsMerge = page.items.any { item ->
            (overrides[item.ratingKey]?.revision ?: 0) > work.metadataRevision
        }
        pages[work.page] = if (needsMerge) page.copy(items = page.items.map { item ->
            overrides[item.ratingKey]?.takeIf { it.revision > work.metadataRevision }?.item ?: item
        }) else page
        stalePages.remove(work.page)
        pageFailures.remove(work.page)
        initialized = true
        total = page.total
        totalReady = true
        loading = false
        updateWindow()
        reconcileAlphabet()
        evictPages()
    }

    private suspend fun loadAlphabet(work: AlphabetWork) {
        val letters = client.movieAlphabet(library.key, work.unwatched)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(work)) return
        alphabet = letters
        alphabetReady = true
        alphabetLoading = false
        alphabetError = null
        reconcileAlphabet()
    }

    private suspend fun loadMetadata(work: MetadataWork) {
        val movie = client.movieDetails(work.ratingKey)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(work) || metadataVersions[work.ratingKey] != work.version) return
        val previous = cachedMovie(movie.ratingKey) ?: selectedMovie?.takeIf { it.ratingKey == movie.ratingKey }
        val changedPage = pages.entries.firstOrNull { (_, page) -> page.items.any { it.ratingKey == movie.ratingKey } }?.key
        metadataRevision++
        overrides[movie.ratingKey] = MetadataOverride(movie, metadataRevision)
        pages.keys.toList().forEach { key ->
            val page = pages.getValue(key)
            if (page.items.any { it.ratingKey == movie.ratingKey }) {
                pages[key] = page.copy(items = page.items.map { if (it.ratingKey == movie.ratingKey) movie else it })
            }
        }
        if (selectedMovie?.ratingKey == movie.ratingKey) {
            selectedMovie = movie
            detailsLoading = false
            detailsError = null
        }
        failedRefreshes.remove(movie.ratingKey)
        if (failedRefreshes.isEmpty()) refreshError = null
        if (unwatchedOnly && (previous == null || movie.watched != previous.watched)) {
            invalidateMembership(changedPage ?: 0, movie.ratingKey)
        }
    }

    private fun invalidateMembership(firstChangedPage: Int, refreshedKey: String? = null) {
        // Keep the public generation, populated slots, total, and viewport until replacements arrive.
        epoch++
        totalReady = false
        invalidateAlphabet()
        running.values.forEach { it.job.cancel() }
        pageFailures.clear()
        stalePages.addAll(pages.keys.filter { it >= firstChangedPage })
        // Even removal beyond the viewport must refresh a visible page's total before a jump.
        stalePages.addAll(visiblePages.filter { it in pages })
        val refreshKeys = refreshes.keys.toMutableSet()
        running.values.forEach { active ->
            val metadata = active.work as? MetadataWork
            if (metadata != null && metadata.detailsVersion == null && metadata.ratingKey != refreshedKey) {
                refreshKeys.add(metadata.ratingKey)
            }
        }
        refreshes.clear()
        refreshKeys.forEach { key -> refreshes[key] = newMetadataWork(key, null) }
        pendingDetails = selectedMovie?.takeIf { detailsLoading }?.let { newMetadataWork(it.ratingKey, detailsVersion) }
        updateWindow()
    }

    private fun queueRefresh(ratingKey: String) {
        if (cachedMovie(ratingKey) == null && selectedMovie?.ratingKey != ratingKey) return
        failedRefreshes.remove(ratingKey)
        if (failedRefreshes.isEmpty()) refreshError = null
        running.values.forEach { active ->
            if ((active.work as? MetadataWork)?.ratingKey == ratingKey) active.job.cancel()
        }
        if (pendingDetails?.ratingKey == ratingKey) pendingDetails = null
        refreshes[ratingKey] = newMetadataWork(ratingKey, null)
    }

    private fun newMetadataWork(ratingKey: String, detailsVersion: Long?): MetadataWork {
        val version = ++metadataId
        metadataVersions[ratingKey] = version
        return MetadataWork(epoch, ratingKey, version, detailsVersion)
    }

    private fun cachedMovie(ratingKey: String): PlexBrowseItem? =
        pages.values.firstNotNullOfOrNull { page -> page.items.firstOrNull { it.ratingKey == ratingKey } }

    private fun isCurrent(work: Work): Boolean = !closed && lifetime.isActive &&
        if (work is AlphabetWork) work.version == alphabetVersion && work.unwatched == unwatchedOnly
        else work.epoch == epoch

    private fun recordFailure(work: Work, failure: Exception) {
        val message = if (failure is IOException) failure.message ?: "Plex could not load the movies." else "Plex could not load the movies."
        when (work) {
            is PageWork -> pageFailures[work.page] = message
            is MetadataWork -> if (metadataVersions[work.ratingKey] == work.version) {
                if (selectedMovie?.ratingKey == work.ratingKey) {
                    detailsLoading = false
                    detailsError = message
                }
                if (work.detailsVersion == null) {
                    failedRefreshes.add(work.ratingKey)
                    refreshError = message
                }
            }
            is AlphabetWork -> {
                alphabetReady = false
                alphabetLoading = false
                alphabetError = if (failure is IOException) message else "Plex could not load the alphabet."
                clearJump()
            }
        }
    }

    private fun updateError() {
        error = visiblePages.firstNotNullOfOrNull { pageFailures[it] } ?: refreshError
    }

    private fun evictPages() {
        if (total == 0) pages.clear()
        else pages.keys.filter { it.toLong() * pageSize >= total }.forEach { pages.remove(it) }
        while (pages.size > MAX_PAGES) {
            val victim = pages.keys.filter { it !in visiblePages && it != jumpPrefetchPage }.maxByOrNull { page ->
                minOf(kotlin.math.abs(page - visiblePages.first()), kotlin.math.abs(page - visiblePages.last()))
            } ?: break
            pages.remove(victim)
        }
        stalePages.retainAll(pages.keys)
    }

    private fun pruneMetadata() {
        val retained = mutableSetOf<String>()
        pages.values.forEach { page -> page.items.forEach { retained.add(it.ratingKey) } }
        selectedMovie?.let { retained.add(it.ratingKey) }
        refreshes.keys.retainAll(retained)
        failedRefreshes.retainAll(retained)
        pendingDetails?.let { retained.add(it.ratingKey) }
        running.values.forEach { active -> (active.work as? MetadataWork)?.let { retained.add(it.ratingKey) } }
        metadataVersions.keys.retainAll(retained)
        overrides.keys.retainAll(retained)
        if (failedRefreshes.isEmpty()) refreshError = null
    }

    private companion object {
        const val MAX_PAGES = 12
        const val MAX_REQUESTS = 2
    }
}
