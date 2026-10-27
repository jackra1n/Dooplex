package com.jackra1n.dooplex.plex

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A blocking socket read ignores cancellation, so a cancelled request closes its connection instead. */
internal suspend fun <T> abortOnCancel(abort: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val guard = launch {
        try {
            awaitCancellation()
        } finally {
            abort()
        }
    }
    try {
        block()
    } finally {
        guard.cancel()
    }
}
