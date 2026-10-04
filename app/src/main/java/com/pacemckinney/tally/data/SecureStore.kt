package com.pacemckinney.tally.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One connected bank login (a Plaid "Item"). */
data class LinkedItem(
    val itemId: String,
    val accessToken: String,
    val institution: String,
    val cursor: String?,
    val needsReauth: Boolean = false,
    val lastError: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("itemId", itemId); put("accessToken", accessToken); put("institution", institution)
        put("cursor", cursor ?: JSONObject.NULL); put("needsReauth", needsReauth)
        put("lastError", lastError ?: JSONObject.NULL)
    }

    companion object {
        fun from(o: JSONObject) = LinkedItem(
            o.getString("itemId"), o.getString("accessToken"), o.optString("institution", "Bank"),
            o.optString("cursor").takeIf { !o.isNull("cursor") && it.isNotEmpty() },
            o.optBoolean("needsReauth"), o.optString("lastError").takeIf { !o.isNull("lastError") && it.isNotEmpty() },
        )
    }
}

/**
 * Plaid keys and access tokens, encrypted with an AES key that never leaves the phone's
 * Android Keystore. Nothing secret is compiled into the APK or stored in plain text.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    var clientId: String?
        get() = get("clientId")
        set(v) = put("clientId", v?.trim())

    var secret: String?
        get() = get("secret")
        set(v) = put("secret", v?.trim())

    /** "production" or "sandbox". */
    var environment: String
        get() = prefs.getString("env", "production") ?: "production"
        set(v) = prefs.edit().putString("env", v).apply()

    val hasKeys: Boolean get() = !clientId.isNullOrBlank() && !secret.isNullOrBlank()

    @get:Synchronized @set:Synchronized
    var items: List<LinkedItem>
        get() {
            val raw = get("items") ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { LinkedItem.from(arr.getJSONObject(it)) }
        }
        set(v) = put("items", JSONArray().apply { v.forEach { put(it.toJson()) } }.toString())

    @Synchronized
    fun updateItem(itemId: String, f: (LinkedItem) -> LinkedItem) {
        items = items.map { if (it.itemId == itemId) f(it) else it }
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object { private const val ALIAS = "tally_master_key" }
}
