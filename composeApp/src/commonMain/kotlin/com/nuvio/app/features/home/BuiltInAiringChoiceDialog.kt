package com.nuvio.app.features.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.home_airing_first_run_description
import nuvio.composeapp.generated.resources.home_airing_first_run_hide
import nuvio.composeapp.generated.resources.home_airing_first_run_show
import nuvio.composeapp.generated.resources.home_airing_first_run_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BuiltInAiringChoiceDialog(onChoose: (Boolean) -> Unit) {
    DialogSurface(
        onDismissRequest = {},
        title = stringResource(Res.string.home_airing_first_run_title),
        message = stringResource(Res.string.home_airing_first_run_description),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.home_airing_first_run_hide),
                onClick = { onChoose(false) },
            )
            DialogButton(
                text = stringResource(Res.string.home_airing_first_run_show),
                onClick = { onChoose(true) },
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
