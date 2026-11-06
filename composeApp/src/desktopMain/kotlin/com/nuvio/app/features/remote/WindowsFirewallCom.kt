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
    fun allowPhoneRemote(program: String) {
        require(program.isNotBlank())
        WindowsCom.withApartment {
            WindowsCom.create(CLSID_NET_FW_POLICY2, IID_NET_FW_POLICY2).use { policy ->
                policy.getObject(POLICY_RULES, "Rules").use { rules ->
                    val ownership = mutableMapOf<String, MutableList<Boolean>>()
                    forEachRule(rules) { rule, _ ->
                        val name = runCatching { rule.getString(RULE_NAME, "Name") }.getOrNull()
                        if (name == FIREWALL_TCP_RULE || name == FIREWALL_UDP_RULE) {
                            val ours = runCatching {
                                rule.getInt(RULE_DIRECTION, "Direction") == NET_FW_RULE_DIR_IN &&
                                    rule.getInt(RULE_ACTION, "Action") == NET_FW_ACTION_ALLOW &&
                                    rule.getString(RULE_GROUPING, "Grouping") == FIREWALL_GROUP
                            }.getOrDefault(false)
                            ownership.getOrPut(name) { mutableListOf() } += ours
                        }
                    }
                    // Remove deletes by name, so only do it when every rule with that name is ours.
                    for ((name, owners) in ownership) {
                        if (owners.all { it }) repeat(owners.size) {
                            WindowsCom.withBstr(name) { rules.call(RULES_REMOVE, it) }
                        }
                    }
                    addRule(rules, FIREWALL_TCP_RULE, NET_FW_IP_PROTOCOL_TCP, FIREWALL_SESSION_PORT, program)
                    addRule(rules, FIREWALL_UDP_RULE, NET_FW_IP_PROTOCOL_UDP, FIREWALL_DISCOVERY_PORT, program)
                }
            }
        }
    }

    /** Builds a rule exactly as [allowPhoneRemote] would, without adding it to the firewall. */
    internal fun <T> withPhoneRemoteRule(name: String, protocol: Int, port: Int, program: String, block: (ComObject) -> T): T =
        WindowsCom.create(CLSID_NET_FW_RULE, IID_NET_FW_RULE).use { rule ->
            rule.putString(RULE_NAME + 1, "Name", name)
            rule.putString(RULE_DESCRIPTION + 1, "Description", "Lets phones on your local network find and control Starlit.")
            rule.putString(RULE_APPLICATION + 1, "ApplicationName", program)
            // The protocol must be set before ports can be.
            rule.putInt(RULE_PROTOCOL + 1, "Protocol", protocol)
            rule.putString(RULE_LOCAL_PORTS + 1, "LocalPorts", port.toString())
            rule.putString(RULE_REMOTE_ADDRESSES + 1, "RemoteAddresses", "LocalSubnet")
            rule.putInt(RULE_DIRECTION + 1, "Direction", NET_FW_RULE_DIR_IN)
            rule.putString(RULE_GROUPING + 1, "Grouping", FIREWALL_GROUP)
            rule.putInt(RULE_PROFILES + 1, "Profiles", NET_FW_PROFILE2_DOMAIN or NET_FW_PROFILE2_PRIVATE)
            rule.putInt(RULE_ACTION + 1, "Action", NET_FW_ACTION_ALLOW)
            rule.putBoolean(RULE_ENABLED + 1, "Enabled", true)
            block(rule)
        }

    /** Reads a rule back with the same mapping the snapshot uses (for tests). */
    internal fun recordOf(rule: ComObject): WindowsFirewallRuleRecord =
        rule.queryInterface(IID_NET_FW_RULE3)?.use { record(it, isRule3 = true) } ?: record(rule, isRule3 = false)

    private fun addRule(rules: ComObject, name: String, protocol: Int, port: Int, program: String) {
        withPhoneRemoteRule(name, protocol, port, program) { rule ->
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
