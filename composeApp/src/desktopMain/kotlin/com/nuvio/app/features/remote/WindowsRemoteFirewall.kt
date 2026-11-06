package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.ELEVATED_TASK_ARGUMENT
import com.nuvio.app.core.windows.PHONE_REMOTE_FIREWALL_TASK
import com.nuvio.app.core.windows.WindowsElevation
import co.touchlab.kermit.Logger
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val FIREWALL_GROUP = "Starlit phone remote"
internal const val FIREWALL_TCP_RULE = "Starlit phone remote TCP"
internal const val FIREWALL_UDP_RULE = "Starlit phone remote UDP"
internal const val FIREWALL_DISCOVERY_PORT = 47788
internal const val FIREWALL_SESSION_PORT = 47789

/**
 * One enabled inbound rule as the Windows Firewall COM API reports it. A failed
 * Result means Windows refused to report that property; it becomes "Unknown",
 * which can never prove access but can still block it.
 */
internal data class WindowsFirewallRuleRecord(
    val action: Result<Int>,
    val profiles: Result<Int>,
    val applicationName: Result<String?>,
    val protocol: Result<Int>,
    val localPorts: Result<String?>,
    val remotePorts: Result<String?>,
    val localAddresses: Result<String?>,
    val remoteAddresses: Result<String?>,
    val serviceName: Result<String?>,
    val interfacesRestricted: Result<Boolean>,
    val interfaceTypes: Result<String?>,
    val secureFlags: Result<Int>,
    val owner: Result<String?>,
    val packageId: Result<String?>,
    val localUsers: Result<String?>,
    val remoteUsers: Result<String?>,
    val remoteMachines: Result<String?>,
)

internal data class WindowsFirewallProfileRecord(val type: Int, val enabled: Boolean, val blockAllInbound: Boolean)

private const val UNKNOWN = "Unknown"

private fun Result<String?>.orAny(): String = fold(
    { value -> value?.trim()?.takeUnless { it.isEmpty() || it == "*" } ?: "Any" },
    { UNKNOWN },
)

private fun Result<String?>.orEmptyText(): String = fold({ it?.trim().orEmpty() }, { UNKNOWN })

private fun firewallActionText(action: Result<Int>): String = when (action.getOrNull()) {
    WindowsFirewallCom.NET_FW_ACTION_ALLOW -> "Allow"
    WindowsFirewallCom.NET_FW_ACTION_BLOCK -> "Block"
    else -> UNKNOWN
}

private fun firewallProfileText(profiles: Result<Int>): String {
    val mask = profiles.getOrNull() ?: return UNKNOWN
    val all = WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN or WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE or
        WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC
    if (mask == WindowsFirewallCom.NET_FW_PROFILE2_ALL || mask and all == all) return "Any"
    return listOfNotNull(
        "Domain".takeIf { mask and WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN != 0 },
        "Private".takeIf { mask and WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE != 0 },
        "Public".takeIf { mask and WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC != 0 },
    ).joinToString(", ")
}

private fun firewallProtocolText(protocol: Result<Int>): String = when (val value = protocol.getOrNull()) {
    null -> UNKNOWN
    WindowsFirewallCom.NET_FW_IP_PROTOCOL_TCP -> "TCP"
    WindowsFirewallCom.NET_FW_IP_PROTOCOL_UDP -> "UDP"
    WindowsFirewallCom.NET_FW_IP_PROTOCOL_ANY -> "Any"
    else -> value.toString()
}

/**
 * Renders the firewall state in the format [parseWindowsFirewallState] reads, so the
 * rule evaluation stays the same however the data was collected.
 */
