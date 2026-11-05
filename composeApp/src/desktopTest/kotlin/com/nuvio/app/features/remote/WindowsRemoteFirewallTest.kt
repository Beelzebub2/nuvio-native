package com.nuvio.app.features.remote

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WindowsRemoteFirewallTest {
    private val executable = """C:\Program Files\Starlit\Starlit.exe"""

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

    @Test
    fun inspectionUsesBoundedPortRecoveryAndChecksAdditionalFirewallFilters() {
        val script = windowsFirewallInspectionScript(executable)
        assertContains(script, "Get-NetFirewallPortFilter -PolicyStore ActiveStore -ErrorAction Stop")
        assertContains(script, "Get-NetFirewallPortFilter -ErrorAction Stop")
        assertContains(script, "portFallbackBudget = 24")
        assertContains(script, "Sort-Object -Property")
        assertContains(script, "targetProgram = 'C:\\Program Files\\Starlit\\Starlit.exe'")
        listOf("Address", "Service", "Interface", "InterfaceType", "Security").forEach { filter ->
            assertContains(script, "Get-NetFirewall${filter}Filter -PolicyStore ActiveStore")
        }
        assertContains(script, "Get-NetFirewallProfile -PolicyStore ActiveStore")
    }

    @Test
    fun scriptsEscapeQuotesAndRoundTripUtf16EncodedCommand() {
        val path = """C:\Program Files\O'Brien\Aplicação 日本語 ¤ __GROUP__\Starlit.exe"""
        val inspection = windowsFirewallInspectionScript(path)
        assertContains(inspection, """¤targetProgram = 'C:\Program Files\O''Brien\Aplicação 日本語 ¤ __GROUP__\Starlit.exe'""".replaceFirst('¤', '$'))
        val elevated = windowsFirewallElevatedScript(path)
        assertContains(elevated, """¤program = 'C:\Program Files\O''Brien\Aplicação 日本語 ¤ __GROUP__\Starlit.exe'""".replaceFirst('¤', '$'))
        assertContains(elevated, "-LocalPort 47789 -Profile Domain,Private -RemoteAddress LocalSubnet")
        assertContains(elevated, "-LocalPort 47788 -Profile Domain,Private -RemoteAddress LocalSubnet")
        assertContains(elevated, "Get-NetFirewallRule -PolicyStore PersistentStore -Group")
        assertContains(elevated, "Action -eq 'Allow'")
        assertFalse(elevated.contains("Disable-NetFirewallRule"))
        assertFalse(elevated.contains("-Action Block"))

        val launcher = windowsFirewallElevationLauncherScript(path)
        assertContains(launcher, "-Verb RunAs -WindowStyle Hidden -Wait -PassThru")
        val match = Regex("""-EncodedCommand', '([A-Za-z0-9+/=]+)'""").find(launcher)
        val childEncoded = assertNotNull(match).groupValues[1]
        val decodedChild = String(Base64.getDecoder().decode(childEncoded), Charsets.UTF_16LE)
        assertEquals(elevated, decodedChild)
        val parentEncoded = encodeWindowsPowerShell(launcher)
        assertEquals(launcher, String(Base64.getDecoder().decode(parentEncoded), Charsets.UTF_16LE))
    }
}
