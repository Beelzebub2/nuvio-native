package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.ComException
import com.nuvio.app.core.windows.ComObject
import com.nuvio.app.core.windows.WindowsCom
import com.nuvio.app.core.windows.checkHresult
import com.sun.jna.Memory
import com.sun.jna.ptr.IntByReference

/**
 * Windows Firewall through its COM API (HNetCfg.FwPolicy2), in Starlit's own process.
 * Vtable indexes are from netfw.h in the Windows SDK; a property setter is always
 * its getter's index + 1.
 */
internal object WindowsFirewallCom {
    private const val CLSID_NET_FW_POLICY2 = "E2B3C97F-6AE1-41AC-817A-F6F92166D7DD"
    private const val IID_NET_FW_POLICY2 = "98325047-C671-4174-8D81-DEFCD3F03186"
    private const val CLSID_NET_FW_RULE = "2C5BC43E-3369-4C33-AB0C-BE9469677AF4"
    private const val IID_NET_FW_RULE = "AF230D27-BABA-4E42-ACED-F524F22CFCE2"
    private const val IID_NET_FW_RULE3 = "B21563FF-D696-4222-AB46-4E89B73AB34A"
    private const val IID_ENUM_VARIANT = "00020404-0000-0000-C000-000000000046"
    private const val E_NOINTERFACE = 0x80004002.toInt()

    // INetFwPolicy2
    private const val POLICY_CURRENT_PROFILE_TYPES = 7
    private const val POLICY_FIREWALL_ENABLED = 8
    private const val POLICY_BLOCK_ALL_INBOUND = 12
    private const val POLICY_RULES = 18

    // INetFwRules
    private const val RULES_ADD = 8
    private const val RULES_REMOVE = 9
    private const val RULES_NEW_ENUM = 11

    // INetFwRule / INetFwRule3 getters
    private const val RULE_NAME = 7
    private const val RULE_DESCRIPTION = 9
    private const val RULE_APPLICATION = 11
    private const val RULE_SERVICE = 13
    private const val RULE_PROTOCOL = 15
    private const val RULE_LOCAL_PORTS = 17
    private const val RULE_REMOTE_PORTS = 19
    private const val RULE_LOCAL_ADDRESSES = 21
    private const val RULE_REMOTE_ADDRESSES = 23
    private const val RULE_DIRECTION = 27
    private const val RULE_INTERFACES = 29
    private const val RULE_INTERFACE_TYPES = 31
    private const val RULE_ENABLED = 33
    private const val RULE_GROUPING = 35
    private const val RULE_PROFILES = 37
    private const val RULE_ACTION = 41
    private const val RULE3_PACKAGE = 45
    private const val RULE3_OWNER = 47
    private const val RULE3_LOCAL_USERS = 49
    private const val RULE3_REMOTE_USERS = 51
    private const val RULE3_REMOTE_MACHINES = 53
    private const val RULE3_SECURE_FLAGS = 55

    // IEnumVARIANT
    private const val ENUM_NEXT = 3
    private const val MAX_RULES = 50_000

    /** INetFwRules.Add overwrites matching rule names, so reject foreign or ambiguous IDs. */
    internal fun requireOwnedRuleNames(ownership: Map<String, List<Boolean>>) {
        check(ownership.values.all { owners -> owners.size == 1 && owners[0] }) {
            "Refusing to replace an unrelated or duplicate Windows Firewall rule"
        }
    }

    /** A JVM launched by Gradle shares java.exe with unrelated applications. */
    internal fun requireDedicatedPublicEthernetLauncher(program: String) {
        val name = program.replace('\\', '/').substringAfterLast('/')
        require(name.equals("Starlit.exe", ignoreCase = true) && name != program) {
            "Public Ethernet firewall permission requires the installed Starlit desktop launcher"
        }
    }

    internal fun newlyCreatedPublicEthernetRuleNames(previouslyOwned: Set<String>): List<String> =
        listOf(FIREWALL_PUBLIC_ETHERNET_TCP_RULE, FIREWALL_PUBLIC_ETHERNET_UDP_RULE)
            .filterNot(previouslyOwned::contains)