internal fun windowsFirewallSnapshotJson(
    rules: List<WindowsFirewallRuleRecord>,
    activeProfileTypes: Int,
    profiles: List<WindowsFirewallProfileRecord>,
): String = buildJsonObject {
    put("rules", buildJsonArray {
        for (rule in rules) add(buildJsonObject {
            val secureFlags = rule.secureFlags.getOrNull()
            val ipsec = when (secureFlags) {
                null -> UNKNOWN
                0 -> "NotRequired"
                else -> "Required"
            }
            put("enabled", true)
            put("action", firewallActionText(rule.action))
            put("profile", firewallProfileText(rule.profiles))
            put("program", rule.applicationName.orAny())
            put("protocol", firewallProtocolText(rule.protocol))
            put("localPort", rule.localPorts.orAny())
            // Rules owned by a user or an app package are skipped by the parser.
            put("owner", !rule.owner.getOrNull().isNullOrBlank())
            put("package", rule.packageId.getOrNull()?.trim()?.takeUnless { it == "*" || it.equals("Any", true) }.orEmpty())
            put("remotePort", rule.remotePorts.orAny())
            put("localAddress", rule.localAddresses.orAny())
            put("remoteAddress", rule.remoteAddresses.orAny())
            put("service", rule.serviceName.fold({ it?.trim()?.takeUnless(String::isEmpty) ?: "Any" }, { UNKNOWN }))
            put("interfaceAlias", rule.interfacesRestricted.fold({ if (it) "Restricted" else "Any" }, { UNKNOWN }))
            put("interfaceType", rule.interfaceTypes.fold(
                { value -> value?.trim()?.takeUnless { it.isEmpty() || it.equals("All", true) } ?: "Any" },
                { UNKNOWN },
            ))
            put("authentication", ipsec)
            put("encryption", ipsec)
            put("overrideBlockRules", when (secureFlags) {
                null -> UNKNOWN
                0 -> "False"
                else -> "True"
            })
            put("localUser", rule.localUsers.orEmptyText())
            put("remoteUser", rule.remoteUsers.orEmptyText())
            put("remoteMachine", rule.remoteMachines.orEmptyText())
        })
    })
    put("networks", buildJsonArray {
        if (activeProfileTypes and WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN != 0) add(buildJsonObject { put("category", "DomainAuthenticated") })
        if (activeProfileTypes and WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE != 0) add(buildJsonObject { put("category", "Private") })
        if (activeProfileTypes and WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC != 0) add(buildJsonObject { put("category", "Public") })
    })
    put("profiles", buildJsonArray {
        for (profile in profiles) add(buildJsonObject {
            put("name", when (profile.type) {
                WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN -> "Domain"
                WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE -> "Private"
                else -> "Public"
            })
            put("enabled", if (profile.enabled) "True" else "False")
            put("allowInboundRules", if (profile.blockAllInbound) "False" else "True")
        })
    })
}.toString()

/** Runs in the elevated helper started by [WindowsRemoteFirewall.requestAccess]. */
internal fun allowPhoneRemoteThroughWindowsFirewall() {
    val executable = ProcessHandle.current().info().command().orElse(null)
    check(!executable.isNullOrBlank()) { "Unknown Starlit executable" }
    WindowsFirewallCom.allowPhoneRemote(executable)
}

private data class FirewallRule(
    val action: String,
    val profile: String,
    val program: String,
    val protocol: String,
    val localPort: String,
    val remotePort: String,
    val localAddress: String,
    val remoteAddress: String,
    val service: String,
    val interfaceAlias: String,
    val interfaceType: String,
    val authentication: String,
    val encryption: String,
    val overrideBlockRules: String,
    val localUser: String,
    val remoteUser: String,
    val remoteMachine: String,
)

private data class FirewallProfilePolicy(
    val name: String,
    val enabled: Boolean,
    val allowInboundRules: Boolean,
)

