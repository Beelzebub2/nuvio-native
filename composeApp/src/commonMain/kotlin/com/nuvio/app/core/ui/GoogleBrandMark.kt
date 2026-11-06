package com.nuvio.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.google_g_mark
import org.jetbrains.compose.resources.painterResource

/** The Google identity mark is decorative; the enclosing button supplies its accessible label. */
@Composable
fun GoogleBrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.google_g_mark),
        contentDescription = null,
        modifier = modifier,
    )
}
