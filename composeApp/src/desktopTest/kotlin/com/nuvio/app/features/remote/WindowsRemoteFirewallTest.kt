package com.nuvio.app.features.remote

import com.nuvio.app.core.windows.WindowsCom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

class WindowsRemoteFirewallTest {
    private val executable = """C:\Program Files\Starlit\Starlit.exe"""

    @Test
    fun existingFirewallRuleNamesMustBeUnambiguouslyOwnedBeforeReplacement() {
        WindowsFirewallCom.requireOwnedRuleNames(emptyMap())
        WindowsFirewallCom.requireOwnedRuleNames(mapOf(FIREWALL_TCP_RULE to listOf(true)))
        for (owners in listOf(listOf(false), listOf(true, false), listOf(true, true))) {
            assertFailsWith<IllegalStateException> {
                WindowsFirewallCom.requireOwnedRuleNames(mapOf(FIREWALL_TCP_RULE to owners))
            }
        }
    }

    private fun rule(
        action: String = "Allow",
        profile: String = "Private",
        program: String = "Any",
        protocol: String = "TCP",
        localPort: String = "47789",
        enabled: Boolean = true,
        owner: Boolean = false,
        packageName: String = "",
        remotePort: String = "Any",
        localAddress: String = "Any",
        remoteAddress: String = "Any",
        service: String = "Any",
        interfaceAlias: String = "Any",
        interfaceType: String = "Any",
        authentication: String = "NotRequired",
        encryption: String = "NotRequired",
        overrideBlockRules: String = "False",
        localUser: String = "",
        remoteUser: String = "",
        remoteMachine: String = "",
    ): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("action", action)
        put("profile", profile)
        put("program", program)
        put("protocol", protocol)
        put("localPort", localPort)
        put("owner", owner)
        put("package", packageName)
        put("remotePort", remotePort)
        put("localAddress", localAddress)
        put("remoteAddress", remoteAddress)
        put("service", service)
        put("interfaceAlias", interfaceAlias)
        put("interfaceType", interfaceType)
        put("authentication", authentication)
        put("encryption", encryption)
        put("overrideBlockRules", overrideBlockRules)
        put("localUser", localUser)
        put("remoteUser", remoteUser)
        put("remoteMachine", remoteMachine)
    }

    private fun snapshot(
        vararg rules: JsonObject,
        networks: List<String> = listOf("Private"),
        firewallEnabled: Boolean = true,
        allowInboundRules: Boolean = true,
    ) =
        buildJsonObject {
            put("rules", JsonArray(rules.toList()))
            put("networks", JsonArray(networks.map { category ->
                buildJsonObject { put("category", category) }
            }))
            put("profiles", JsonArray(listOf("Domain", "Private", "Public").map { profile ->
                buildJsonObject {
                    put("name", profile)
                    put("enabled", firewallEnabled.toString())
                    put("allowInboundRules", allowInboundRules.toString())
                }
            }))
        }.toString()

    @Test
    fun noRulesAreBlocked() {
        assertEquals(
            RemoteFirewallState.Blocked,
            parseWindowsFirewallState(snapshot(), executable),
        )
    }

    @Test
    fun matchingPortScopedRulesAllowTcpAndUdp() {
        val json = snapshot(
            rule(protocol = "TCP", localPort = "47789", remoteAddress = "LocalSubnet"),
            rule(protocol = "UDP", localPort = "47788", remoteAddress = "LocalSubnet"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun normalizedProgramScopedRuleAllowsBothProtocols() {
        val json = snapshot(
            rule(program = """c:/PROGRAM FILES/Starlit/./Starlit.exe""", protocol = "Any", localPort = "Any"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun appContainerAndBroadAnyProgramRulesCannotGrantPublicAccess() {
        val json = snapshot(
            rule(profile = "Public", program = "Any", protocol = "Any", localPort = "Any", owner = true),
            rule(profile = "Any", program = "Any", protocol = "Any", localPort = "Any"),
            networks = listOf("Public"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun packageScopedRulesCannotGrantOrOverrideStarlitAccess() {
        val packagesOnly = snapshot(
            rule(program = "Any", protocol = "TCP", localPort = "47789", packageName = "Microsoft.Store.App"),
            rule(program = "Any", protocol = "UDP", localPort = "47788", packageName = "Microsoft.Store.App"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(packagesOnly, executable))

        val withPackageBlock = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", protocol = "TCP", packageName = "Microsoft.Store.App"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(withPackageBlock, executable))
    }

    @Test
    fun unknownPortBlockForOtherExecutableDoesNotOverrideExplicitRules() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", program = """C:\OtherApp.exe""", protocol = "Unknown", localPort = "Unknown"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun unknownPortBlockForStarlitOverridesExplicitRules() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", program = executable, protocol = "Unknown", localPort = "Unknown"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun anyProgramCanAllowBothExplicitRemotePortsOnPrivateNetwork() {
        val json = snapshot(
            rule(program = "Any", protocol = "TCP", localPort = "47780-47789"),
            rule(program = "Any", protocol = "UDP", localPort = "47788"),
            networks = listOf("Private"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun appProgramRulesRequirePrivateProfileWhenOnlyPublicIsActive() {
        val json = snapshot(
            rule(program = executable, profile = "Domain,Private", protocol = "Any", localPort = "Any"),
            networks = listOf("Public"),
        )
        assertEquals(RemoteFirewallState.PublicNetwork, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun unknownPortAllowCannotGrantAccessEvenForAppExecutable() {
        val json = snapshot(
            rule(program = executable, protocol = "Any", localPort = "Unknown"),
            networks = listOf("Private"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun enabledAppBlockOverridesExplicitAllow() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", program = executable, protocol = "Any", localPort = "Any"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun broadProgramAgnosticBlockOverridesBothPortAllows() {
        val json = snapshot(
            rule(program = executable, protocol = "TCP", localPort = "47789"),
            rule(program = executable, protocol = "UDP", localPort = "47788"),
            rule(action = "Block", program = "Any", protocol = "Any", localPort = "Any", profile = "Any"),
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun appBlockOnUnrelatedPortDoesNotOverrideRemoteAllows() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", program = executable, protocol = "TCP", localPort = "443"),
            rule(action = "Block", program = executable, protocol = "UDP", localPort = "9999"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun missingOrRestrictedRuleFiltersCannotClaimFirewallAccess() {
        val restricted = listOf(
            "different local address" to rule(localAddress = "192.0.2.10"),
            "different remote address" to rule(remoteAddress = "192.0.2.20"),
            "different remote port" to rule(remotePort = "53"),
            "specific Windows service" to rule(service = "WinRM"),
            "specific network interface" to rule(interfaceAlias = "Ethernet 2"),
            "specific interface type" to rule(interfaceType = "Wireless"),
            "IPsec authentication" to rule(authentication = "Required"),
            "IPsec encryption" to rule(encryption = "Required"),
            "IPsec override" to rule(overrideBlockRules = "True"),
            "restricted local user" to rule(localUser = "D:(A;;CC;;;S-1-5-18)"),
            "restricted remote user" to rule(remoteUser = "D:(A;;CC;;;S-1-5-18)"),
            "restricted remote machine" to rule(remoteMachine = "D:(A;;CC;;;S-1-5-18)"),
            "missing address filter" to rule(remoteAddress = "Unknown"),
            "missing service filter" to rule(service = "Unknown"),
            "missing interface filter" to rule(interfaceAlias = "Unknown"),
            "missing security filter" to rule(authentication = "Unknown"),
        )
        restricted.forEach { (reason, tcpRule) ->
            val json = snapshot(tcpRule, rule(protocol = "UDP", localPort = "47788"))
            assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable), reason)
        }
    }

    @Test
    fun nonmatchingServiceBlockDoesNotOverrideRemoteAllows() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            rule(action = "Block", protocol = "Any", localPort = "Any", service = "WinRM"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun profileBlockAllIncomingOverridesExplicitAllowRules() {
        val json = snapshot(
            rule(protocol = "TCP"),
            rule(protocol = "UDP", localPort = "47788"),
            allowInboundRules = false,
        )
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun disabledFirewallProfileDoesNotRequireInboundAllowRules() {
        assertEquals(
            RemoteFirewallState.Allowed,
            parseWindowsFirewallState(snapshot(firewallEnabled = false), executable),
        )
    }

    @Test
    fun portBlockOverridesAllowButDisabledAndUnrelatedBlocksDoNot() {
        val allowed = arrayOf(rule(protocol = "TCP"), rule(protocol = "UDP", localPort = "47788"))
        assertEquals(
            RemoteFirewallState.Blocked,
            parseWindowsFirewallState(snapshot(*allowed, rule(action = "Block", protocol = "TCP")), executable),
        )
        assertEquals(
            RemoteFirewallState.Allowed,
            parseWindowsFirewallState(snapshot(
                *allowed,
                rule(action = "Block", protocol = "TCP", enabled = false),
                rule(action = "Block", program = """C:\OtherApp.exe""", protocol = "Any", localPort = "Any"),
            ), executable),
        )
    }

    @Test
    fun privateRulesOnOnlyPublicNetworksNeedProfileChange() {
        val json = snapshot(
            rule(profile = "Domain, Private", protocol = "TCP"),
            rule(profile = "Domain, Private", protocol = "UDP", localPort = "47788"),
            networks = listOf("Public"),
        )
        assertEquals(RemoteFirewallState.PublicNetwork, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun domainAuthenticatedNetworkUsesDomainRules() {
        val json = snapshot(
            rule(profile = "Domain", protocol = "TCP"),
            rule(profile = "Domain", protocol = "UDP", localPort = "47788"),
            networks = listOf("DomainAuthenticated"),
        )
        assertEquals(RemoteFirewallState.Allowed, parseWindowsFirewallState(json, executable))
    }

    @Test
    fun malformedOrMissingDataFailsClosed() {
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState("{broken", executable))
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState("""{"rules":[] }""", executable))
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(
            """{"rules":[],"networks":[{"category":"Private"}]}""", executable,
        ))
        assertEquals(RemoteFirewallState.Blocked, parseWindowsFirewallState(
            snapshot(rule(localPort = "47789"), networks = emptyList()), executable,
        ))
    }

    /** A rule as Windows Firewall's COM API reports it, defaulting to an unrestricted inbound Allow. */
    private fun comRule(
        action: Result<Int> = Result.success(WindowsFirewallCom.NET_FW_ACTION_ALLOW),
        profiles: Result<Int> = Result.success(WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE),
        applicationName: Result<String?> = Result.success(null),
        protocol: Result<Int> = Result.success(WindowsFirewallCom.NET_FW_IP_PROTOCOL_TCP),
        localPorts: Result<String?> = Result.success("47789"),
        remotePorts: Result<String?> = Result.success("*"),
        localAddresses: Result<String?> = Result.success("*"),
        remoteAddresses: Result<String?> = Result.success("*"),
        serviceName: Result<String?> = Result.success(null),
        interfacesRestricted: Result<Boolean> = Result.success(false),
        interfaceTypes: Result<String?> = Result.success("All"),
        secureFlags: Result<Int> = Result.success(0),
        owner: Result<String?> = Result.success(null),
        packageId: Result<String?> = Result.success(null),
        localUsers: Result<String?> = Result.success(null),
        remoteUsers: Result<String?> = Result.success(null),
        remoteMachines: Result<String?> = Result.success(null),
    ) = WindowsFirewallRuleRecord(
        action, profiles, applicationName, protocol, localPorts, remotePorts, localAddresses, remoteAddresses,
        serviceName, interfacesRestricted, interfaceTypes, secureFlags, owner, packageId, localUsers, remoteUsers,
        remoteMachines,
    )

    private val allProfilesOn = listOf(
        WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN,
        WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE,
        WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC,
    ).map { WindowsFirewallProfileRecord(it, enabled = true, blockAllInbound = false) }

    private fun comState(
        vararg rules: WindowsFirewallRuleRecord,
        active: Int = WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE,
        profiles: List<WindowsFirewallProfileRecord> = allProfilesOn,
    ) = parseWindowsFirewallState(windowsFirewallSnapshotJson(rules.toList(), active, profiles), executable)

    /** The two rules Starlit adds, as COM reports them back. */
    private fun starlitRules(program: String = executable) = arrayOf(
        comRule(
            applicationName = Result.success(program),
            profiles = Result.success(WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN or WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE),
            remoteAddresses = Result.success("LocalSubnet"),
        ),
        comRule(
            applicationName = Result.success(program),
            profiles = Result.success(WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN or WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE),
            protocol = Result.success(WindowsFirewallCom.NET_FW_IP_PROTOCOL_UDP),
            localPorts = Result.success("47788"),
            remoteAddresses = Result.success("LocalSubnet"),
        ),
    )

    @Test
    fun comSnapshotOfStarlitRulesAllowsThePhoneRemote() {
        assertEquals(RemoteFirewallState.Allowed, comState(*starlitRules()))
        assertEquals(RemoteFirewallState.Allowed, comState(*starlitRules(), active = WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN))
        assertEquals(RemoteFirewallState.Blocked, comState(*starlitRules("""C:\Other\App.exe""")))
        assertEquals(RemoteFirewallState.Blocked, comState())
    }

    @Test
    fun comSnapshotMapsNetworksAndProfilePolicy() {
        assertEquals(
            RemoteFirewallState.PublicNetwork,
            comState(*starlitRules(), active = WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC),
        )
        val shieldsUp = allProfilesOn.map { it.copy(blockAllInbound = true) }
        assertEquals(RemoteFirewallState.Blocked, comState(*starlitRules(), profiles = shieldsUp))
        val firewallOff = allProfilesOn.map { it.copy(enabled = false) }
        assertEquals(RemoteFirewallState.Allowed, comState(profiles = firewallOff))
        // A profile Windows would not report can never prove access.
        assertEquals(RemoteFirewallState.Blocked, comState(*starlitRules(), profiles = allProfilesOn.filter { it.type != WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE }))

        val json = Json.parseToJsonElement(windowsFirewallSnapshotJson(
            listOf(comRule(profiles = Result.success(WindowsFirewallCom.NET_FW_PROFILE2_ALL))),
            WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN or WindowsFirewallCom.NET_FW_PROFILE2_PUBLIC,
            allProfilesOn,
        )).jsonObject
        assertEquals("\"Any\"", json["rules"]!!.jsonArray[0].jsonObject["profile"].toString())
        assertEquals(2, json["networks"]!!.jsonArray.size)
    }

    @Test
    fun comBlockRulesStillOverrideStarlitRules() {
        val anyPortBlock = comRule(
            action = Result.success(WindowsFirewallCom.NET_FW_ACTION_BLOCK),
            profiles = Result.success(WindowsFirewallCom.NET_FW_PROFILE2_ALL),
            protocol = Result.success(WindowsFirewallCom.NET_FW_IP_PROTOCOL_ANY),
            localPorts = Result.success(null),
        )
        assertEquals(RemoteFirewallState.Blocked, comState(*starlitRules(), anyPortBlock))
        val unreadablePortBlock = anyPortBlock.copy(
            applicationName = Result.success(executable),
            localPorts = Result.failure(IllegalStateException("Access is denied")),
        )
        assertEquals(RemoteFirewallState.Blocked, comState(*starlitRules(), unreadablePortBlock))
        val otherPortBlock = anyPortBlock.copy(
            protocol = Result.success(WindowsFirewallCom.NET_FW_IP_PROTOCOL_TCP),
            localPorts = Result.success("8080"),
        )
        assertEquals(RemoteFirewallState.Allowed, comState(*starlitRules(), otherPortBlock))
        // Rules owned by a user or an app package are skipped, as before.
        assertEquals(RemoteFirewallState.Allowed, comState(*starlitRules(), anyPortBlock.copy(owner = Result.success("S-1-5-21-1"))))
        assertEquals(RemoteFirewallState.Allowed, comState(*starlitRules(), anyPortBlock.copy(packageId = Result.success("S-1-15-2-1"))))
    }

    @Test
    fun unreadableOrRestrictedComPropertiesCannotProveAccess() {
        val failure = Result.failure<Nothing>(IllegalStateException("Access is denied"))
        val variants = listOf<(WindowsFirewallRuleRecord) -> WindowsFirewallRuleRecord>(
            { it.copy(action = failure) },
            { it.copy(applicationName = failure) },
            { it.copy(protocol = failure) },
            { it.copy(localPorts = failure) },
            { it.copy(remotePorts = failure) },
            { it.copy(remoteAddresses = failure) },
            { it.copy(serviceName = failure) },
            { it.copy(interfacesRestricted = failure) },
            { it.copy(interfacesRestricted = Result.success(true)) },
            { it.copy(interfaceTypes = Result.success("Wireless")) },
            { it.copy(secureFlags = failure) },
            { it.copy(secureFlags = Result.success(1)) },
            { it.copy(remoteUsers = Result.success("O:LSD:(A;;CC;;;S-1-5-21-1)")) },
            { it.copy(remoteMachines = failure) },
        )
        val (tcp, udp) = starlitRules()
        for (change in variants) {
            assertEquals(RemoteFirewallState.Blocked, comState(change(tcp), udp))
        }
    }

    @Test
    fun liveFirewallIsReadInProcess() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val snapshot = Json.parseToJsonElement(WindowsFirewallCom.snapshotJson()).jsonObject
        assertEquals(3, snapshot["profiles"]!!.jsonArray.size)
        assertTrue(snapshot["networks"]!!.jsonArray.isNotEmpty())
        // Every Windows install ships enabled inbound rules (for example Core Networking).
        assertTrue(snapshot["rules"]!!.jsonArray.isNotEmpty())
        parseWindowsFirewallState(snapshot.toString(), executable)
    }

    @Test
    fun starlitRulesRoundTripThroughComWithoutChangingTheFirewall() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val program = """C:\Program Files\O'Brien\Aplicação 日本語\Starlit.exe"""
        val records = WindowsCom.withApartment {
            listOf(
                WindowsFirewallCom.withPhoneRemoteRule(FIREWALL_TCP_RULE, WindowsFirewallCom.NET_FW_IP_PROTOCOL_TCP, FIREWALL_SESSION_PORT, program, WindowsFirewallCom::recordOf),
                WindowsFirewallCom.withPhoneRemoteRule(FIREWALL_UDP_RULE, WindowsFirewallCom.NET_FW_IP_PROTOCOL_UDP, FIREWALL_DISCOVERY_PORT, program, WindowsFirewallCom::recordOf),
            )
        }
        val tcp = records[0]
        assertEquals(WindowsFirewallCom.NET_FW_ACTION_ALLOW, tcp.action.getOrThrow())
        assertEquals(WindowsFirewallCom.NET_FW_PROFILE2_DOMAIN or WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE, tcp.profiles.getOrThrow())
        assertEquals(program, tcp.applicationName.getOrThrow())
        assertEquals(WindowsFirewallCom.NET_FW_IP_PROTOCOL_TCP, tcp.protocol.getOrThrow())
        assertEquals("47789", tcp.localPorts.getOrThrow())
        assertEquals("47788", records[1].localPorts.getOrThrow())
        assertEquals("LocalSubnet", tcp.remoteAddresses.getOrThrow())
        assertEquals(
            RemoteFirewallState.Allowed,
            parseWindowsFirewallState(windowsFirewallSnapshotJson(records, WindowsFirewallCom.NET_FW_PROFILE2_PRIVATE, allProfilesOn), program),
        )
    }
}
