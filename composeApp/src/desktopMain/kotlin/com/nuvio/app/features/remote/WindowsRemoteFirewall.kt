package com.nuvio.app.features.remote

import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val FIREWALL_GROUP = "Starlit phone remote"
private const val FIREWALL_DISCOVERY_PORT = 47788
private const val FIREWALL_SESSION_PORT = 47789

/** All firewall output is structured, including empty rule/profile arrays. */
internal fun windowsFirewallInspectionScript(executable: String): String = """
    ¤ErrorActionPreference = 'Stop'
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(¤false)
    ¤targetProgram = __PROGRAM__
    ¤inbound = @(Get-NetFirewallRule -PolicyStore ActiveStore -Direction Inbound -Enabled True)
    ¤apps = @{}
    Get-NetFirewallApplicationFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤apps[[string]¤_.InstanceID] = ¤_
    }
    ¤ports = @{}
    # Bulk port queries can intermittently omit a filter. Retry once, then use
    # a bounded associated-rule lookup. Unresolved ports stay Unknown.
    for (¤attempt = 0; ¤attempt -lt 2; ¤attempt++) {
        try {
            Get-NetFirewallPortFilter -PolicyStore ActiveStore -ErrorAction Stop | ForEach-Object {
                ¤ports[[string]¤_.InstanceID] = ¤_
            }
        } catch { }
        if (@(¤inbound | Where-Object { -not ¤ports.ContainsKey([string]¤_.Name) }).Count -eq 0) { break }
    }
    ¤addresses = @{}
    Get-NetFirewallAddressFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤addresses[[string]¤_.InstanceID] = ¤_
    }
    ¤services = @{}
    Get-NetFirewallServiceFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤services[[string]¤_.InstanceID] = ¤_
    }
    ¤interfaces = @{}
    Get-NetFirewallInterfaceFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤interfaces[[string]¤_.InstanceID] = ¤_
    }
    ¤interfaceTypes = @{}
    Get-NetFirewallInterfaceTypeFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤interfaceTypes[[string]¤_.InstanceID] = ¤_
    }
    ¤securityFilters = @{}
    Get-NetFirewallSecurityFilter -PolicyStore ActiveStore -ErrorAction SilentlyContinue | ForEach-Object {
        ¤securityFilters[[string]¤_.InstanceID] = ¤_
    }
    # Budget is spent on our own rules first, followed by any other rules that
    # could affect this executable, before inaccessible packaged-app rules.
    ¤priorityRules = @(¤inbound | Sort-Object -Property {
        ¤app = ¤apps[[string]¤_.Name]
        ¤candidate = if (¤null -ne ¤app) { [string]¤app.Program } else { '' }
        ¤package = if (¤null -ne ¤app) { [string]¤app.Package } else { '' }
        if ([string]¤_.Group -eq '__GROUP__') { 0 }
        elseif (¤candidate -eq ¤targetProgram) { 1 }
        elseif ((¤candidate -eq 'Any' -or ¤candidate -eq '*') -and
            [string]::IsNullOrWhiteSpace([string]¤_.Owner) -and
            (¤package -eq '' -or ¤package -eq '*' -or ¤package -eq 'Any')) {
            if (¤_.Action -eq 'Block') { 2 } else { 3 }
        } else { 4 }
    })
    ¤portFallbackBudget = 24
    foreach (¤rule in ¤priorityRules) {
        if (¤ports.ContainsKey([string]¤rule.Name) -or ¤portFallbackBudget -le 0) { continue }
        ¤portFallbackBudget--
        for (¤attempt = 0; ¤attempt -lt 2; ¤attempt++) {
            try {
                ¤found = @(¤rule | Get-NetFirewallPortFilter -ErrorAction Stop)
                if (¤found.Count -eq 1) {
                    ¤ports[[string]¤rule.Name] = ¤found[0]
                    break
                }
            } catch { }
        }
    }
    ¤rules = @(
        foreach (¤rule in ¤inbound) {
            ¤app = ¤apps[[string]¤rule.Name]
            ¤port = ¤ports[[string]¤rule.Name]
            ¤address = ¤addresses[[string]¤rule.Name]
            ¤service = ¤services[[string]¤rule.Name]
            ¤interface = ¤interfaces[[string]¤rule.Name]
            ¤interfaceType = ¤interfaceTypes[[string]¤rule.Name]
            ¤security = ¤securityFilters[[string]¤rule.Name]
            ¤program = if (¤null -ne ¤app -and -not [string]::IsNullOrWhiteSpace([string]¤app.Program)) {
                [string]¤app.Program
            } else { 'Unknown' }
            ¤protocol = if (¤null -ne ¤port -and -not [string]::IsNullOrWhiteSpace([string]¤port.Protocol)) {
                [string]¤port.Protocol
            } else { 'Unknown' }
            ¤localPort = if (¤null -ne ¤port -and -not [string]::IsNullOrWhiteSpace((@(¤port.LocalPort) -join ','))) {
                @(¤port.LocalPort) -join ','
            } else { 'Unknown' }
            ¤package = if (¤null -ne ¤app) { [string]¤app.Package } else { '' }
            if ([string]::IsNullOrWhiteSpace(¤package) -or ¤package -eq '*' -or ¤package -eq 'Any') { ¤package = '' }
            [PSCustomObject]@{
                enabled = ¤true
                action = [string]¤rule.Action
                profile = [string]¤rule.Profile
                program = ¤program
                protocol = ¤protocol
                localPort = ¤localPort
                owner = -not [string]::IsNullOrWhiteSpace([string]¤rule.Owner)
                package = ¤package
                remotePort = if (¤null -ne ¤port) { @(¤port.RemotePort) -join ',' } else { 'Unknown' }
                localAddress = if (¤null -ne ¤address) { @(¤address.LocalAddress) -join ',' } else { 'Unknown' }
                remoteAddress = if (¤null -ne ¤address) { @(¤address.RemoteAddress) -join ',' } else { 'Unknown' }
                service = if (¤null -ne ¤service) { [string]¤service.Service } else { 'Unknown' }
                interfaceAlias = if (¤null -ne ¤interface) { @(¤interface.InterfaceAlias) -join ',' } else { 'Unknown' }
                interfaceType = if (¤null -ne ¤interfaceType) { @(¤interfaceType.InterfaceType) -join ',' } else { 'Unknown' }
                authentication = if (¤null -ne ¤security) { [string]¤security.Authentication } else { 'Unknown' }
                encryption = if (¤null -ne ¤security) { [string]¤security.Encryption } else { 'Unknown' }
                overrideBlockRules = if (¤null -ne ¤security) { [string]¤security.OverrideBlockRules } else { 'Unknown' }
                localUser = if (¤null -ne ¤security) { @(¤security.LocalUser) -join ',' } else { 'Unknown' }
                remoteUser = if (¤null -ne ¤security) { @(¤security.RemoteUser) -join ',' } else { 'Unknown' }
                remoteMachine = if (¤null -ne ¤security) { @(¤security.RemoteMachine) -join ',' } else { 'Unknown' }
            }
        }
    )
    ¤networks = @(
        Get-NetConnectionProfile | Where-Object {
            [string]¤_.IPv4Connectivity -ne 'Disconnected'
        } | ForEach-Object {
            [PSCustomObject]@{ category = [string]¤_.NetworkCategory }
        }
    )
    ¤profiles = @(
        Get-NetFirewallProfile -PolicyStore ActiveStore | ForEach-Object {
            [PSCustomObject]@{
                name = [string]¤_.Name
                enabled = [string]¤_.Enabled
                allowInboundRules = [string]¤_.AllowInboundRules
            }
        }
    )
    [PSCustomObject]@{ rules = ¤rules; networks = ¤networks; profiles = ¤profiles } |
        ConvertTo-Json -Depth 5 -Compress
""".trimIndent()
    .replace('¤', '$')
    .replace("__GROUP__", FIREWALL_GROUP)
    .replace("__PROGRAM__", powerShellString(executable))

