@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.R
import id.quezacolt.veilkeeper.data.DeviceDto
import id.quezacolt.veilkeeper.ui.components.VeilKeeperEmptyState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading
import id.quezacolt.veilkeeper.ui.theme.Spacing

/**
 * Devices & Sessions screen (plan.md Phase 4, backend GET /api/v1/devices +
 * DELETE /api/v1/devices/{id}): lists every device tied to the account,
 * marks the one making this request ([DeviceDto.isCurrent]), and lets the
 * user revoke any other device. Ported from VeilKeepers' DevicesScreen.kt
 * (structure/logic only -- this app never shows "VeilKeepers" anywhere).
 */
@Composable
fun DevicesScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    viewModel: DevicesViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsState()
    val revokingDeviceId by viewModel.revokingDeviceId.collectAsState()
    var confirmRevokeId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { viewModel.loadDevices() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            is DevicesUiState.Loading -> VeilKeeperLoading(
                label = stringResource(R.string.devices_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is DevicesUiState.Error -> VeilKeeperErrorState(
                message = current.message,
                onRetry = viewModel::loadDevices,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            is DevicesUiState.Loaded -> {
                if (current.devices.isEmpty()) {
                    VeilKeeperEmptyState(
                        icon = Icons.Outlined.DevicesOther,
                        title = stringResource(R.string.devices_empty_title),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                    ) {
                        Text(
                            text = stringResource(R.string.devices_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Spacing.md))
                        current.devices.forEach { device ->
                            DeviceRow(
                                device = device,
                                isRevoking = revokingDeviceId == device.id,
                                onRevoke = if (device.isCurrent || device.revokedAt != null) null else {
                                    { confirmRevokeId = device.id }
                                },
                            )
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }
            }
        }
    }

    val revokeId = confirmRevokeId
    if (revokeId != null) {
        val device = (state as? DevicesUiState.Loaded)?.devices?.firstOrNull { it.id == revokeId }
        AlertDialog(
            onDismissRequest = { confirmRevokeId = null },
            title = { Text(stringResource(R.string.devices_revoke_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.devices_revoke_body,
                        device?.deviceName?.ifEmpty { device.deviceIdentifier } ?: "",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.revokeDevice(revokeId)
                        confirmRevokeId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = revokingDeviceId == null,
                ) {
                    Text(stringResource(R.string.devices_revoke_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One device row: name/identifier, created date, "this device"/"revoked" badge, optional revoke action. */
@Composable
private fun DeviceRow(
    device: DeviceDto,
    isRevoking: Boolean,
    onRevoke: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isRevoked = device.revokedAt != null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = when {
            device.isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.deviceName.ifEmpty { device.deviceIdentifier },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (device.isCurrent) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.devices_this_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else if (isRevoked) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = stringResource(R.string.devices_revoked_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (device.deviceName.isNotEmpty() && device.deviceIdentifier.isNotEmpty() &&
                    device.deviceName != device.deviceIdentifier
                ) {
                    Text(
                        text = device.deviceIdentifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (device.createdAt.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.devices_created, device.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onRevoke != null) {
                if (isRevoking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = onRevoke) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = stringResource(R.string.devices_revoke_cd),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
