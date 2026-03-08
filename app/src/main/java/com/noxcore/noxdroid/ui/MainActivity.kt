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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverEditText = findViewById(R.id.serverEditText)
        secretEditText = findViewById(R.id.secretEditText)
        clientIdEditText = findViewById(R.id.clientIdEditText)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        connectButton.setOnClickListener {
            val serverUrl = serverEditText.text?.toString().orEmpty().trim()
            val secret = secretEditText.text?.toString().orEmpty()
            val clientId = clientIdEditText.text?.toString().orEmpty()

            activeJob?.cancel()
            activeJob = lifecycleScope.launch {
                setState(ConnectionState.Connecting(serverUrl))
                val result = connectionService.connect(
                    serverUrl = serverUrl,
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

    private fun setState(state: ConnectionState) {
        when (state) {
            is ConnectionState.Idle -> {
                connectButton.text = getString(R.string.action_run_handshake_test)
                statusText.text = getString(R.string.status_idle)
            }

            is ConnectionState.Connecting -> {
                connectButton.text = getString(R.string.action_run_handshake_test)
                statusText.text = "Testing NoxCore WSS handshake at ${state.endpoint}..."
            }

            is ConnectionState.Connected -> {
                connectButton.text = getString(R.string.action_run_handshake_test)
                statusText.text = "Handshake test passed for ${state.endpoint}: ${state.message}"
            }

            is ConnectionState.Error -> {
                connectButton.text = getString(R.string.action_run_handshake_test)
                statusText.text = "Handshake test failed: ${state.details}"
            }
        }
    }
}