    /** The remote ranges are private routes only; the exact local VPN address is also required. */
    internal const val PRIVATE_VPN_REMOTE_RANGES =
        "100.64.0.0/10,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"

    const val NET_FW_RULE_DIR_IN = 1
    const val NET_FW_ACTION_BLOCK = 0
    const val NET_FW_ACTION_ALLOW = 1
    const val NET_FW_IP_PROTOCOL_TCP = 6
    const val NET_FW_IP_PROTOCOL_UDP = 17
    const val NET_FW_IP_PROTOCOL_ANY = 256
    const val NET_FW_PROFILE2_DOMAIN = 0x1
    const val NET_FW_PROFILE2_PRIVATE = 0x2
    const val NET_FW_PROFILE2_PUBLIC = 0x4
    const val NET_FW_PROFILE2_ALL = 0x7fffffff

    /** Read-only snapshot of every enabled inbound rule plus profile and network state. */
    fun snapshotJson(): String = WindowsCom.withApartment {
        WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
            val activeProfiles = policy.getInt(POLICY_CURRENT_PROFILE_TYPES, "CurrentProfileTypes")
            val profiles = listOf(NET_FW_PROFILE2_DOMAIN, NET_FW_PROFILE2_PRIVATE, NET_FW_PROFILE2_PUBLIC).mapNotNull { type ->
                // An unreadable profile is left out, which the parser treats as blocked.
                runCatching {
                    WindowsFirewallProfileRecord(
                        type = type,
                        enabled = policy.getBoolean(POLICY_FIREWALL_ENABLED, "FirewallEnabled", type),
                        blockAllInbound = policy.getBoolean(POLICY_BLOCK_ALL_INBOUND, "BlockAllInboundTraffic", type),
                    )
                }.getOrNull()
            }
            val rules = mutableListOf<WindowsFirewallRuleRecord>()
            policy.getObject(POLICY_RULES, "Rules").use { collection ->
                forEachRule(collection) { rule, isRule3 ->
                    val inbound = runCatching { rule.getInt(RULE_DIRECTION, "Direction") }.getOrNull() == NET_FW_RULE_DIR_IN
                    val enabled = runCatching { rule.getBoolean(RULE_ENABLED, "Enabled") }.getOrDefault(false)
                    if (inbound && enabled) rules += record(rule, isRule3)
                }
            }
            windowsFirewallSnapshotJson(rules, activeProfiles, profiles)
        }
    }

    /**
     * Adds the two phone remote rules for [program]. Needs administrator rights, so it
     * only runs in the elevated helper the user approved. Only replaces our own earlier
     * inbound Allow rules; never removes a Block rule or another app's rule.
     */
    fun allowPhoneRemote(program: String, publicEthernet: Boolean = false) {
        require(program.isNotBlank())
        if (publicEthernet) requireDedicatedPublicEthernetLauncher(program)
        val tcpRule = if (publicEthernet) FIREWALL_PUBLIC_ETHERNET_TCP_RULE else FIREWALL_TCP_RULE
        val udpRule = if (publicEthernet) FIREWALL_PUBLIC_ETHERNET_UDP_RULE else FIREWALL_UDP_RULE
        WindowsCom.withApartment {
            WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
                policy.getObject(POLICY_RULES, "Rules").use { rules ->
                    val ownership = mutableMapOf<String, MutableList<Boolean>>()
                    forEachRule(rules) { rule, _ ->
                        val name = runCatching { rule.getString(RULE_NAME, "Name") }.getOrNull()
                        if (name == tcpRule || name == udpRule) {
                            val ours = runCatching {
                                rule.getInt(RULE_DIRECTION, "Direction") == NET_FW_RULE_DIR_IN &&
                                    rule.getInt(RULE_ACTION, "Action") == NET_FW_ACTION_ALLOW &&
                                    rule.getString(RULE_GROUPING, "Grouping") == FIREWALL_GROUP &&
                                    rule.getString(RULE_APPLICATION, "ApplicationName")
                                        ?.equals(program, ignoreCase = true) == true
                            }.getOrDefault(false)
                            ownership.getOrPut(name) { mutableListOf() } += ours
                        }
                    }
                    // Add also replaces by name: reject collisions BEFORE changing any rule.
                    requireOwnedRuleNames(ownership)
                    if (!publicEthernet) {
                        // Preserve the existing private/domain rule replacement flow.
                        for ((name, owners) in ownership) {
                            if (owners.isNotEmpty()) WindowsCom.withBstr(name) {
                                checkHresult("Rules.Remove", rules.call(RULES_REMOVE, it))
                            }
                        }
                    }
                    try {
                        // Public rules overwrite only previously verified, owned names.
                        // Keep old rules in place until their replacements are added.
                        addRule(rules, tcpRule, NET_FW_IP_PROTOCOL_TCP, FIREWALL_SESSION_PORT, program, publicEthernet)
                        addRule(rules, udpRule, NET_FW_IP_PROTOCOL_UDP, FIREWALL_DISCOVERY_PORT, program, publicEthernet)
                    } catch (error: Exception) {
                        if (publicEthernet) {
                            // Fail closed on a first-time partial installation:
                            // remove any newly created Public rules if either Add failed.
                            for (name in newlyCreatedPublicEthernetRuleNames(ownership.keys)) {
                                runCatching {
                                    WindowsCom.withBstr(name) { ruleName ->
                                        checkHresult("Rules.Remove", rules.call(RULES_REMOVE, ruleName))
                                    }
                                }.onFailure(error::addSuppressed)
                            }
                        }
                        throw error
                    }
                }
            }
        }
    }

    private fun vpnOwnership(rules: ComObject, program: String): Map<String, List<Boolean>> {
        val ownership = mutableMapOf<String, MutableList<Boolean>>()
        forEachRule(rules) { rule, _ ->
            val name = runCatching { rule.getString(RULE_NAME, "Name") }.getOrNull()
            if (name != FIREWALL_PRIVATE_VPN_TCP_RULE) return@forEachRule
            val ours = runCatching {
                rule.getInt(RULE_DIRECTION, "Direction") == NET_FW_RULE_DIR_IN &&
                    rule.getInt(RULE_ACTION, "Action") == NET_FW_ACTION_ALLOW &&
                    rule.getString(RULE_GROUPING, "Grouping") == FIREWALL_GROUP &&
                    rule.getString(RULE_APPLICATION, "ApplicationName")
                        ?.equals(program, ignoreCase = true) == true &&
                    rule.getInt(RULE_PROTOCOL, "Protocol") == NET_FW_IP_PROTOCOL_TCP &&
                    rule.getString(RULE_LOCAL_PORTS, "LocalPorts") == FIREWALL_SESSION_PORT.toString()
            }.getOrDefault(false)
            ownership.getOrPut(name) { mutableListOf() } += ours
        }
        return ownership
    }

    /** Runs only after a named UAC task and verifies a currently active VPN adapter. */
    fun allowPrivateVpnRemote(program: String, localVpnAddress: String) {
        requireDedicatedPublicEthernetLauncher(program)
        require(strictRemoteIpv4(localVpnAddress)?.let(::isPrivateRemoteVpnAddress) == true)
        require(WindowsRemoteVpnAccess.interfaces().any { it.address == localVpnAddress }) {
            "Refusing to create a firewall exception for a non-VPN interface"
        }
        WindowsCom.withApartment {
            WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
                policy.getObject(POLICY_RULES, "Rules").use { rules ->
                    requireOwnedRuleNames(vpnOwnership(rules, program))
                    withPrivateVpnRule(program, localVpnAddress) { rule ->
                        checkHresult("Rules.Add", rules.call(RULES_ADD, rule.pointer))
                    }
                }
            }
        }
    }

    /** Only removes an unambiguously owned rule after VPN access is disabled in-app. */
    fun removePrivateVpnRemote(program: String) {
        requireDedicatedPublicEthernetLauncher(program)
        WindowsCom.withApartment {
            WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
                policy.getObject(POLICY_RULES, "Rules").use { rules ->
                    val ownership = vpnOwnership(rules, program)
                    requireOwnedRuleNames(ownership)
                    if (ownership.containsKey(FIREWALL_PRIVATE_VPN_TCP_RULE)) {
                        WindowsCom.withBstr(FIREWALL_PRIVATE_VPN_TCP_RULE) {
                            checkHresult("Rules.Remove", rules.call(RULES_REMOVE, it))
                        }
                    }
                }
            }
        }
    }

    /** Read-only check for the rule's exact executable/port/local address and remote ranges. */
    fun privateVpnRuleConfigured(program: String, localVpnAddress: String): Boolean =
        runCatching {
            WindowsCom.withApartment {
                WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
                    policy.getObject(POLICY_RULES, "Rules").use { rules ->
                        var count = 0
                        var valid = false
                        forEachRule(rules) { rule, _ ->
                            if (runCatching { rule.getString(RULE_NAME, "Name") }.getOrNull() !=
                                FIREWALL_PRIVATE_VPN_TCP_RULE) return@forEachRule
                            count++
                            valid = runCatching {
                                rule.getBoolean(RULE_ENABLED, "Enabled") &&
                                    rule.getString(RULE_GROUPING, "Grouping") == FIREWALL_GROUP &&
                                    rule.getString(RULE_APPLICATION, "ApplicationName")
                                        ?.equals(program, ignoreCase = true) == true &&
                                    rule.getInt(RULE_DIRECTION, "Direction") == NET_FW_RULE_DIR_IN &&
                                    rule.getInt(RULE_ACTION, "Action") == NET_FW_ACTION_ALLOW &&
                                    rule.getInt(RULE_PROTOCOL, "Protocol") == NET_FW_IP_PROTOCOL_TCP &&
                                    rule.getString(RULE_LOCAL_PORTS, "LocalPorts") == FIREWALL_SESSION_PORT.toString() &&
                                    vpnFirewallLocalAddressMatches(
                                        rule.getString(RULE_LOCAL_ADDRESSES, "LocalAddresses"), localVpnAddress,
                                    ) &&
                                    vpnFirewallRemoteAddressesMatch(
                                        rule.getString(RULE_REMOTE_ADDRESSES, "RemoteAddresses"),
                                    )
                            }.getOrDefault(false)
                        }
                        count == 1 && valid
                    }
                }
            }
        }.getOrDefault(false)

    /** Testable COM rule builder; never shares the LAN/public-Ethernet exception. */
    internal fun <T> withPrivateVpnRule(
        program: String, localVpnAddress: String, block: (ComObject) -> T,
    ): T = WindowsCom.create(CLSID_NET_FW_RULE, IID_NET_FW_RULE).use { rule ->
        rule.putString(RULE_NAME + 1, "Name", FIREWALL_PRIVATE_VPN_TCP_RULE)
        rule.putString(RULE_DESCRIPTION + 1, "Description", "Allows encrypted phone control over an approved private VPN adapter.")
        rule.putString(RULE_APPLICATION + 1, "ApplicationName", program)
        rule.putInt(RULE_PROTOCOL + 1, "Protocol", NET_FW_IP_PROTOCOL_TCP)
        rule.putString(RULE_LOCAL_PORTS + 1, "LocalPorts", FIREWALL_SESSION_PORT.toString())
        rule.putString(RULE_LOCAL_ADDRESSES + 1, "LocalAddresses", localVpnAddress)
        rule.putString(RULE_REMOTE_ADDRESSES + 1, "RemoteAddresses", PRIVATE_VPN_REMOTE_RANGES)
        rule.putInt(RULE_DIRECTION + 1, "Direction", NET_FW_RULE_DIR_IN)
        rule.putString(RULE_GROUPING + 1, "Grouping", FIREWALL_GROUP)
        rule.putInt(RULE_PROFILES + 1, "Profiles", NET_FW_PROFILE2_ALL)
        rule.putInt(RULE_ACTION + 1, "Action", NET_FW_ACTION_ALLOW)
        rule.putBoolean(RULE_ENABLED + 1, "Enabled", true)
        block(rule)
    }

    /** INetFwRule canonicalizes an individual address into a /32 mask on readback. */
    internal fun vpnFirewallLocalAddressMatches(actual: String?, approved: String): Boolean =
        actual == approved || actual == "$approved/255.255.255.255" || actual == "$approved/32"

    /** COM sorts CIDRs and expands prefixes into dotted masks; reject broader rule scopes. */
    internal fun vpnFirewallRemoteAddressesMatch(actual: String?): Boolean {
        val ranges = actual?.split(',')?.map(String::trim)?.toSet() ?: return false
        val intended = PRIVATE_VPN_REMOTE_RANGES.split(',').toSet()
        val windowsCanonical = setOf(
            "10.0.0.0/255.0.0.0",
            "100.64.0.0/255.192.0.0",
            "172.16.0.0/255.240.0.0",
            "192.168.0.0/255.255.0.0",
        )
        return ranges == intended || ranges == windowsCanonical
    }

    /** Builds a rule exactly as [allowPhoneRemote] would, without adding it to the firewall. */
    internal fun <T> withPhoneRemoteRule(
        name: String,
        protocol: Int,
        port: Int,
        program: String,
        publicEthernet: Boolean = false,
        block: (ComObject) -> T,
    ): T =
        WindowsCom.create(CLSID_NET_FW_RULE, IID_NET_FW_RULE).use { rule ->
            rule.putString(RULE_NAME + 1, "Name", name)
            rule.putString(RULE_DESCRIPTION + 1, "Description", "Lets phones on your local network find and control Starlit.")
            rule.putString(RULE_APPLICATION + 1, "ApplicationName", program)
            // The protocol must be set before ports can be.
            rule.putInt(RULE_PROTOCOL + 1, "Protocol", protocol)
            rule.putString(RULE_LOCAL_PORTS + 1, "LocalPorts", port.toString())
            rule.putString(RULE_REMOTE_ADDRESSES + 1, "RemoteAddresses", "LocalSubnet")
            // A Public-profile exception must never expose remote control via
            // public Wi-Fi. The user explicitly approves wired LAN access only.
            if (publicEthernet) rule.putString(RULE_INTERFACE_TYPES + 1, "InterfaceTypes", "Lan")
            rule.putInt(RULE_DIRECTION + 1, "Direction", NET_FW_RULE_DIR_IN)
            rule.putString(RULE_GROUPING + 1, "Grouping", FIREWALL_GROUP)
            rule.putInt(RULE_PROFILES + 1, "Profiles", if (publicEthernet) NET_FW_PROFILE2_PUBLIC
                else NET_FW_PROFILE2_DOMAIN or NET_FW_PROFILE2_PRIVATE)
            rule.putInt(RULE_ACTION + 1, "Action", NET_FW_ACTION_ALLOW)
            rule.putBoolean(RULE_ENABLED + 1, "Enabled", true)
            block(rule)
        }

    /** Reads a rule back with the same mapping the snapshot uses (for tests). */
    internal fun recordOf(rule: ComObject): WindowsFirewallRuleRecord =
        rule.queryInterface(IID_NET_FW_RULE3)?.use { record(it, isRule3 = true) } ?: record(rule, isRule3 = false)

    private fun addRule(rules: ComObject, name: String, protocol: Int, port: Int, program: String, publicEthernet: Boolean) {
        withPhoneRemoteRule(name, protocol, port, program, publicEthernet) { rule ->
            checkHresult("Rules.Add", rules.call(RULES_ADD, rule.pointer))
        }
    }

    private inline fun forEachRule(rules: ComObject, action: (rule: ComObject, isRule3: Boolean) -> Unit) {
        rules.getObject(RULES_NEW_ENUM, "_NewEnum").use { unknown ->
            val enumerator = unknown.queryInterface(IID_ENUM_VARIANT) ?: throw ComException("IEnumVARIANT", E_NOINTERFACE)
            enumerator.use {
                val variant = Memory(WindowsCom.variantSize)
                val fetched = IntByReference()
                for (index in 0 until MAX_RULES) {
                    variant.clear()
                    fetched.value = 0
                    // S_FALSE (1) or zero fetched means the end of the collection.
                    if (enumerator.call(ENUM_NEXT, 1, variant, fetched) != 0 || fetched.value != 1) break
                    try {
                        val type = variant.getShort(0).toInt()
                        val item = if (type == WindowsCom.VT_DISPATCH || type == WindowsCom.VT_UNKNOWN) {
                            variant.getPointer(WindowsCom.variantValueOffset)
                        } else null
                        if (item != null) {
                            val rule3 = ComObject.queryInterface(item, IID_NET_FW_RULE3)
                            val rule = rule3 ?: ComObject.queryInterface(item, IID_NET_FW_RULE)
                            rule?.use { action(it, rule3 != null) }
                        }
                    } finally {
                        WindowsCom.clearVariant(variant)
                    }
                }
            }
        }
    }

    private fun record(rule: ComObject, isRule3: Boolean): WindowsFirewallRuleRecord {
        fun text(index: Int, name: String) = runCatching { rule.getString(index, name) }
        fun number(index: Int, name: String) = runCatching { rule.getInt(index, name) }
        // Rules from before INetFwRule3 have no owner, package, user or IPsec filters.
        fun rule3Text(index: Int, name: String) = if (isRule3) text(index, name) else Result.success(null)
        return WindowsFirewallRuleRecord(
            action = number(RULE_ACTION, "Action"),
            profiles = number(RULE_PROFILES, "Profiles"),
            applicationName = text(RULE_APPLICATION, "ApplicationName"),
            protocol = number(RULE_PROTOCOL, "Protocol"),
            localPorts = text(RULE_LOCAL_PORTS, "LocalPorts"),
            remotePorts = text(RULE_REMOTE_PORTS, "RemotePorts"),
            localAddresses = text(RULE_LOCAL_ADDRESSES, "LocalAddresses"),
            remoteAddresses = text(RULE_REMOTE_ADDRESSES, "RemoteAddresses"),
            serviceName = text(RULE_SERVICE, "ServiceName"),
            interfacesRestricted = runCatching { interfacesRestricted(rule) },
            interfaceTypes = text(RULE_INTERFACE_TYPES, "InterfaceTypes"),
            secureFlags = if (isRule3) number(RULE3_SECURE_FLAGS, "SecureFlags") else Result.success(0),
            owner = rule3Text(RULE3_OWNER, "LocalUserOwner"),
            packageId = rule3Text(RULE3_PACKAGE, "LocalAppPackageId"),
            localUsers = rule3Text(RULE3_LOCAL_USERS, "LocalUserAuthorizedList"),
            remoteUsers = rule3Text(RULE3_REMOTE_USERS, "RemoteUserAuthorizedList"),
            remoteMachines = rule3Text(RULE3_REMOTE_MACHINES, "RemoteMachineAuthorizedList"),
        )
    }

    /** Interfaces is a VARIANT: empty means every interface. */
    private fun interfacesRestricted(rule: ComObject): Boolean {
        val variant = Memory(WindowsCom.variantSize).apply { clear() }
        try {
            checkHresult("Interfaces", rule.call(RULE_INTERFACES, variant))
            val type = variant.getShort(0).toInt()
            return type != WindowsCom.VT_EMPTY && type != WindowsCom.VT_NULL
        } finally {
            WindowsCom.clearVariant(variant)
        }
    }
}
