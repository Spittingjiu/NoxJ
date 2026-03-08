package com.noxcore.noxdroid.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.noxcore.noxdroid.R
import com.noxcore.noxdroid.core.connection.ConnectionState
import com.noxcore.noxdroid.core.connection.SocketConnectionService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val connectionService = SocketConnectionService()

    private lateinit var serverEditText: TextInputEditText
    private lateinit var secretEditText: TextInputEditText
    private lateinit var clientIdEditText: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var statusText: android.widget.TextView

    private var activeJob: Job? = null
    private var isConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverEditText = findViewById(R.id.serverEditText)
        secretEditText = findViewById(R.id.secretEditText)
        clientIdEditText = findViewById(R.id.clientIdEditText)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        connectButton.setOnClickListener {
            if (isConnected) {
                setState(ConnectionState.Idle)
                return@setOnClickListener
            }

            val endpoint = parseEndpoint(serverEditText.text?.toString().orEmpty())
            if (endpoint == null) {
                setState(ConnectionState.Error("Server must be in host:port format"))
                return@setOnClickListener
            }

            val secret = secretEditText.text?.toString().orEmpty()
            val clientId = clientIdEditText.text?.toString().orEmpty()

            activeJob?.cancel()
            activeJob = lifecycleScope.launch {
                setState(ConnectionState.Connecting("${endpoint.first}:${endpoint.second}"))
                val result = connectionService.connect(
                    host = endpoint.first,
                    port = endpoint.second,
                    sharedSecret = secret,
                    clientId = clientId
                )
                setState(result)
            }
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        super.onDestroy()
    }

    private fun parseEndpoint(value: String): Pair<String, Int>? {
        val parts = value.trim().split(":")
        if (parts.size != 2) return null

        val host = parts[0].trim()
        val port = parts[1].trim().toIntOrNull()

        if (host.isBlank() || port == null || port !in 1..65535) {
            return null
        }
        return host to port
    }

    private fun setState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Idle -> {
                isConnected = false
                connectButton.text = getString(R.string.action_connect)
                statusText.text = getString(R.string.status_idle)
            }

            is ConnectionState.Connecting -> {
                isConnected = false
                connectButton.text = getString(R.string.action_connect)
                statusText.text = "Connecting to ${state.endpoint}..."
            }

            is ConnectionState.Connected -> {
                isConnected = true
                connectButton.text = getString(R.string.action_disconnect)
                statusText.text = "Connected to ${state.endpoint}: ${state.message}"
            }

            is ConnectionState.Error -> {
                isConnected = false
                connectButton.text = getString(R.string.action_connect)
                statusText.text = "Error: ${state.details}"
            }
        }
    }
}
