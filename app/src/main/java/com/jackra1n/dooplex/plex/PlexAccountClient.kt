package com.jackra1n.dooplex.plex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class PlexAccountClient(private val clientId: String) {
    init {
        require(headerValue(clientId)) { "A stable Plex client identifier is required." }
    }

    suspend fun createPin(): PlexPin = withContext(Dispatchers.IO) {
        json {
            val value = JSONObject(request("https://plex.tv/api/v2/pins", method = "POST", body = "strong=false"))
            val id = value.get("id").toString()
            val code = value.getString("code")
            if (!Regex("[0-9]+").matches(id) || !Regex("[A-Za-z0-9]+").matches(code)) {
                throw IOException("Plex returned an invalid link code.")
            }
            val expires = try {
                Instant.parse(value.getString("expiresAt")).toEpochMilli()
            } catch (_: Exception) {
                throw IOException("Plex returned an invalid link expiry.")
            }
            val qr = value.optString("qr").takeIf(::plexHosted) ?: "https://plex.tv/api/v2/pins/qr/${encode(code)}"
            PlexPin(id, code, expires, qr)
        }
    }

    /** The same public QR image the official Plex apps show for a link PIN. */
    suspend fun pinQr(pin: PlexPin): Bitmap = withContext(Dispatchers.IO) {
        require(plexHosted(pin.qrUrl)) { "A plex.tv QR address is required." }
        val image = fetch(pin.qrUrl, accept = "image/png", accountRequest = false)
        BitmapFactory.decodeByteArray(image, 0, image.size) ?: throw IOException("Plex returned an unreadable QR code.")
    }

    /**
     * The account's picture. plex.tv answers with a redirect to wherever the image lives, so this
     * follows redirects, and sends neither the token nor Plex headers to those hosts.
     */
    suspend fun avatar(url: String, size: Int): Bitmap = withContext(Dispatchers.IO) {
        require(plexHosted(url)) { "A plex.tv avatar address is required." }
        val http = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 8_000
            http.readTimeout = 8_000
            http.setRequestProperty("Accept", "image/*")
            if (http.responseCode !in 200..299 || !http.url.protocol.equals("https", true)) {
                throw IOException("Plex did not return an account picture.")
            }
            val bytes = http.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw IOException("The account picture is too large.")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("The account picture is unreadable.")
            Bitmap.createScaledBitmap(decoded, size, size, true)
        } finally {
            http.disconnect()
        }
    }

    suspend fun pollPin(pin: PlexPin): String? = withContext(Dispatchers.IO) {
        if (System.currentTimeMillis() >= pin.expiresAtMs) throw IOException("The Plex link code expired. Request a new code.")
        require(Regex("[0-9]+").matches(pin.id) && Regex("[A-Za-z0-9]+").matches(pin.code)) {
            "A valid Plex link code is required."
        }
        json {
            val value = JSONObject(request("https://plex.tv/api/v2/pins/${pin.id}?code=${encode(pin.code)}", pinPoll = true))
            if (value.isNull("authToken")) null else value.optString("authToken").takeIf { headerValue(it) }
        }
    }

    suspend fun account(token: String): PlexAccount = withContext(Dispatchers.IO) {
        require(headerValue(token)) { "A valid Plex account token is required." }
        json {
            val value = JSONObject(request("https://plex.tv/api/v2/user", token = token))
            val name = sequenceOf("friendlyName", "username", "title").map { value.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" } ?: "Plex account"
            PlexAccount(token, name, value.optString("thumb").takeIf(::plexHosted))
        }
    }

    /** The account's Plex watchlist, newest first. It lives on Plex's Discover service, not on a server. */
    suspend fun watchlist(token: String): List<WatchlistEntry> = withContext(Dispatchers.IO) {
        require(headerValue(token)) { "A valid Plex account token is required." }
        json {
            buildList {
                var start = 0
                while (true) {
                    val page = JSONObject(request(
                        "https://discover.provider.plex.tv/library/sections/watchlist/all?sort=watchlistedAt:desc" +
                            "&includeCollections=1&includeExternalMedia=1" +
                            "&X-Plex-Container-Start=$start&X-Plex-Container-Size=$WATCHLIST_PAGE",
                        token = token,
                    )).getJSONObject("MediaContainer")
                    val items = page.optJSONArray("Metadata") ?: break
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val guid = item.optString("guid")
                        val type = item.optString("type")
                        if (guid.startsWith("plex://") && (type == "movie" || type == "show")) {
                            add(WatchlistEntry(
                                guid = guid,
                                type = type,
                                title = item.optString("title"),
                                year = item.optString("year"),
                                summary = item.optString("summary"),
                                thumb = item.optString("thumb"),
                            ))
                        }
                    }
                    start += items.length()
                    if (items.length() < WATCHLIST_PAGE || start >= page.optInt("totalSize", Int.MAX_VALUE)) break
                }
            }
        }
    }

    /** Discover identifies a title by the last part of its `plex://` id. */
    suspend fun setWatchlisted(token: String, guid: String, watchlisted: Boolean): Unit = withContext(Dispatchers.IO) {
        require(headerValue(token)) { "A valid Plex account token is required." }
        val id = guid.substringAfterLast('/')
        require(guid.startsWith("plex://") && id.isNotEmpty() && id.all { it.isLetterOrDigit() }) { "This title can't be added to a watchlist." }
        val action = if (watchlisted) "addToWatchlist" else "removeFromWatchlist"
        request("https://discover.provider.plex.tv/actions/$action?ratingKey=$id", token = token, method = "PUT", body = "")
    }

    suspend fun servers(token: String): List<PlexServer> = withContext(Dispatchers.IO) {
        require(headerValue(token)) { "A valid Plex account token is required." }
        json {
            val resources = JSONArray(request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", token = token))
            buildList {
                for (index in 0 until resources.length()) {
                    val resource = resources.getJSONObject(index)
                    if ("server" !in resource.optString("provides").split(',').map { it.trim() }) continue
                    val id = resource.optString("clientIdentifier")
                    val accessToken = resource.optString("accessToken")
                    if (id.isBlank() || !headerValue(accessToken)) continue
                    val candidates = resource.optJSONArray("connections") ?: continue
                    val endpoints = buildList {
                        for (candidateIndex in 0 until candidates.length()) {
                            val candidate = candidates.getJSONObject(candidateIndex)
                            val uri = endpoint(candidate.optString("uri")) ?: continue
                            add(PlexServerEndpoint(uri, candidate.truth("local"), candidate.truth("relay")))
                        }
                    }.distinctBy { it.uri }.sortedWith(compareBy<PlexServerEndpoint> {
                        when { it.relay -> 3; it.local -> 0; it.uri.startsWith("https://") -> 1; else -> 2 }
                    }.thenBy { !it.uri.startsWith("https://") })
                    if (endpoints.isNotEmpty()) add(PlexServer(id, resource.optString("name", "Plex server"), accessToken, endpoints))
                }
            }
        }
    }

    suspend fun connect(server: PlexServer): PlexConnection = withContext(Dispatchers.IO) {
        require(headerValue(server.accessToken)) { "A valid Plex server token is required." }
        for (candidate in server.connections) {
            currentCoroutineContext().ensureActive()
            val uri = endpoint(candidate.uri) ?: continue
            try {
                val identity = json { JSONObject(request("$uri/identity", token = server.accessToken, accountRequest = false)) }
                    .getJSONObject("MediaContainer")
                if (identity.optString("machineIdentifier") == server.id) {
                    return@withContext PlexConnection(uri, server.accessToken, clientId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
            } catch (_: JSONException) {
                // A candidate is usable only if it identifies the selected server.
            }
        }
        throw IOException("No declared connection could reach this Plex server. Check that it is online and reachable.")
    }

    private suspend fun request(
        url: String,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
        accountRequest: Boolean = true,
        pinPoll: Boolean = false,
    ): String = String(fetch(url, token, method, body, accountRequest, pinPoll), Charsets.UTF_8)

    private suspend fun fetch(
        url: String,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
        accountRequest: Boolean = true,
        pinPoll: Boolean = false,
        accept: String = "application/json",
    ): ByteArray {
        currentCoroutineContext().ensureActive()
        var http: HttpURLConnection? = null
        try {
            val client = URI(url).toURL().openConnection() as HttpURLConnection
            http = client
            client.requestMethod = method
            client.instanceFollowRedirects = false
            client.connectTimeout = 8_000
            client.readTimeout = 8_000
            client.useCaches = false
            client.setRequestProperty("Accept", accept)
            client.setRequestProperty("X-Plex-Client-Identifier", clientId)
            client.setRequestProperty("X-Plex-Product", "Dooplex")
            client.setRequestProperty("X-Plex-Version", "1.0")
            client.setRequestProperty("X-Plex-Platform", "Android")
            client.setRequestProperty("X-Plex-Device", "Zidoo")
            client.setRequestProperty("X-Plex-Device-Name", "Dooplex on Zidoo")
            client.setRequestProperty("X-Plex-Provides", "player")
            if (token != null) client.setRequestProperty("X-Plex-Token", token)
            if (body != null) {
                client.doOutput = true
                client.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = body.toByteArray(Charsets.UTF_8)
                client.setFixedLengthStreamingMode(bytes.size)
                client.outputStream.use { it.write(bytes) }
            }
            val status = client.responseCode
            currentCoroutineContext().ensureActive()
            if (pinPoll && status in setOf(404, 410)) throw RequestFailure("The Plex link code expired. Request a new code.")
            if (accountRequest && token != null && status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw PlexAuthenticationException("Plex sign-in expired or was revoked. Link your account again.")
            }
            if (status !in 200..299) throw RequestFailure("Plex request failed (HTTP $status).")
            val output = ByteArrayOutputStream()
            client.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) throw RequestFailure("Plex response was too large.")
                    output.write(buffer, 0, count)
                }
            }
            currentCoroutineContext().ensureActive()
            return output.toByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PlexAuthenticationException) {
            throw failure
        } catch (failure: RequestFailure) {
            throw failure
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("Plex request could not be completed. Check the network connection.")
        } finally {
            http?.disconnect()
        }
    }

    private inline fun <T> json(block: () -> T): T = try {
        block()
    } catch (_: JSONException) {
        throw IOException("Plex returned invalid account or server information.")
    }

    private fun endpoint(value: String): String? {
        val uri = try { URI(value) } catch (_: Exception) { return null }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.port != -1 && uri.port !in 1..65535 || uri.rawPath.orEmpty() !in setOf("", "/")) return null
        return uri.toASCIIString().trimEnd('/')
    }

    private fun plexHosted(value: String): Boolean {
        val uri = try { URI(value) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.rawUserInfo == null && (host == "plex.tv" || host.endsWith(".plex.tv"))
    }

    private fun JSONObject.truth(name: String): Boolean = opt(name)?.toString().let {
        it == "1" || it.equals("true", ignoreCase = true)
    }

    private class RequestFailure(message: String) : IOException(message)

    private companion object {
        const val WATCHLIST_PAGE = 100

        fun headerValue(value: String): Boolean = value.isNotEmpty() && value != "null" && value.all { it.code in 33..126 }
        fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
