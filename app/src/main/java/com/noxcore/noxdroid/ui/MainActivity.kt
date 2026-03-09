package com.noxcore.noxdroid.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.noxcore.noxdroid.R
import com.noxcore.noxdroid.core.connection.ConnectionState
import com.noxcore.noxdroid.core.connection.NoxClientConfig
import com.noxcore.noxdroid.core.connection.NoxClientConfigStore
import com.noxcore.noxdroid.core.connection.SocketConnectionService
import com.noxcore.noxdroid.core.diagnostics.DiagnosticsLog
import com.noxcore.noxdroid.core.vpn.NoxVpnRoutingConfigStore
import com.noxcore.noxdroid.core.vpn.NoxVpnService
import com.noxcore.noxdroid.core.vpn.NoxVpnState
import com.noxcore.noxdroid.core.vpn.ParsedIpv4Routes
import com.noxcore.noxdroid.core.vpn.VpnRoutingConfig
import com.noxcore.noxdroid.core.vpn.VpnRoutingMode
import com.noxcore.noxdroid.core.vpn.VpnRoutingParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val connectionService = SocketConnectionService()

    private lateinit var serverEditText: TextInputEditText
    private lateinit var secretEditText: TextInputEditText
    private lateinit var clientIdEditText: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var vpnButton: MaterialButton
    private lateinit var routingModeSwitch: MaterialSwitch
    private lateinit var publicRoutesInputLayout: TextInputLayout
    private lateinit var publicRoutesEditText: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var vpnStatusText: TextView
    private lateinit var diagnosticsText: TextView

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
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.rootScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        serverEditText = findViewById(R.id.serverEditText)
        secretEditText = findViewById(R.id.secretEditText)
        clientIdEditText = findViewById(R.id.clientIdEditText)
        connectButton = findViewById(R.id.connectButton)
        vpnButton = findViewById(R.id.vpnButton)
        routingModeSwitch = findViewById(R.id.routingModeSwitch)
        publicRoutesInputLayout = findViewById(R.id.publicRoutesInputLayout)
        publicRoutesEditText = findViewById(R.id.publicRoutesEditText)
        statusText = findViewById(R.id.statusText)
        vpnStatusText = findViewById(R.id.vpnStatusText)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        DiagnosticsLog.initialize(applicationContext)

        NoxClientConfigStore.load(this)?.let { cfg ->
            serverEditText.setText(cfg.serverUrl)
            secretEditText.setText(cfg.sharedSecret)
            clientIdEditText.setText(cfg.clientId)
        }
        NoxVpnRoutingConfigStore.load(this).let { routing ->
            routingModeSwitch.isChecked = routing.mode == VpnRoutingMode.CONTROLLED_PUBLIC
            if (routing.publicIpv4CidrsRaw.isNotBlank()) {
                publicRoutesEditText.setText(routing.publicIpv4CidrsRaw)
            }
        }
        updateRoutingUi()

        routingModeSwitch.setOnCheckedChangeListener { _, _ ->
            updateRoutingUi()
        }

        connectButton.setOnClickListener {
            val serverUrl = serverEditText.text?.toString().orEmpty().trim()
            val secret = secretEditText.text?.toString().orEmpty()
            val clientId = clientIdEditText.text?.toString().orEmpty()
            persistConfig(serverUrl, secret, clientId)

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

                else -> {
                    val serverUrl = serverEditText.text?.toString().orEmpty().trim()
                    val secret = secretEditText.text?.toString().orEmpty()
                    val clientId = clientIdEditText.text?.toString().orEmpty()
                    if (!persistConfig(serverUrl, secret, clientId)) {
                        setVpnStatus(
                            NoxVpnState.Error("Server URL, shared secret, and client ID are required")
                        )
                        return@setOnClickListener
                    }
                    val routingValidationError = persistRoutingConfigOrError()
                    if (routingValidationError != null) {
                        setVpnStatus(NoxVpnState.Error(routingValidationError))
                        return@setOnClickListener
                    }
                    requestAndStartVpn()
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                NoxVpnService.vpnState.collect { state ->
                    setVpnStatus(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                DiagnosticsLog.tailText.collect { text ->
                    diagnosticsText.text = text.ifBlank { getString(R.string.status_diag_empty) }
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
                    state.activeTcpSessions,
                    state.activeForwardSessions,
                    if (state.transportConnected) "up" else "down",
                    state.reconnectAttempts,
                    state.reconnectSuccesses,
                    state.uplinkBytes,
                    state.downlinkBytes,
                    state.connectFailures,
                    state.droppedForwardPackets,
                    state.quicFallbackSignals,
                    state.youtubeFallbackOpenSuccesses,
                    state.youtubeFallbackOpenFailures,
                    state.youtubeFallbackDownlinkBytes,
                    state.youtubeFallbackCompletedFlows,
                    state.youtubeFallbackSuccessfulFlows,
                    state.youtubeFallbackEarlyCloseFlows,
                    state.youtubeFallbackVerdict,
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

    private fun updateRoutingUi() {
        val controlledPublicMode = routingModeSwitch.isChecked
        publicRoutesInputLayout.isEnabled = controlledPublicMode
        publicRoutesEditText.isEnabled = controlledPublicMode
        publicRoutesEditText.alpha = if (controlledPublicMode) 1f else 0.6f
    }

    private fun persistConfig(serverUrl: String, secret: String, clientId: String): Boolean {
        if (serverUrl.isBlank() || secret.isBlank() || clientId.isBlank()) {
            return false
        }
        NoxClientConfigStore.save(
            this,
            NoxClientConfig(
                serverUrl = serverUrl,
                sharedSecret = secret,
                clientId = clientId
            )
        )
        return true
    }

    private fun persistRoutingConfigOrError(): String? {
        val mode = if (routingModeSwitch.isChecked) {
            VpnRoutingMode.CONTROLLED_PUBLIC
        } else {
            VpnRoutingMode.SAFE_PRIVATE_SPLIT
        }
        val rawPublicRoutes = publicRoutesEditText.text?.toString().orEmpty().trim()

        if (mode == VpnRoutingMode.CONTROLLED_PUBLIC) {
            val parsedRoutes = VpnRoutingParser.parseIpv4Cidrs(rawPublicRoutes)
            validateControlledRoutes(parsedRoutes)?.let { return it }
        }

        NoxVpnRoutingConfigStore.save(
            this,
            VpnRoutingConfig(
                mode = mode,
                publicIpv4CidrsRaw = rawPublicRoutes
            )
        )
        return null
    }

    private fun validateControlledRoutes(parsedRoutes: ParsedIpv4Routes): String? {
        if (parsedRoutes.invalidEntries.isNotEmpty()) {
            val bad = parsedRoutes.invalidEntries.take(3).joinToString(", ")
            return "Invalid IPv4 CIDR entries: $bad"
        }
        if (parsedRoutes.routes.isEmpty()) {
            return "Controlled public routing requires at least one IPv4 CIDR route"
        }
        return null
    }
}
