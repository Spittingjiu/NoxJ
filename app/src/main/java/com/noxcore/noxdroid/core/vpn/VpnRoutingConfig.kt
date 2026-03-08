package com.noxcore.noxdroid.core.vpn

import android.content.Context
import java.util.Locale

enum class VpnRoutingMode {
    SAFE_PRIVATE_SPLIT,
    CONTROLLED_PUBLIC
}

data class VpnRoutingConfig(
    val mode: VpnRoutingMode,
    val publicIpv4CidrsRaw: String
)

data class ParsedIpv4Routes(
    val routes: List<Pair<String, Int>>,
    val invalidEntries: List<String>
)

object VpnRoutingParser {

    fun parseIpv4Cidrs(raw: String): ParsedIpv4Routes {
        val tokens = raw
            .split(',', ';', '\n', '\r', '\t', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val routes = linkedSetOf<Pair<String, Int>>()
        val invalid = mutableListOf<String>()

        tokens.forEach { token ->
            val parsed = parseSingleIpv4Cidr(token)
            if (parsed == null) {
                invalid += token
            } else {
                routes += parsed
            }
        }

        return ParsedIpv4Routes(routes = routes.toList(), invalidEntries = invalid)
    }

    private fun parseSingleIpv4Cidr(value: String): Pair<String, Int>? {
        val parts = value.split('/')
        if (parts.size != 2) {
            return null
        }

        val ip = parseIpv4(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) {
            return null
        }

        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val network = ip and mask
        return intToIpv4(network) to prefix
    }

    private fun parseIpv4(value: String): Int? {
        val octets = value.split('.')
        if (octets.size != 4) {
            return null
        }

        var acc = 0
        for (octet in octets) {
            if (octet.isBlank()) {
                return null
            }
            val part = octet.toIntOrNull() ?: return null
            if (part !in 0..255) {
                return null
            }
            acc = (acc shl 8) or part
        }

        return acc
    }

    private fun intToIpv4(value: Int): String {
        val o1 = (value ushr 24) and 0xFF
        val o2 = (value ushr 16) and 0xFF
        val o3 = (value ushr 8) and 0xFF
        val o4 = value and 0xFF
        return "$o1.$o2.$o3.$o4"
    }
}

object NoxVpnRoutingConfigStore {

    fun save(context: Context, config: VpnRoutingConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, config.mode.name.lowercase(Locale.US))
            .putString(KEY_PUBLIC_CIDRS, config.publicIpv4CidrsRaw)
            .apply()
    }

    fun load(context: Context): VpnRoutingConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = when (prefs.getString(KEY_MODE, MODE_SAFE_PRIVATE)) {
            MODE_CONTROLLED_PUBLIC -> VpnRoutingMode.CONTROLLED_PUBLIC
            else -> VpnRoutingMode.SAFE_PRIVATE_SPLIT
        }

        return VpnRoutingConfig(
            mode = mode,
            publicIpv4CidrsRaw = prefs.getString(KEY_PUBLIC_CIDRS, "").orEmpty()
        )
    }

    private const val PREFS_NAME = "nox_vpn_routing_config"
    private const val KEY_MODE = "mode"
    private const val KEY_PUBLIC_CIDRS = "public_ipv4_cidrs"
    private const val MODE_SAFE_PRIVATE = "safe_private_split"
    private const val MODE_CONTROLLED_PUBLIC = "controlled_public"
}
