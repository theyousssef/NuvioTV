@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.core.trakt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/** Asks the user to connect Trakt again after their Trakt session expired or was revoked. */
@Composable
fun TraktReauthDialog(
    onLogIn: () -> Unit,
    onLater: () -> Unit
) {
    val logInFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        logInFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onLater,
        title = stringResource(R.string.trakt_reauth_title),
        width = 600.dp,
        suppressFirstKeyUp = false
    ) {
        Text(
            text = stringResource(R.string.trakt_reauth_body),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onLogIn,
                modifier = Modifier.focusRequester(logInFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.trakt_reauth_log_in))
            }
            Button(
                onClick = onLater,
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(stringResource(R.string.trakt_reauth_later))
            }
        }
    }
}
