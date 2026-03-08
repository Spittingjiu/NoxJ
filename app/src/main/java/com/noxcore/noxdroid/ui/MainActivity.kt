package com.noxcore.noxdroid.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.noxcore.noxdroid.R
import com.noxcore.noxdroid.core.connection.ConnectionState
import com.noxcore.noxdroid.core.connection.SocketConnectionService
import com.noxcore.noxdroid.core.vpn.NoxVpnService
import com.noxcore.noxdroid.core.vpn.NoxVpnState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val connectionService = SocketConnectionService()

    private lateinit var serverEditText: TextInputEditText
    private lateinit var secretEditText: TextInputEditText
    private lateinit var clientIdEditText: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var vpnButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var vpnStatusText: TextView

    private var activeJob: Job? = null
    private var currentVpnState: NoxVpnState = NoxVpnState.Idle
    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                NoxVpnService.start(this)
            } else {
                setVpnStatus(
                    NoxVpnState.Error(getString(R.string.status_vpn_permission_denied))
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverEditText = findViewById(R.id.serverEditText)
        secretEditText = findViewById(R.id.secretEditText)
        clientIdEditText = findViewById(R.id.clientIdEditText)
        connectButton = findViewById(R.id.connectButton)
        vpnButton = findViewById(R.id.vpnButton)
        statusText = findViewById(R.id.statusText)
        vpnStatusText = findViewById(R.id.vpnStatusText)

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

        vpnButton.setOnClickListener {
            when (currentVpnState) {
                is NoxVpnState.RunningForwarding,
                is NoxVpnState.Starting -> NoxVpnService.stop(this)

                else -> requestAndStartVpn()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                NoxVpnService.vpnState.collect { state ->
                    setVpnStatus(state)
                }
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

    private fun requestAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            NoxVpnService.start(this)
        }
    }

    private fun setVpnStatus(state: NoxVpnState) {
        currentVpnState = state
        when (state) {
            is NoxVpnState.Idle -> {
                vpnButton.text = getString(R.string.action_start_vpn)
                vpnStatusText.text = getString(R.string.status_vpn_idle)
            }

            is NoxVpnState.Starting -> {
                vpnButton.text = getString(R.string.action_stop_vpn)
                vpnStatusText.text = getString(R.string.status_vpn_starting)
            }

            is NoxVpnState.RunningForwarding -> {
                vpnButton.text = getString(R.string.action_stop_vpn)
                vpnStatusText.text = getString(
                    R.string.status_vpn_running_forwarding,
                    state.totalPackets,
                    state.ipv4Packets,
                    state.tcpPackets,
                    state.activeForwardSessions,
                    state.uplinkBytes,
                    state.downlinkBytes,
                    state.connectFailures,
                    state.lastPacketSummary
                )
            }

            is NoxVpnState.Stopping -> {
                vpnButton.text = getString(R.string.action_start_vpn)
                vpnStatusText.text = getString(R.string.status_vpn_stopping)
            }

            is NoxVpnState.Error -> {
                vpnButton.text = getString(R.string.action_start_vpn)
                vpnStatusText.text = getString(R.string.status_vpn_error, state.details)
            }
        }
    }
}
