package com.opencloudgaming.opennow
import com.papahchan.nanaplay.BuildConfig
import com.papahchan.nanaplay.R

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.os.PowerManager
import android.os.BatteryManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import kotlinx.coroutines.delay

@Composable
internal fun AppDataSettingsPanel(viewModel: OpenNowViewModel) {
    var clearCacheConfirmOpen by remember { mutableStateOf(false) }
    var resetSettingsConfirmOpen by remember { mutableStateOf(false) }
    if (clearCacheConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearCacheConfirmOpen = false },
            title = { Text(stringResource(R.string.settings_appdata_clear_cache_title)) },
            text = { Text(stringResource(R.string.settings_appdata_clear_cache_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        clearCacheConfirmOpen = false
                        viewModel.clearCatalogCache()
                    },
                ) {
                    Text(stringResource(R.string.settings_appdata_clear_cache_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (resetSettingsConfirmOpen) {
        AlertDialog(
            onDismissRequest = { resetSettingsConfirmOpen = false },
            title = { Text(stringResource(R.string.settings_appdata_reset_title)) },
            text = { Text(stringResource(R.string.settings_appdata_reset_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        resetSettingsConfirmOpen = false
                        viewModel.resetSettings()
                    },
                ) {
                    Text(stringResource(R.string.settings_appdata_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetSettingsConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.settings_appdata_reset_note),
            color = SettingsTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { clearCacheConfirmOpen = true }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_appdata_clear_cache_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = viewModel::resetStreamTutorial, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_appdata_reset_tutorial), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = { resetSettingsConfirmOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_appdata_reset_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun AndroidUpdatePanel(state: OpenNowUiState, viewModel: OpenNowViewModel) {
    val update = state.androidUpdate
    if (!update.updateChecksSupported) {
        AndroidUpdateUnavailablePanel(update)
        return
    }
    val updateCheckingDisabled = !state.settings.autoCheckForUpdates
    val checkBlockedByStream = state.isAndroidUpdateCheckBlockedByStream()
    val showCheckPauseMessage = checkBlockedByStream && when (update.status) {
        AndroidUpdateStatus.Available,
        AndroidUpdateStatus.Downloading,
        AndroidUpdateStatus.Downloaded -> false
        else -> true
    }
    val statusMessage = when {
        updateCheckingDisabled -> stringResource(R.string.update_checks_off)
        showCheckPauseMessage -> stringResource(R.string.update_checks_paused)
        else -> updateStatusSubtitle(update)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (update.status in updateAvailableStatuses) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        updateStatusTitle(update),
                        color = SettingsText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusMessage,
                        color = SettingsTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                UpdateStatusBadge(update.status)
            }
            UpdateVersionSummary(update)
            if (update.status == AndroidUpdateStatus.Downloading) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    update.progress?.let { progress ->
                        Text(
                            formatAndroidUpdateProgress(progress),
                            color = SettingsTextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            UpdateReleaseNotes(update.releaseNotes)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = viewModel::checkAndroidUpdate,
                    enabled = update.canCheck && !checkBlockedByStream && !updateCheckingDisabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (update.status == AndroidUpdateStatus.Checking) {
                            stringResource(R.string.update_button_checking)
                        } else {
                            stringResource(R.string.update_button_check)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    update.status == AndroidUpdateStatus.Available -> {
                        Button(
                            onClick = viewModel::performAndroidUpdatePrimaryAction,
                            enabled = update.canDownload || update.canOpenPlayStore,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (update.installSource.isGooglePlay) {
                                    stringResource(R.string.update_button_update)
                                } else {
                                    stringResource(R.string.update_button_download)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    update.status == AndroidUpdateStatus.Downloaded -> {
                        Button(
                            onClick = viewModel::installAndroidUpdate,
                            enabled = update.canInstall,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.update_button_install), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidUpdateUnavailablePanel(update: AndroidUpdateState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (update.installSource.isGooglePlay) {
                            stringResource(R.string.update_unavailable_play_title)
                        } else {
                            stringResource(R.string.update_unavailable_apk_title)
                        },
                        color = SettingsText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        update.message,
                        color = SettingsTextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                ) {
                    Text(
                        if (update.installSource.isGooglePlay) {
                            stringResource(R.string.update_badge_play)
                        } else {
                            stringResource(R.string.update_badge_locked)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdateInfoValue(stringResource(R.string.update_info_current), formatCurrentUpdateVersion(update), Modifier.weight(1f))
                    UpdateInfoValue(stringResource(R.string.update_info_source), update.installSource.displayName, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusBadge(status: AndroidUpdateStatus) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = updateMessageColor(status).copy(alpha = 0.16f),
    ) {
        Text(
            updateStatusBadgeText(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = updateMessageColor(status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private val updateAvailableStatuses = setOf(
    AndroidUpdateStatus.Available,
    AndroidUpdateStatus.Downloading,
    AndroidUpdateStatus.Downloaded,
)

@Composable
private fun UpdateVersionSummary(update: AndroidUpdateState) {
    val checked = update.lastCheckedAt?.let { checkedAt ->
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(checkedAt))
    }
    val availableVersion = formatAvailableUpdateVersion(update)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                UpdateInfoValue(stringResource(R.string.update_info_current), formatCurrentUpdateVersion(update), Modifier.weight(1f))
                availableVersion?.let {
                    UpdateInfoValue(stringResource(R.string.update_info_available), it, Modifier.weight(1f))
                }
            }
            checked?.let {
                Text(
                    stringResource(R.string.update_last_checked, it),
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UpdateInfoValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            color = SettingsTextMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            color = SettingsText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UpdateReleaseNotes(notes: String?) {
    val releaseNotes = notes?.trim()?.takeIf { it.isNotBlank() } ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.update_release_notes),
                color = SettingsText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                releaseNotes,
                color = SettingsTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatAvailableUpdateVersion(update: AndroidUpdateState): String? {
    val pieces = listOfNotNull(
        update.availableVersionName?.let { "v$it" },
        update.availableVersionCode?.let { "build $it" },
    )
    return pieces.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun formatCurrentUpdateVersion(update: AndroidUpdateState): String =
    listOfNotNull(
        update.currentVersionName.takeIf(String::isNotBlank)?.let { "v$it" },
        "build ${update.currentVersionCode}",
    ).joinToString(" ")

@Composable
private fun updateStatusTitle(update: AndroidUpdateState): String =
    when (update.status) {
        AndroidUpdateStatus.Available -> stringResource(R.string.update_status_available)
        AndroidUpdateStatus.Downloading -> stringResource(R.string.update_status_downloading)
        AndroidUpdateStatus.Downloaded -> stringResource(R.string.update_status_downloaded)
        AndroidUpdateStatus.NotAvailable -> stringResource(R.string.update_status_up_to_date)
        AndroidUpdateStatus.Checking -> stringResource(R.string.update_status_checking)
        AndroidUpdateStatus.Error -> stringResource(R.string.update_status_error)
        AndroidUpdateStatus.Idle -> stringResource(R.string.update_status_idle)
    }

@Composable
private fun updateStatusSubtitle(update: AndroidUpdateState): String =
    when (update.status) {
        AndroidUpdateStatus.Available -> if (update.installSource.isGooglePlay) {
            update.message
        } else {
            update.availableVersionName?.let { stringResource(R.string.update_subtitle_version_available, it) }
                ?: stringResource(R.string.update_subtitle_new_build)
        }
        AndroidUpdateStatus.Downloading -> stringResource(R.string.update_subtitle_keep_open)
        AndroidUpdateStatus.Downloaded -> update.availableVersionName?.let { stringResource(R.string.update_subtitle_downloaded_version, it) }
            ?: stringResource(R.string.update_subtitle_downloaded)
        AndroidUpdateStatus.NotAvailable -> update.message
        AndroidUpdateStatus.Checking -> if (update.installSource.isGooglePlay) {
            stringResource(R.string.update_subtitle_checking_play)
        } else {
            stringResource(R.string.update_subtitle_checking_source)
        }
        AndroidUpdateStatus.Error -> update.message
        AndroidUpdateStatus.Idle -> update.message
    }

@Composable
private fun updateStatusBadgeText(status: AndroidUpdateStatus): String =
    when (status) {
        AndroidUpdateStatus.Available -> stringResource(R.string.update_badge_new)
        AndroidUpdateStatus.Downloading -> stringResource(R.string.update_badge_downloading)
        AndroidUpdateStatus.Downloaded -> stringResource(R.string.update_badge_ready)
        AndroidUpdateStatus.NotAvailable -> stringResource(R.string.update_badge_current)
        AndroidUpdateStatus.Checking -> stringResource(R.string.update_badge_checking)
        AndroidUpdateStatus.Error -> stringResource(R.string.update_badge_error)
        AndroidUpdateStatus.Idle -> stringResource(R.string.update_badge_idle)
    }

@Composable
private fun updateMessageColor(status: AndroidUpdateStatus): Color =
    when (status) {
        AndroidUpdateStatus.Available,
        AndroidUpdateStatus.Downloaded,
        AndroidUpdateStatus.NotAvailable -> MaterialTheme.colorScheme.primary
        AndroidUpdateStatus.Error -> Color(0xffff9f9f)
        else -> SettingsTextMuted
    }

private fun formatAndroidUpdateProgress(progress: AndroidUpdateProgress): String {
    val bytes = progress.totalBytes?.let { total ->
        "${formatUpdateBytes(progress.transferredBytes)} / ${formatUpdateBytes(total)}"
    } ?: formatUpdateBytes(progress.transferredBytes)
    return progress.percent?.let { "$it% - $bytes" } ?: bytes
}

private fun formatUpdateBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unit = units.first()
    for (index in 1 until units.size) {
        if (value < 1024.0) break
        value /= 1024.0
        unit = units[index]
    }
    return "%.1f %s".format(Locale.US, value, unit)
}

@Composable
internal fun AccountSettingsPanel(state: OpenNowUiState, viewModel: OpenNowViewModel) {
    val currentSession = state.authSession
    val currentUserId = currentSession?.user?.userId
    val context = LocalContext.current
    val noBrowserAvailable = stringResource(R.string.browser_unavailable)
    val fallbackAccountName = stringResource(R.string.account_fallback_name)
    var addAccountPromptOpen by remember { mutableStateOf(false) }
    val addAccountProviders = remember(state.providers, state.selectedProvider) {
        accountProviderOptions(state.providers, state.selectedProvider)
    }
    if (addAccountPromptOpen) {
        AddAccountProviderDialog(
            providers = addAccountProviders,
            selectedProvider = state.selectedProvider,
            onProviderSelected = { provider ->
                addAccountPromptOpen = false
                viewModel.selectProvider(provider)
                viewModel.login(provider)
            },
            onDismiss = { addAccountPromptOpen = false },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.savedAccounts.ifEmpty {
            state.authSession?.toSavedAccount()?.let { listOf(it) } ?: emptyList()
        }.forEach { account ->
            val selected = account.userId == currentUserId
            val membershipTier = if (selected) {
                state.subscriptionInfo?.membershipTier?.takeIf { it.isNotBlank() }
                    ?: currentSession.user.membershipTier.takeIf { it.isNotBlank() }
                    ?: account.membershipTier
            } else {
                account.membershipTier
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(account.displayName.ifBlank { fallbackAccountName }, color = SettingsText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(account.email?.takeIf { it.isNotBlank() }, account.providerCode, membershipTier).joinToString(" - "),
                            color = SettingsTextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) {
                        Text(stringResource(R.string.account_badge_active), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    } else {
                        OutlinedButton(onClick = { viewModel.switchAccount(account.userId) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(stringResource(R.string.account_switch))
                        }
                    }
                }
            }
        }
        AndroidUpdateNoticeRow(
            update = state.androidUpdate,
            dismissedKey = state.dismissedAndroidUpdateNoticeKey,
            onOpenUpdates = viewModel::openAndroidUpdateSettings,
            onDismiss = viewModel::dismissAndroidUpdateNotice,
        )
        state.deviceLoginPrompt?.let { prompt ->
            DeviceLoginPanel(
                prompt = prompt,
                phase = state.launchPhase,
                onCancel = viewModel::cancelLogin,
                modifier = Modifier.fillMaxWidth(),
                qrMaxSize = 240.dp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { addAccountPromptOpen = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.account_add)) }
            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.account_sign_out)) }
        }
        OutlinedButton(onClick = viewModel::logoutAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_sign_out_all)) }
        AccountPlayTimeStatsPanel(
            subscriptionInfo = state.subscriptionInfo,
            fallbackMembershipTier = state.authSession?.user?.membershipTier,
        )
        StorageAddonPanel(
            storageAddon = state.subscriptionInfo?.storageAddon,
            openExternal = { url ->
                if (!openExternalUrl(context, url)) {
                    Toast.makeText(context, noBrowserAvailable, Toast.LENGTH_SHORT).show()
                }
            },
        )
        AccountConnectorsPanel(
            connectors = state.accountConnectors,
            loading = state.loadingAccountConnectors,
            actionStore = state.connectorActionStore,
            onRefresh = viewModel::refreshAccountConnectors,
            onConnect = { connector ->
                viewModel.connectAccountConnector(connector.store) { url ->
                    if (!openExternalUrl(context, url)) {
                        Toast.makeText(context, noBrowserAvailable, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDisconnect = { connector ->
                viewModel.disconnectAccountConnector(connector.store)
            },
            openExternal = { url ->
                if (!openExternalUrl(context, url)) {
                    Toast.makeText(context, noBrowserAvailable, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun AddAccountProviderDialog(
    providers: List<LoginProvider>,
    selectedProvider: LoginProvider,
    onProviderSelected: (LoginProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    var providerChoice by remember(providers, selectedProvider) {
        mutableStateOf(providers.preferredProvider(selectedProvider))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_provider_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.account_provider_body),
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                providers.forEach { provider ->
                    ProviderChoiceRow(
                        provider = provider,
                        selected = provider.sameProvider(providerChoice),
                        onClick = { providerChoice = provider },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onProviderSelected(providerChoice) }) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ProviderChoiceRow(provider: LoginProvider, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f)
        },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    provider.displayName,
                    color = SettingsText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    provider.code,
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Text(stringResource(R.string.account_provider_selected), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AccountPlayTimeStatsPanel(subscriptionInfo: SubscriptionInfo?, fallbackMembershipTier: String?) {
    val sessionLimit = smartSessionLimitFor(subscriptionInfo, fallbackMembershipTier)
    val monthlyLimit = monthlyHourLimitFor(subscriptionInfo, fallbackMembershipTier)
    val monthlyRemaining = monthlyHoursRemainingFor(subscriptionInfo, fallbackMembershipTier)
    val usedHours = subscriptionInfo?.usedHours?.takeIf { it > 0.0 }
    val progressFraction = if (monthlyLimit != null && monthlyLimit > 0.0) {
        ((usedHours ?: 0.0) / monthlyLimit).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }
    val freePlan = sessionLimit.mode == SessionTimerMode.Countdown
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.playtime_title), color = SettingsText, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                UsageMetricTile(
                    label = stringResource(R.string.playtime_session),
                    value = "${sessionLimit.limitHours}h",
                    detail = when (sessionLimit.mode) {
                        SessionTimerMode.Countdown -> stringResource(R.string.playtime_mode_countdown)
                        SessionTimerMode.Stopwatch -> stringResource(R.string.playtime_mode_stopwatch)
                    },
                    modifier = Modifier.weight(1f),
                )
                UsageMetricTile(
                    label = stringResource(R.string.playtime_monthly_left),
                    value = monthlyRemaining?.let(::formatPlayTimeHours) ?: "--",
                    detail = monthlyLimit?.let { stringResource(R.string.playtime_of_value, formatPlayTimeHours(it)) }
                        ?: if (freePlan) {
                            stringResource(R.string.playtime_paid_plans)
                        } else {
                            stringResource(R.string.playtime_refresh_account)
                        },
                    modifier = Modifier.weight(1f),
                )
            }
            if (progressFraction != null && monthlyLimit != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.playtime_used_value, formatPlayTimeHours(usedHours ?: 0.0)),
                            color = SettingsTextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(
                                R.string.playtime_progress_value,
                                formatPlayTimePercent(progressFraction),
                                formatPlayTimeHours(monthlyLimit),
                            ),
                            color = SettingsTextMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .semantics {
                                contentDescription = "Monthly play time ${formatPlayTimePercent(progressFraction)} used"
                                progressBarRangeInfo = ProgressBarRangeInfo(progressFraction, 0f..1f)
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progressFraction)
                                .height(4.dp)
                                .background(
                                    when {
                                        progressFraction >= 0.9f -> Color(0xffff8a65)
                                        progressFraction >= 0.75f -> Color(0xffffc266)
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                ),
                        )
                    }
                }
            } else {
                Text(
                    if (freePlan) {
                        stringResource(R.string.playtime_paid_note)
                    } else {
                        stringResource(R.string.playtime_refresh_note)
                    },
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UsageMetricTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = SettingsTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = SettingsText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = SettingsTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun AndroidUpdateNoticeRow(
    update: AndroidUpdateState,
    dismissedKey: String?,
    onOpenUpdates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val noticeKey = update.visibleNoticeKey(dismissedKey) ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenUpdates),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UpdateStatusBadge(update.status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(accountUpdateTitle(update), color = SettingsText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(accountUpdateSubtitle(update), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (update.status == AndroidUpdateStatus.Downloading) {
                CircularUpdateProgress(update.progress)
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss update ${noticeKey.takeLast(12)}" },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_clear),
                    contentDescription = null,
                    tint = SettingsTextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CircularUpdateProgress(progress: AndroidUpdateProgress?) {
    val label = progress?.let(::formatAndroidUpdateProgress) ?: stringResource(R.string.update_progress_downloading)
    Text(
        label,
        color = SettingsTextMuted,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun accountUpdateTitle(update: AndroidUpdateState): String =
    when (update.status) {
        AndroidUpdateStatus.Downloaded -> stringResource(R.string.update_ready_title)
        AndroidUpdateStatus.Downloading -> stringResource(R.string.update_ready_downloading)
        else -> stringResource(R.string.update_ready_available)
    }

@Composable
private fun accountUpdateSubtitle(update: AndroidUpdateState): String =
    update.availableVersionName?.let { stringResource(R.string.update_ready_version, it) }
        ?: update.message

@Composable
private fun StorageAddonPanel(storageAddon: StorageAddon?, openExternal: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.storage_title), color = SettingsText, fontWeight = FontWeight.SemiBold)
            if (storageAddon == null) {
                Text(stringResource(R.string.storage_no_addon), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { openExternal(GFN_ADD_STORAGE_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_add), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val used = storageAddon.usedGb
                val total = storageAddon.sizeGb
                val usageFraction = storageUsageFraction(used, total)
                Text(
                    listOfNotNull(
                        total?.let { stringResource(R.string.storage_total, formatStorageGb(it)) },
                        used?.let { stringResource(R.string.storage_used, formatStorageGb(it)) },
                        if (used != null && total != null) {
                            stringResource(R.string.storage_available, formatStorageGb((total - used).coerceAtLeast(0.0)))
                        } else {
                            null
                        },
                    ).joinToString(" - "),
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (usageFraction != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.storage_usage_title),
                                color = SettingsText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.playtime_used_value, formatStoragePercent(usageFraction)),
                                color = SettingsTextMuted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        val usageColor = when {
                            usageFraction >= 0.9f -> Color(0xffff8a65)
                            usageFraction >= 0.75f -> Color(0xffffc266)
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                                .semantics {
                                    contentDescription = "Cloud storage ${formatStoragePercent(usageFraction)} used"
                                    progressBarRangeInfo = ProgressBarRangeInfo(usageFraction, 0f..1f)
                                },
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(usageFraction.coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(usageColor),
                            )
                        }
                    }
                }
                storageAddon.regionName?.takeIf { it.isNotBlank() }?.let { region ->
                    Text(stringResource(R.string.storage_location, region), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { openExternal(GFN_STORAGE_MANAGEMENT_URL) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.storage_manage), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { openExternal(GFN_STORAGE_RESET_URL) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.storage_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(onClick = { openExternal(GFN_STORAGE_MANAGEMENT_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.storage_change_location), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AccountConnectorsPanel(
    connectors: List<AccountConnector>,
    loading: Boolean,
    actionStore: String?,
    onRefresh: () -> Unit,
    onConnect: (AccountConnector) -> Unit,
    onDisconnect: (AccountConnector) -> Unit,
    openExternal: (String) -> Unit,
) {
    var disconnecting by remember { mutableStateOf<AccountConnector?>(null) }
    disconnecting?.let { connector ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text(stringResource(R.string.connector_disconnect_title, connector.label)) },
            text = { Text(stringResource(R.string.connector_disconnect_body, connector.label)) },
            confirmButton = {
                Button(
                    onClick = {
                        disconnecting = null
                        onDisconnect(connector)
                    },
                ) {
                    Text(stringResource(R.string.connector_disconnect_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnecting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connector_section_title), color = SettingsText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(
                        if (loading) {
                            stringResource(R.string.connector_refreshing)
                        } else {
                            stringResource(R.string.connector_refresh)
                        },
                    )
                }
            }
            if (connectors.isEmpty()) {
                Text(
                    if (loading) {
                        stringResource(R.string.connector_loading)
                    } else {
                        stringResource(R.string.connector_empty_body)
                    },
                    color = SettingsTextMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                connectors.take(6).forEach { connector ->
                    ConnectorRow(
                        connector = connector,
                        busy = actionStore == connector.store,
                        onConnect = { onConnect(connector) },
                        onDisconnect = { disconnecting = connector },
                    )
                }
            }
            OutlinedButton(onClick = { openExternal(GFN_ACCOUNT_HELP_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connector_help), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectorRow(
    connector: AccountConnector,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val actionEnabled = !busy && (connector.isLinked || connector.supported)
    val badge = launcherBadgeForStoreKey(splitGameStoreKeys(connector.store).firstOrNull())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = actionEnabled) {
                if (connector.isLinked) onDisconnect() else onConnect()
            },
    ) {
        ConnectorStoreIcon(badge)
        Column(Modifier.weight(1f)) {
            Text(connector.label, color = SettingsText, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(connectorStatusText(connector), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (connector.isLinked) {
            OutlinedButton(onClick = onDisconnect, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    if (busy) {
                        stringResource(R.string.connector_removing)
                    } else {
                        stringResource(R.string.connector_disconnect_button)
                    },
                )
            }
        } else {
            Button(onClick = onConnect, enabled = connector.supported && !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    if (busy) {
                        stringResource(R.string.connector_opening)
                    } else {
                        stringResource(R.string.connector_connect)
                    },
                )
            }
        }
    }
}

@Composable
internal fun ConnectorStoreIcon(badge: LauncherBadge) {
    Surface(
        modifier = Modifier
            .size(34.dp)
            .semantics { contentDescription = "${badge.name} store" },
        shape = RoundedCornerShape(10.dp),
        color = badge.background.copy(alpha = 0.88f),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(badge.iconRes),
                contentDescription = null,
                tint = badge.foreground,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

private fun AuthSession.toSavedAccount(): SavedAccount =
    SavedAccount(
        userId = user.userId,
        displayName = user.displayName,
        email = user.email,
        avatarUrl = user.avatarUrl,
        membershipTier = user.membershipTier,
        providerCode = provider.code,
    )

private fun accountProviderOptions(providers: List<LoginProvider>, selectedProvider: LoginProvider): List<LoginProvider> =
    (providers + selectedProvider)
        .distinctBy { it.providerIdentityKey() }
        .ifEmpty { listOf(selectedProvider) }

private fun List<LoginProvider>.preferredProvider(provider: LoginProvider): LoginProvider =
    firstOrNull { it.sameProvider(provider) }
        ?: firstOrNull()
        ?: provider

private fun LoginProvider.sameProvider(other: LoginProvider): Boolean =
    providerIdentityKey() == other.providerIdentityKey()

private fun LoginProvider.providerIdentityKey(): String =
    idpId.ifBlank { code }.lowercase(Locale.US)

private const val GFN_STORAGE_MANAGEMENT_URL = "https://gfn.link/cloudstorage"
private const val GFN_STORAGE_RESET_URL = "https://gfn.link/resetstorage"
private const val GFN_ADD_STORAGE_URL = "https://gfn.link/addstorage"
private const val GFN_ACCOUNT_HELP_URL = "https://gfn.link/5399"
private const val OPENNOW_GITHUB_URL = "https://github.com/FahriAdison"

private data class DeveloperCredit(
    val name: String,
    val githubUrl: String,
)

private val DEVELOPER_CREDITS = listOf(
    DeveloperCredit("FahriAdison", "https://github.com/FahriAdison"),
)

private fun formatStorageGb(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()} GB" else "%.1f GB".format(Locale.US, value)

private fun storageUsageFraction(usedGb: Double?, totalGb: Double?): Float? {
    if (usedGb == null || totalGb == null || totalGb <= 0.0) return null
    return (usedGb / totalGb).coerceIn(0.0, 1.0).toFloat()
}

private fun formatStoragePercent(fraction: Float): String =
    "${(fraction * 100).roundToInt().coerceIn(0, 100)}%"

private fun formatPlayTimeHours(value: Double): String =
    if (value >= 10.0 || value % 1.0 == 0.0) {
        "${value.roundToInt()}h"
    } else {
        "%.1fh".format(Locale.US, value)
    }

private fun formatPlayTimePercent(fraction: Float): String =
    "${(fraction * 100).roundToInt().coerceIn(0, 100)}%"

@Composable
private fun connectorStatusText(connector: AccountConnector): String {
    if (!connector.isLinked) {
        return if (connector.required) {
            stringResource(R.string.connector_required)
        } else {
            stringResource(R.string.connector_available)
        }
    }
    val identity = connector.userDisplayName?.takeIf { it.isNotBlank() }
        ?: connector.userIdentifier?.takeIf { it.isNotBlank() }
    val sync = when {
        connector.syncedGameCount != null -> stringResource(R.string.connector_synced_games, connector.syncedGameCount!!)
        !connector.syncState.isNullOrBlank() -> connector.syncState.replace('_', ' ').lowercase(Locale.US)
            .replaceFirstChar { it.titlecase(Locale.US) }
        else -> null
    }
    return listOfNotNull(identity, sync).joinToString(" - ").ifBlank { stringResource(R.string.connector_connected) }
}

@Composable
internal fun CodecDiagnosticsPanel(report: RuntimeCodecReport?) {
    if (report == null) {
        Text(stringResource(R.string.settings_codec_diagnostics_unavailable), color = SettingsTextMuted)
        return
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(report) { mutableStateOf(false) }
    val safeDecoders = report.capabilities.count { it.streamingRealtimeSafe() }
    val codecDiagnosticsHeader = stringResource(R.string.codec_diagnostics_header)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(formatCodecDiagnosticReport(codecDiagnosticsHeader, report)))
                copied = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (copied) {
                    stringResource(R.string.settings_codec_diagnostics_copied)
                } else {
                    stringResource(R.string.settings_codec_diagnostics_copy)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CodecSummaryChip(
                "${safeDecoders}/${report.capabilities.size}",
                stringResource(R.string.codec_chip_realtime_decoders),
            )
            CodecSummaryChip(
                if (report.lowPowerGpuProfile) {
                    stringResource(R.string.codec_profile_low_power)
                } else {
                    stringResource(R.string.codec_profile_standard)
                },
                stringResource(R.string.codec_chip_device_profile),
            )
            CodecSummaryChip(
                if (report.androidTvProfile) {
                    stringResource(R.string.codec_shell_tv)
                } else {
                    stringResource(R.string.codec_shell_mobile)
                },
                stringResource(R.string.codec_chip_shell),
            )
        }
        report.capabilities.forEach { capability ->
            CodecCapabilityRow(capability)
        }
        Text(
            report.nativeRuntimeSummary.replace("{", "").replace("}", "").replace("\"", ""),
            color = SettingsTextMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatCodecDiagnosticReport(header: String, report: RuntimeCodecReport): String = buildString {
    appendLine(header)
    appendLine("nativeRuntimeSummary=${report.nativeRuntimeSummary}")
    appendLine("androidTvProfile=${report.androidTvProfile}")
    appendLine("lowPowerGpuProfile=${report.lowPowerGpuProfile}")
    appendLine("constrainedRuntimeProfile=${report.constrainedRuntimeProfile}")
    report.capabilities.forEach { capability ->
        appendLine()
        appendLine("codec=${capability.codec}")
        appendLine("decoderAvailable=${capability.decoderAvailable}")
        appendLine("decoderName=${capability.decoderName ?: "none"}")
        appendLine("hardwareDecoder=${capability.hardwareDecoder}")
        appendLine("realtimeSafe=${capability.realtimeSafe}")
        appendLine("nativeDecoderAvailable=${capability.nativeDecoderAvailable ?: "unknown"}")
        appendLine("webRtcDecoderAvailable=${capability.webRtcDecoderAvailable ?: "unknown"}")
        appendLine("webRtcDecoderName=${capability.webRtcDecoderName ?: "none"}")
        appendLine("webRtcHardwareDecoderAvailable=${capability.webRtcHardwareDecoderAvailable ?: "unknown"}")
        appendLine("webRtcProfiles=${capability.webRtcCodecProfiles.joinToString(", ").ifBlank { "none" }}")
        appendLine("encoderAvailable=${capability.encoderAvailable}")
        appendLine("encoderName=${capability.encoderName ?: "none"}")
        appendLine("hardwareEncoder=${capability.hardwareEncoder}")
    }
}

@Composable
private fun RowScope.CodecSummaryChip(value: String, label: String) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(value, color = SettingsText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, color = SettingsTextMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CodecCapabilityRow(capability: CodecCapability) {
    val streamingReady = capability.streamingDecoderAvailable()
    val healthy = capability.streamingRealtimeSafe()
    val status = when {
        healthy -> stringResource(R.string.codec_status_ready)
        streamingReady -> stringResource(R.string.codec_status_webrtc)
        capability.decoderAvailable -> stringResource(R.string.codec_status_platform)
        else -> stringResource(R.string.codec_status_unavailable)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(capability.codec.name, color = SettingsText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    status,
                    color = if (healthy) MaterialTheme.colorScheme.primary else Color(0xffffc266),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                stringResource(R.string.codec_webrtc_decoder, capability.streamingDecoderName() ?: "none"),
                color = SettingsTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.codec_hardware_decode,
                    yesNo(capability.streamingHardwareDecoderAvailable()),
                    capability.nativeDecoderAvailable?.toString() ?: "unknown",
                    capability.decoderName ?: "none",
                ),
                color = SettingsTextMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String =
    if (value) stringResource(R.string.codec_yes) else stringResource(R.string.codec_no)

internal val StreamStatsStyle.label: String
    get() = when (this) {
        StreamStatsStyle.Compact -> "Single line"
        StreamStatsStyle.Detailed -> "Multiline"
    }

internal fun StreamStatsStyle.next(): StreamStatsStyle =
    when (this) {
        StreamStatsStyle.Compact -> StreamStatsStyle.Detailed
        StreamStatsStyle.Detailed -> StreamStatsStyle.Compact
    }

internal val StreamStatsPosition.label: String
    get() = when (this) {
        StreamStatsPosition.Left -> "Left"
        StreamStatsPosition.Center -> "Center"
        StreamStatsPosition.Right -> "Right"
    }

internal fun StreamStatsPosition.next(): StreamStatsPosition =
    when (this) {
        StreamStatsPosition.Left -> StreamStatsPosition.Center
        StreamStatsPosition.Center -> StreamStatsPosition.Right
        StreamStatsPosition.Right -> StreamStatsPosition.Left
    }

@Composable
internal fun AppVersionPanel() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsPanelAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.about_app_title), color = SettingsText, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.about_build, BuildConfig.VERSION_CODE), color = SettingsTextMuted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun OpenNowGitHubPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsPanelAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.about_repo_title), color = SettingsText, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.about_repo_owner), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { openExternalUrlOrCopy(context, clipboard, OPENNOW_GITHUB_URL, context.getString(R.string.about_github_copied)) }) {
            Text(stringResource(R.string.about_github_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun DeveloperPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DEVELOPER_CREDITS.forEach { developer ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SettingsPanelAlt)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    UrlImage("${developer.githubUrl}.png?size=160", Modifier.fillMaxSize().clip(CircleShape))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(developer.name, color = SettingsText, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.about_developer_role), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { openExternalUrlOrCopy(context, clipboard, developer.githubUrl, context.getString(R.string.about_github_copied)) }) {
                    Text(stringResource(R.string.about_github_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun ThanksPanel() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Text(
        stringResource(R.string.settings_thanks_body),
        color = SettingsTextMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SettingsPanelAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_thanks_darkevilpt), color = SettingsText, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_thanks_darkevilpt_note), color = SettingsTextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(
        onClick = {
            openExternalUrlOrCopy(context, clipboard, DONATE_URL, context.getString(R.string.settings_donate_link_copied))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_donate), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun openExternalUrlOrCopy(
    context: android.content.Context,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    url: String,
    copiedMessage: String,
) {
    if (!openExternalUrl(context, url)) {
        clipboard.setText(AnnotatedString(url))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun DebugLogsPanel(state: OpenNowUiState, viewModel: OpenNowViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var pendingLogText by remember { mutableStateOf("") }
    val couldNotSaveLogs = stringResource(R.string.logs_save_failed)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(pendingLogText.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open log file")
        }.onSuccess {
            saved = true
            saveError = null
        }.onFailure { error ->
            saveError = error.message ?: couldNotSaveLogs
        }
    }
    Text(
        stringResource(R.string.logs_export_description),
        color = SettingsTextMuted,
    )
    if (state.androidTvProfile) {
        Button(
            onClick = viewModel::uploadDiagnosticShare,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.logs_upload_qr), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            stringResource(R.string.logs_upload_note),
            color = SettingsTextMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(viewModel.sanitizedDebugLogText()))
                    copied = true
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (copied) stringResource(R.string.logs_copied) else stringResource(R.string.logs_copy),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = {
                    pendingLogText = viewModel.sanitizedDebugLogText()
                    saved = false
                    saveError = null
                    saveLauncher.launch(viewModel.debugLogFileName())
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (saved) stringResource(R.string.logs_exported) else stringResource(R.string.logs_export),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    state.error?.let { error ->
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(error))
                copied = true
            },
        ) {
            Text(stringResource(R.string.logs_copy_error))
        }
    }
    saveError?.let {
        Text(it, color = Color(0xffff9f9f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun rememberDeviceHasBattery(): Boolean {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) { deviceHasBattery(appContext) }
}

internal fun shouldShowBatteryOptimization(explicitBatteryPresent: Boolean?): Boolean =
    explicitBatteryPresent != false

private fun deviceHasBattery(context: Context): Boolean {
    val batteryStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    } else {
        @Suppress("DEPRECATION")
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    val explicitBatteryPresent = batteryStatus
        ?.takeIf { it.hasExtra(BatteryManager.EXTRA_PRESENT) }
        ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    return shouldShowBatteryOptimization(explicitBatteryPresent)
}

@Composable
internal fun BatteryOptimizationPanel() {
    val context = LocalContext.current
    var isIgnoring by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        while (true) {
            isIgnoring = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            delay(1000L)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.battery_description),
            style = MaterialTheme.typography.bodyMedium,
            color = SettingsTextMuted
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.battery_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = if (isIgnoring) {
                        stringResource(R.string.battery_unlimited)
                    } else {
                        stringResource(R.string.battery_optimized)
                    },
                    color = if (isIgnoring) Color(0xff81c784) else Color(0xffffb74d),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!isIgnoring) {
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    }
                ) {
                    Text(stringResource(R.string.battery_allow))
                }
            } else {
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (_: Exception) {}
                    }
                ) {
                    Text(stringResource(R.string.nav_settings))
                }
            }
        }
    }
}