internal fun encodeWindowsPowerShell(script: String): String =
    Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))

private fun powerShellString(value: String): String = "'" + value.replace("'", "''") + "'"

/** This script is only run after the user's explicit request and a single UAC prompt. */
internal fun windowsFirewallElevatedScript(executable: String): String {
    require(executable.isNotBlank())
    return """
        ¤ErrorActionPreference = 'Stop'
        ¤program = __PROGRAM__
        ¤group = '__GROUP__'
        # Only replace our prior inbound Allow rules. Never alter a Block rule,
        # including an administrator's policy targeting this executable.
        Get-NetFirewallRule -PolicyStore PersistentStore -Group ¤group -ErrorAction SilentlyContinue |
            Where-Object {
                ¤_.Direction -eq 'Inbound' -and ¤_.Action -eq 'Allow' -and
                (¤_.DisplayName -eq 'Starlit phone remote TCP' -or ¤_.DisplayName -eq 'Starlit phone remote UDP')
            } | Remove-NetFirewallRule -ErrorAction Stop
        New-NetFirewallRule -DisplayName 'Starlit phone remote TCP' -Group ¤group -Direction Inbound -Action Allow -Enabled True -Program ¤program -Protocol TCP -LocalPort 47789 -Profile Domain,Private -RemoteAddress LocalSubnet -ErrorAction Stop | Out-Null
        New-NetFirewallRule -DisplayName 'Starlit phone remote UDP' -Group ¤group -Direction Inbound -Action Allow -Enabled True -Program ¤program -Protocol UDP -LocalPort 47788 -Profile Domain,Private -RemoteAddress LocalSubnet -ErrorAction Stop | Out-Null
    """.trimIndent()
        .replace("__GROUP__", FIREWALL_GROUP)
        .replace('¤', '$')
        .replace("__PROGRAM__", powerShellString(executable))
}

