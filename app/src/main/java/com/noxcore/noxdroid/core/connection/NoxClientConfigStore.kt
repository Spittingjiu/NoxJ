package com.noxcore.noxdroid.core.connection

import android.content.Context

data class NoxClientConfig(
    val serverUrl: String,
    val sharedSecret: String,
    val clientId: String
)

object NoxClientConfigStore {

    fun save(context: Context, config: NoxClientConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, config.serverUrl.trim())
            .putString(KEY_SHARED_SECRET, config.sharedSecret)
            .putString(KEY_CLIENT_ID, config.clientId.trim())
            .apply()
    }

    fun load(context: Context): NoxClientConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, "").orEmpty().trim()
        val sharedSecret = prefs.getString(KEY_SHARED_SECRET, "").orEmpty()
        val clientId = prefs.getString(KEY_CLIENT_ID, "").orEmpty().trim()
        if (serverUrl.isBlank() || sharedSecret.isBlank() || clientId.isBlank()) {
            return null
        }
        return NoxClientConfig(
            serverUrl = serverUrl,
            sharedSecret = sharedSecret,
            clientId = clientId
        )
    }

    private const val PREFS_NAME = "nox_client_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_SHARED_SECRET = "shared_secret"
    private const val KEY_CLIENT_ID = "client_id"
}
