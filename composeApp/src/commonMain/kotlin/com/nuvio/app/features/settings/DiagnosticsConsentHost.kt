package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.diagnostics.DiagnosticsConsent
import com.nuvio.app.core.diagnostics.UsageDiagnostics
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSwitchColors
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.diagnostics_consent_crashes
import nuvio.composeapp.generated.resources.diagnostics_consent_crashes_detail
import nuvio.composeapp.generated.resources.diagnostics_consent_description
import nuvio.composeapp.generated.resources.diagnostics_consent_description_single
import nuvio.composeapp.generated.resources.diagnostics_consent_not_now
import nuvio.composeapp.generated.resources.diagnostics_consent_privacy
import nuvio.composeapp.generated.resources.diagnostics_consent_save
import nuvio.composeapp.generated.resources.diagnostics_consent_title
import nuvio.composeapp.generated.resources.diagnostics_consent_usage
import nuvio.composeapp.generated.resources.diagnostics_consent_usage_detail
import org.jetbrains.compose.resources.stringResource

/** First-run decision only after the user has entered the app, not on the login screen. */
@Composable
internal fun DiagnosticsConsentHost() {
    val consentFlow = remember {
        UsageDiagnostics.ensureLoaded()
        UsageDiagnostics.consent
    }
    val consent by consentFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UsageDiagnostics.onAppOpen() }
    if (consent != DiagnosticsConsent.Undecided) return

    var usage by rememberSaveable { mutableStateOf(false) }
    var crashes by rememberSaveable { mutableStateOf(false) }
    val sentrySupported = SentrySettingsRepository.isSupported
    val decide: (Boolean, Boolean) -> Unit = { allowUsage, allowCrashes ->
        // Explicitly reject crash reporting when the user declines the first-run invitation.
        SentrySettingsRepository.setEnabled(sentrySupported && allowCrashes)
        UsageDiagnostics.setEnabled(allowUsage)
    }

    DialogSurface(
        onDismissRequest = { decide(false, false) },
        title = stringResource(Res.string.diagnostics_consent_title),
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        val tokens = MaterialTheme.nuvio
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Text(
                text = stringResource(
                    if (sentrySupported) Res.string.diagnostics_consent_description
                    else Res.string.diagnostics_consent_description_single,
                    stringResource(Res.string.compose_settings_page_root),
                    stringResource(Res.string.compose_settings_page_advanced),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
            )
            DiagnosticsChoice(
                title = stringResource(Res.string.diagnostics_consent_usage),
                description = stringResource(Res.string.diagnostics_consent_usage_detail),
                note = stringResource(Res.string.diagnostics_consent_privacy),
                checked = usage,
                onCheckedChange = { usage = it },
            )
            if (sentrySupported) {
                DiagnosticsChoice(
                    title = stringResource(Res.string.diagnostics_consent_crashes),
                    description = stringResource(Res.string.diagnostics_consent_crashes_detail),
                    checked = crashes,
                    onCheckedChange = { crashes = it },
                )
            }
        }
        if (scroll.canScrollBackward || scroll.canScrollForward) {
            HorizontalDivider(color = tokens.colors.borderSubtle)
        }
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.diagnostics_consent_not_now),
                onClick = { decide(false, false) },
            )
            DialogButton(
                text = stringResource(Res.string.diagnostics_consent_save),
                onClick = { decide(usage, crashes) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}

@Composable
private fun DiagnosticsChoice(
    title: String,
    description: String,
    note: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = NuvioTokens.Space.s48)
            .padding(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tokens.colors.textPrimary,
            )
            Text(description, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
            }
        }
        Switch(checked = checked, onCheckedChange = null, colors = nuvioSwitchColors())
    }
}
