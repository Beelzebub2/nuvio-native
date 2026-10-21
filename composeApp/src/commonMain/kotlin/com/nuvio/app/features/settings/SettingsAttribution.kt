package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionPolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.about_based_on_nuvio
import nuvio.composeapp.generated.resources.about_based_on_nuvio_version_format
import nuvio.composeapp.generated.resources.about_made_by_format
import nuvio.composeapp.generated.resources.compose_about_version_format
import org.jetbrains.compose.resources.stringResource

// Starlit is Beelzebub2's rebrand of Nuvio.
internal const val CreatorHandle = "Beelzebub2"
internal const val CreatorProfileUrl = "https://github.com/Beelzebub2"

@Composable
internal fun SettingsAttribution(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
    centered: Boolean = true,
) {
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
    ) {
        MemberBrandWordmark(
            height = if (isTablet) 30.dp else 26.dp,
            modifier = Modifier.align(if (centered) Alignment.CenterHorizontally else Alignment.Start),
        )
        Spacer(
            modifier = Modifier.height(if (isTablet) 10.dp else 8.dp),
        )

        val madeBy = stringResource(Res.string.about_made_by_format, CreatorHandle)
        val linkStyle = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textDecoration = TextDecoration.Underline,
            ),
        )
        Text(
            text = buildAnnotatedString {
                append(madeBy)
                val start = madeBy.indexOf(CreatorHandle)
                if (start >= 0) {
                    addLink(LinkAnnotation.Url(CreatorProfileUrl, linkStyle), start, start + CreatorHandle.length)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
        Text(
            text = stringResource(
                Res.string.compose_about_version_format,
                AppVersionPolicy.displayVersionName,
                AppVersionPolicy.displayVersionCode,
            ),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
        Text(
            text = AppVersionPolicy.basedOnVersionName
                ?.let { stringResource(Res.string.about_based_on_nuvio_version_format, it) }
                ?: stringResource(Res.string.about_based_on_nuvio),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
        )
    }
}