private fun JsonObject.requiredString(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: error("Missing or invalid field: " + key)

private fun JsonObject.requiredBooleanText(key: String): Boolean = when (requiredString(key).lowercase(Locale.ROOT)) {
    "true" -> true
    "false" -> false
    else -> error("Missing or invalid Boolean: " + key)
}

private fun normalizedWindowsPath(value: String): String {
    val raw = value.trim().trim('"').removePrefix("\\\\?\\").replace('/', '\\')
    val expanded = Regex("%([^%]+)%").replace(raw) { match ->
        System.getenv(match.groupValues[1]) ?: match.value
    }
    return runCatching { Paths.get(expanded).toAbsolutePath().normalize().toString() }
        .getOrDefault(expanded).lowercase(Locale.ROOT)
}

private fun profileName(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
    "domainauthenticated", "domain" -> "domain"
    "private" -> "private"
    "public" -> "public"
    else -> ""
}

private fun FirewallRule.matchesProfile(profile: String): Boolean =
    this.profile.equals("Any", ignoreCase = true) ||
        this.profile.split(',').any { profileName(it) == profile }

private fun FirewallRule.isProgramAgnostic(): Boolean =
    program.equals("Any", ignoreCase = true) || program == "*"

private fun FirewallRule.isProgramSpecific(executable: String): Boolean =
    !isProgramAgnostic() &&
        normalizedWindowsPath(program) == normalizedWindowsPath(executable)

private fun String.isAnyFilter(): Boolean = equals("Any", ignoreCase = true) || this == "*"

private fun String.isUnrestrictedUser(): Boolean = isEmpty() || isAnyFilter()

/** Only rules covering ordinary local-subnet traffic can prove that the remote is reachable. */
private fun FirewallRule.allowsLocalSubnet(): Boolean =
    remotePort.isAnyFilter() && localAddress.isAnyFilter() &&
        (remoteAddress.isAnyFilter() || remoteAddress.equals("LocalSubnet", ignoreCase = true) ||
            remoteAddress.equals("LocalSubnet4", ignoreCase = true)) &&
        service.isAnyFilter() && interfaceAlias.isAnyFilter() && interfaceType.isAnyFilter() &&
        authentication.equals("NotRequired", ignoreCase = true) &&
        encryption.equals("NotRequired", ignoreCase = true) &&
        overrideBlockRules.equals("False", ignoreCase = true) &&
        localUser.isUnrestrictedUser() && remoteUser.isUnrestrictedUser() && remoteMachine.isUnrestrictedUser()

private fun FirewallRule.protocolMatches(protocol: String): Boolean =
    this.protocol.equals("Any", ignoreCase = true) ||
        this.protocol == "256" || this.protocol == "0" ||
        this.protocol.equals(protocol, ignoreCase = true) ||
        this.protocol == (if (protocol == "TCP") "6" else "17")

private fun FirewallRule.localPortMatches(port: Int): Boolean {
    if (localPort.isAnyFilter()) return true
    return localPort.split(',').any { token ->
        val bounds = token.trim().split('-', limit = 2)
        when (bounds.size) {
            1 -> bounds[0].toIntOrNull() == port
            2 -> {
                val first = bounds[0].toIntOrNull()
                val last = bounds[1].toIntOrNull()
                first != null && last != null && port in first..last
            }
            else -> false
        }
    }
}

private fun FirewallRule.matchesPort(protocol: String, port: Int, explicitPortOnly: Boolean = false): Boolean {
    return protocolMatches(protocol) && (!explicitPortOnly || !localPort.isAnyFilter()) &&
        localPortMatches(port)
}

/** Missing block filters may cover these ports, so they cannot prove that access is open. */
private fun FirewallRule.mightBlockPort(protocol: String, port: Int): Boolean =
    (this.protocol.equals("Unknown", ignoreCase = true) || protocolMatches(protocol)) &&
        (localPort.equals("Unknown", ignoreCase = true) || localPortMatches(port))

/**
 * Windows resolves rules per profile and prioritizes matching inbound Block rules.
 * Incomplete rule filters fail closed (never Allowed). Invalid snapshots are
 * Unverified, because inspection failure is not proof of a firewall block.
 */
internal fun parseWindowsFirewallState(json: String, executable: String): RemoteFirewallState =
    runCatching {
        require(executable.isNotBlank())
        val root = Json.parseToJsonElement(json) as? JsonObject ?: error("Invalid firewall JSON")
        val ruleArray = root["rules"] as? JsonArray ?: error("Missing rules")
        val networkArray = root["networks"] as? JsonArray ?: error("Missing networks")
        val profileArray = root["profiles"] as? JsonArray ?: error("Missing profiles")
        val rules = ruleArray.map { entry ->
            val item = entry as? JsonObject ?: error("Invalid rule")
            val enabled = (item["enabled"] as? JsonPrimitive)?.booleanOrNull
                ?: error("Missing enabled")
            val owner = (item["owner"] as? JsonPrimitive)?.booleanOrNull
                ?: error("Missing owner")
            val packageName = item.requiredString("package")
            if (enabled && !owner && packageName.isEmpty()) FirewallRule(
                action = item.requiredString("action"),
                profile = item.requiredString("profile"),
                program = item.requiredString("program"),
                protocol = item.requiredString("protocol"),
                localPort = item.requiredString("localPort"),
                remotePort = item.requiredString("remotePort"),
                localAddress = item.requiredString("localAddress"),
                remoteAddress = item.requiredString("remoteAddress"),
                service = item.requiredString("service"),
                interfaceAlias = item.requiredString("interfaceAlias"),
                interfaceType = item.requiredString("interfaceType"),
                authentication = item.requiredString("authentication"),
                encryption = item.requiredString("encryption"),
                overrideBlockRules = item.requiredString("overrideBlockRules"),
                localUser = item.requiredString("localUser"),
                remoteUser = item.requiredString("remoteUser"),
                remoteMachine = item.requiredString("remoteMachine"),
            ) else null
        }.filterNotNull()
        val profiles = profileArray.map { entry ->
            val item = entry as? JsonObject ?: error("Invalid firewall profile")
            FirewallProfilePolicy(
                name = profileName(item.requiredString("name")),
                enabled = item.requiredBooleanText("enabled"),
                allowInboundRules = item.requiredBooleanText("allowInboundRules"),
            )
        }
        val activeProfiles = networkArray.map { entry ->
            val item = entry as? JsonObject ?: error("Invalid network")
            profileName(item.requiredString("category"))
        }.filter { it.isNotEmpty() }.distinct()
        if (activeProfiles.isEmpty()) return@runCatching RemoteFirewallState.Unverified

        fun allowedOn(profile: String, protocol: String, port: Int): Boolean {
            val policy = profiles.singleOrNull { it.name == profile } ?: return false
            if (!policy.enabled) return true
            if (!policy.allowInboundRules) return false
            val matching = rules.filter {
                it.matchesProfile(profile) &&
                    (it.isProgramSpecific(executable) || it.isProgramAgnostic() ||
                        it.program.equals("Unknown", ignoreCase = true))
            }
            val blocked = matching.any { rule ->
                rule.action.equals("Block", ignoreCase = true) &&
                    (rule.service.isAnyFilter() || rule.service.equals("Unknown", ignoreCase = true)) &&
                    rule.mightBlockPort(protocol, port)
            }
            if (blocked) return false
            return matching.any { rule ->
                rule.action.equals("Allow", ignoreCase = true) &&
                    (rule.isProgramSpecific(executable) || rule.isProgramAgnostic()) &&
                    rule.matchesPort(protocol, port, explicitPortOnly = rule.isProgramAgnostic()) &&
                    rule.allowsLocalSubnet()
            }
        }

        when {
            activeProfiles.any { allowedOn(it, "TCP", FIREWALL_SESSION_PORT) &&
                allowedOn(it, "UDP", FIREWALL_DISCOVERY_PORT) } -> RemoteFirewallState.Allowed
            activeProfiles.any { allowedOn(it, "TCP", FIREWALL_SESSION_PORT) } -> RemoteFirewallState.DiscoveryRestricted
            activeProfiles.all { it == "public" } &&
                (allowedOn("private", "TCP", FIREWALL_SESSION_PORT) ||
                    allowedOn("domain", "TCP", FIREWALL_SESSION_PORT)) -> RemoteFirewallState.PublicNetwork
            else -> RemoteFirewallState.Blocked
        }
    }.getOrDefault(RemoteFirewallState.Unverified)

internal fun checkedWindowsFirewallState(output: String?, executable: String): RemoteFirewallState =
    output?.let { parseWindowsFirewallState(it, executable) } ?: RemoteFirewallState.Unverified

/** A late check can never overwrite a newer state or a pending UAC request. */
internal object WindowsRemoteFirewall : RemoteFirewallAccess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutableState = MutableStateFlow(RemoteFirewallState.Checking)
    override val state: StateFlow<RemoteFirewallState> = mutableState.asStateFlow()
    private var generation = 0L

    override fun refresh() = scheduleRefresh()

    private fun scheduleRefresh(requestGeneration: Long? = null) {
        val ticket = synchronized(lock) {
            if (requestGeneration != null && generation != requestGeneration) return
            if (requestGeneration == null && mutableState.value == RemoteFirewallState.Requesting) return
            generation += 1
            mutableState.value = RemoteFirewallState.Checking
            generation
        }
        scope.launch {
            val result = runCatching {
                val executable = ProcessHandle.current().info().command().orElse(null)
                    ?: return@runCatching RemoteFirewallState.Unverified
                // Read in-process through the firewall's COM API; no PowerShell is started.
                checkedWindowsFirewallState(WindowsFirewallCom.snapshotJson(), executable)
            }.getOrDefault(RemoteFirewallState.Unverified)
            synchronized(lock) {
                if (generation == ticket) mutableState.value = result
            }
        }
    }

    override fun requestAccess() {
        val ticket = synchronized(lock) {
            if (mutableState.value == RemoteFirewallState.Requesting) return
            generation += 1
            mutableState.value = RemoteFirewallState.Requesting
            generation
        }
        scope.launch {
            val owner = runCatching { withContext(Dispatchers.Swing) { WindowsElevation.activeWindowHandle() } }.getOrNull()
            // Starlit restarts itself elevated for this one task, behind a single UAC prompt.
            val succeeded = runCatching {
                WindowsElevation.runSelf(listOf(ELEVATED_TASK_ARGUMENT, PHONE_REMOTE_FIREWALL_TASK), 180, owner)
            }.getOrDefault(false)
            if (succeeded) scheduleRefresh(requestGeneration = ticket)
            else synchronized(lock) {
                if (generation == ticket) mutableState.value = RemoteFirewallState.Failed
            }
        }
    }

    override fun openNetworkSettings() {
        scope.launch {
            runCatching {
                check(WindowsElevation.openNetworkSettings()) { "Windows refused to open network settings" }
            }.onFailure {
                Logger.withTag("WindowsRemoteFirewall").w(it) { "Could not open Windows network settings" }
            }
        }
    }
}
