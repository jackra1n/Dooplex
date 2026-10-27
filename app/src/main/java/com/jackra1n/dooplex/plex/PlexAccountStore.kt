package com.jackra1n.dooplex.plex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PlexAccountStore(context: Context) {
    private val preferences = context.getSharedPreferences("dooplex", Context.MODE_PRIVATE)

    val clientId: String = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit().putString("clientId", it).apply()
    }

    suspend fun load(): PlexAccount? = withContext(Dispatchers.IO) {
        val stored = preferences.getString("account", null) ?: return@withContext null
        try {
            val parts = stored.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            val decrypted = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            val account = JSONObject(String(decrypted, Charsets.UTF_8))
            PlexAccount(account.getString("token"), account.getString("displayName"))
        } catch (_: Exception) {
            throw IllegalStateException("Saved Plex sign-in could not be read. Disconnect and link your account again.")
        }
    }

    suspend fun save(account: PlexAccount) = withContext(Dispatchers.IO) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val plaintext = JSONObject()
                .put("token", account.token)
                .put("displayName", account.displayName)
                .toString().toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plaintext)
            val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            check(preferences.edit().putString("account", stored)
                .remove("server").remove("ratingKey").commit())
        } catch (_: Exception) {
            throw IllegalStateException("Plex sign-in could not be saved securely on this device.")
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().remove("account").remove("server").remove("ratingKey").commit()) {
            "Saved Plex sign-in could not be removed."
        }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "dooplex-plex-account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