/** No app path enters the command line; only ASCII base64 is passed to PowerShell. */
internal fun windowsFirewallElevationLauncherScript(executable: String): String {
    val encoded = encodeWindowsPowerShell(windowsFirewallElevatedScript(executable))
    return """
        ¤ErrorActionPreference = 'Stop'
        try {
            ¤child = Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ErrorAction Stop -ArgumentList @('-NoProfile', '-NonInteractive', '-EncodedCommand', '__ENCODED__')
            if (¤null -eq ¤child) { exit 1 }
            exit [int]¤child.ExitCode
        } catch {
            exit 1
        }
    """.trimIndent().replace("__ENCODED__", encoded).replace('¤', '$')
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
 * Incomplete snapshots fail closed so the user is still offered the fix.
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
        if (activeProfiles.isEmpty()) return@runCatching RemoteFirewallState.Blocked

        fun allowedOn(profile: String): Boolean {
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
                    (rule.mightBlockPort("TCP", FIREWALL_SESSION_PORT) ||
                        rule.mightBlockPort("UDP", FIREWALL_DISCOVERY_PORT))
            }
            if (blocked) return false
            fun allowed(protocol: String, port: Int): Boolean = matching.any { rule ->
                rule.action.equals("Allow", ignoreCase = true) &&
                    (rule.isProgramSpecific(executable) || rule.isProgramAgnostic()) &&
                    rule.matchesPort(protocol, port, explicitPortOnly = rule.isProgramAgnostic()) &&
                    rule.allowsLocalSubnet()
            }
            return allowed("TCP", FIREWALL_SESSION_PORT) && allowed("UDP", FIREWALL_DISCOVERY_PORT)
        }

        when {
            activeProfiles.any(::allowedOn) -> RemoteFirewallState.Allowed
            activeProfiles.all { it == "public" } &&
                (allowedOn("private") || allowedOn("domain")) -> RemoteFirewallState.PublicNetwork
            else -> RemoteFirewallState.Blocked
        }
    }.getOrDefault(RemoteFirewallState.Blocked)

/** A late subprocess can never overwrite a newer state or a pending UAC request. */
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
                    ?: return@runCatching RemoteFirewallState.Blocked
                val output = runWindowsPowerShell(windowsFirewallInspectionScript(executable), 40)
                    ?: return@runCatching RemoteFirewallState.Blocked
                parseWindowsFirewallState(output, executable)
            }.getOrDefault(RemoteFirewallState.Blocked)
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
            val succeeded = runCatching {
                val executable = ProcessHandle.current().info().command().orElse(null)
                    ?: return@runCatching false
                runWindowsPowerShell(windowsFirewallElevationLauncherScript(executable), 180) != null
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
                Desktop.getDesktop().browse(URI("ms-settings:network-status"))
            }.onFailure {
                runCatching {
                    ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:network-status")
                        .start().waitFor(15, TimeUnit.SECONDS)
                }
            }
        }
    }
}

/**
 * Drain concurrently to avoid filling the child's stdout pipe. Kill timed-out
 * checks and reject truncated output without blocking the UI or any caller.
 */
private fun runWindowsPowerShell(script: String, timeoutSeconds: Long): String? {
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-EncodedCommand", encodeWindowsPowerShell(script),
    ).redirectErrorStream(true).start()
    val output = ByteArrayOutputStream()
    val overflow = AtomicBoolean(false)
    val readFailed = AtomicBoolean(false)
    val reader = Thread({
        try {
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count <= 1_500_000) output.write(buffer, 0, count)
                    else overflow.set(true)
                }
            }
        } catch (_: Exception) {
            readFailed.set(true)
        }
    }, "starlit-firewall-powershell-output").apply {
        isDaemon = true
        start()
    }
    try {
        process.outputStream.close()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) return null
        reader.join(1000)
        if (reader.isAlive || overflow.get() || readFailed.get() || process.exitValue() != 0)
            return null
        return output.toString(StandardCharsets.UTF_8.name())
    } finally {
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
    }
}
