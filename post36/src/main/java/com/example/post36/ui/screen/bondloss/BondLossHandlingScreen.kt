package com.example.post36.ui.screen.bondloss

import android.annotation.SuppressLint
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.post36.R
import com.example.post36.ui.components.AppBar
import com.example.post36.ui.theme.Android16SnippetTheme

@SuppressLint("MissingPermission")
@Composable
fun BondLossHandlingScreen() {

    val viewModel: BondLossHandlingScreenViewModel = hiltViewModel()
    val uiState = viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries
            .all { it.value }

        if (allGranted)
            viewModel.refreshPairedDevices()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPairedDevices()
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect {
            when (it) {
                is BondLossHandlingEvent.RequestPermissions -> {
                    permissionLauncher.launch(it.permissions.toTypedArray())
                }
            }
        }
    }

    DisposableEffect(Unit) {
        viewModel.registerBluetoothReceiver()

        onDispose {
            viewModel.unregisterBluetoothReceiver()

            Log.d(TAG_BOND_LOSS, "Cleanup on dispose complete.")
        }
    }

    BondLossHandlingScreenContent(
        state = uiState.value,
        onToggleDiscovery = viewModel::toggleDiscovery,
        onDeviceClick = viewModel::selectDevice,
        onPairClick = viewModel::pairDevice,
        onConnectClick = viewModel::connectToDevice
    )
}

@Composable
fun BondLossHandlingScreenContent(
    state: BondLossHandlingScreenState,
    onToggleDiscovery: () -> Unit,
    onDeviceClick: (BluetoothDeviceUiState) -> Unit,
    onPairClick: () -> Unit,
    onConnectClick: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = { AppBar(name = stringResource(R.string.label_bond_loss_handling)) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Reminder to finish this later")
            Text(state.getSelectedDeviceDescription(context))
            Text("Status: ${state.connectionStatus}")

            Button(onClick = onToggleDiscovery) {
                Text(
                    text = if (state.isDiscovering)
                        "Stop Discovery"
                    else "Start Discovery"
                )
            }

            DeviceConnectionBlock(
                pairedDevices = state.pairedDevices,
                discoveredDevices = state.discoveredDevices,
                onDeviceClick = onDeviceClick,
                areButtonsEnabled = state.areButtonsEnabled,
                onPairClick = onPairClick,
                onConnectClick = onConnectClick
            )
        }
    }
}

@Composable
fun DeviceConnectionBlock(
    pairedDevices: List<BluetoothDeviceUiState>,
    discoveredDevices: List<BluetoothDeviceUiState>,
    onDeviceClick: (BluetoothDeviceUiState) -> Unit,
    areButtonsEnabled: Boolean,
    onPairClick: () -> Unit,
    onConnectClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Paired devices")

        DeviceList(
            devices = pairedDevices,
            onDeviceClick = onDeviceClick
        )

        Text("Discovered devices")

        DeviceList(
            devices = discoveredDevices,
            onDeviceClick = onDeviceClick
        )

        Button(
            onClick = onPairClick,
            enabled = areButtonsEnabled
        ) {
            Text("Pair Selected")
        }

        Button(
            onClick = onConnectClick,
            enabled = areButtonsEnabled
        ) {
            Text("Connect to Selected")
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceList(
    devices: List<BluetoothDeviceUiState>,
    onDeviceClick: (BluetoothDeviceUiState) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(devices, key = { it.address })  { device ->
            Text(
                text = device.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDeviceClick(device) }
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceConnectionBlockPreview() {
    Android16SnippetTheme {
        DeviceConnectionBlock(
            pairedDevices = emptyList(),
            discoveredDevices = emptyList(),
            onDeviceClick = {},
            areButtonsEnabled = false,
            onPairClick = {},
            onConnectClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DeviceListPreview() {
    Android16SnippetTheme {
        DeviceList(
            devices = listOf(),
            onDeviceClick = {}
        )
    }
}

const val TAG_BOND_LOSS = "BondLossHandling"