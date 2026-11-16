package com.roohu.connect

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Pc(val id: String, val name: String, val host: String, val port: Int, val fp: String, val token: String)

/** Pairing tokens are kept in EncryptedSharedPreferences (Android Keystore). */
class Store(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "roohu_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val deviceId: String
        get() = prefs.getString("did", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("did", it).apply() }

    fun load(): List<Pc> {
        val a = JSONArray(prefs.getString("pcs", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Pc(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), o.getString("fp"), o.getString("token"))
        }
    }

    fun save(list: List<Pc>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("host", it.host)
                .put("port", it.port).put("fp", it.fp).put("token", it.token))
        }
        prefs.edit().putString("pcs", a.toString()).apply()
    }
}
