package com.pacemckinney.tally.data

import com.pacemckinney.tally.engine.Account
import com.pacemckinney.tally.engine.Categories
import com.pacemckinney.tally.engine.Txn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class PlaidException(
    val errorCode: String?,
    val errorType: String?,
    message: String,
) : IOException(message) {
    val needsReauth: Boolean
        get() = errorCode in setOf("ITEM_LOGIN_REQUIRED", "PENDING_EXPIRATION", "PENDING_DISCONNECT", "ACCESS_NOT_GRANTED")
}

data class SyncPage(
    val added: List<Txn>,
    val modified: List<Txn>,
    val removed: List<String>,
    val nextCursor: String,
    val hasMore: Boolean,
)

/**
 * Talks to Plaid's REST API straight from the phone, using the keys you entered in Settings.
 * Plaid's sign convention is kept: positive amount = money out.
 */
class PlaidApi(private val clientId: String, private val secret: String, environment: String) {
    private val base = if (environment == "sandbox") "https://sandbox.plaid.com" else "https://production.plaid.com"

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        body.put("client_id", clientId)
        body.put("secret", secret)
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Plaid-Version", "2020-09-14")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "{}"
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) {
                throw PlaidException(
                    json.optString("error_code").ifBlank { null },
                    json.optString("error_type").ifBlank { null },
                    json.optString("display_message").takeIf { it.isNotBlank() && it != "null" }
                        ?: json.optString("error_message").ifBlank { "Plaid returned HTTP $code" },
                )
            }
            json
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Link token for connecting a new bank, or (with [accessToken]) for "update mode" to fix a
     * login that UCU expired.
     */
    suspend fun createLinkToken(packageName: String, accessToken: String? = null): String {
        val body = JSONObject().apply {
            put("client_name", "Tally")
            put("language", "en")
            put("country_codes", JSONArray().put("US"))
            put("user", JSONObject().put("client_user_id", "tally-owner"))
            put("android_package_name", packageName)
            if (accessToken != null) {
                put("access_token", accessToken)
            } else {
                put("products", JSONArray().put("transactions"))
                put("transactions", JSONObject().put("days_requested", 730))
            }
        }
        return post("/link/token/create", body).getString("link_token")
    }

    /** Returns (access_token, item_id). */
    suspend fun exchangePublicToken(publicToken: String): Pair<String, String> {
        val r = post("/item/public_token/exchange", JSONObject().put("public_token", publicToken))
        return r.getString("access_token") to r.getString("item_id")
    }

    suspend fun syncPage(accessToken: String, cursor: String?): SyncPage {
        val body = JSONObject().put("access_token", accessToken).put("count", 500)
            .put("options", JSONObject().put("include_original_description", true))
        if (cursor != null) body.put("cursor", cursor)
        val r = post("/transactions/sync", body)
        val removed = r.getJSONArray("removed").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("transaction_id") } }
        return SyncPage(
            added = parseTxns(r.getJSONArray("added")),
            modified = parseTxns(r.getJSONArray("modified")),
            removed = removed,
            nextCursor = r.getString("next_cursor"),
            hasMore = r.getBoolean("has_more"),
        )
    }

    /** Cached balances (free, refreshed by Plaid a few times a day). */
    suspend fun accounts(accessToken: String, itemId: String): List<Account> =
        parseAccounts(post("/accounts/get", JSONObject().put("access_token", accessToken)), itemId)

    /** Live balances pulled from UCU right now (slower). */
    suspend fun liveBalances(accessToken: String, itemId: String): List<Account> =
        parseAccounts(post("/accounts/balance/get", JSONObject().put("access_token", accessToken)), itemId)

    suspend fun institutionName(accessToken: String): String? = try {
        val item = post("/item/get", JSONObject().put("access_token", accessToken)).getJSONObject("item")
        val insId = item.optString("institution_id").ifBlank { null } ?: return null
        post("/institutions/get_by_id", JSONObject().put("institution_id", insId)
            .put("country_codes", JSONArray().put("US")))
            .getJSONObject("institution").optString("name").ifBlank { null }
    } catch (e: Exception) { null }

    suspend fun removeItem(accessToken: String) {
        post("/item/remove", JSONObject().put("access_token", accessToken))
    }

    private fun parseAccounts(r: JSONObject, itemId: String): List<Account> {
        val a = r.getJSONArray("accounts")
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val bal = o.getJSONObject("balances")
            Account(
                id = o.getString("account_id"),
                itemId = itemId,
                name = o.optString("official_name").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("name", "Account"),
                mask = o.optString("mask").takeIf { it.isNotBlank() && it != "null" },
                type = o.optString("type"),
                subtype = o.optString("subtype").takeIf { it.isNotBlank() && it != "null" },
                current = bal.optDoubleOrNull("current"),
                available = bal.optDoubleOrNull("available"),
                creditLimit = bal.optDoubleOrNull("limit"),
            )
        }
    }

    private fun parseTxns(a: JSONArray): List<Txn> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        val pfc = o.optJSONObject("personal_finance_category")
        Txn(
            id = o.getString("transaction_id"),
            accountId = o.getString("account_id"),
            amount = o.getDouble("amount"),
            date = LocalDate.parse(o.getString("date")),
            name = o.optString("name").ifBlank { o.optString("original_description", "Transaction") },
            merchant = o.optString("merchant_name").takeIf { it.isNotBlank() && it != "null" },
            category = pfc?.optString("primary")?.takeIf { it.isNotBlank() } ?: Categories.OTHER,
            detailed = pfc?.optString("detailed")?.takeIf { it.isNotBlank() },
            pending = o.optBoolean("pending"),
            logoUrl = o.optString("logo_url").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
}
