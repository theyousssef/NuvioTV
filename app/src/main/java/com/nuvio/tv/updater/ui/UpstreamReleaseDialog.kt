@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.updater.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpstreamReleaseNotice

/** One-time notice that a new stable Nuvio version is out. Informational only: nothing is downloaded. */
@Composable
fun UpstreamReleaseDialog(
    notice: UpstreamReleaseNotice,
    onDismiss: () -> Unit
) {
    val okFocusRequester = remember { FocusRequester() }
    val qrBitmap = remember(notice.url) {
        runCatching { QrCodeGenerator.generate(notice.url, 420, margin = 1) }.getOrNull()
    }
    val releaseLine = if (notice.publishedAt != null) {
        stringResource(R.string.upstream_release_name_date, notice.name, notice.publishedAt)
    } else {
        notice.name
    }

    LaunchedEffect(notice.tag) {
        okFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.upstream_release_title, notice.tag),
        subtitle = releaseLine,
        width = 680.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.upstream_release_body, notice.tag),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    text = notice.url,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(okFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Secondary,
                        focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                        contentColor = NuvioTheme.colors.OnSecondary,
                        focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(50))
                ) {
                    Text(stringResource(R.string.upstream_release_ok))
                }
            }
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_upstream_release_qr),
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(NuvioTheme.spacing.md))
                )
            }
        }
    }
}
