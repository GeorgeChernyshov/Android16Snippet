package com.example.post36.screen

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.post36.R
import com.example.post36.components.AppBar
import com.example.post36.theme.Android16SnippetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
@Composable
fun BondLossHandlingScreen() {

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val bluetoothManager = remember { context.getSystemService(BluetoothManager::class.java) }
    val bluetoothAdapter: BluetoothAdapter? = remember { bluetoothManager?.adapter }
    val discoveredDevices = remember { mutableStateListOf<BluetoothDevice>() }
    val pairedDevices = remember { mutableStateListOf<BluetoothDevice>() }

    var connectionStatus by remember { mutableStateOf(ConnectionStatus.NOT_CONNECTED) }
    var job by remember { mutableStateOf<Job?>(null) }
    var currentSocket by remember { mutableStateOf<BluetoothSocket?>(null) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            mutableListOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries
            .all { it.value }

        if (allGranted && bluetoothAdapter?.isEnabled == true)
            getPairedDevices(bluetoothAdapter, pairedDevices)
    }

    @SuppressLint("MissingPermission")
    val deviceReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

                handleBluetoothSearchAction(
                    intent = intent ?: return,
                    context = context,
                    deviceFromIntent = deviceFromIntent,
                    discoveredDevices = discoveredDevices,
                    pairedDevices = pairedDevices,
                    selectedDevice = selectedDevice,
                    setDiscovering = { isDiscovering = it },
                    bluetoothAdapter = bluetoothAdapter ?: return,
                    connectionStatus = connectionStatus,
                    setConnectionStatus = { connectionStatus = it },
                    onDisconnect = {
                        disconnect(coroutineScope, currentSocket, job)
                        selectedDevice = null
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(
                context,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Log.d(TAG_BOND_LOSS, "Initial setup: Permissions already granted.")
            if (bluetoothAdapter == null) {
                Log.e(TAG_BOND_LOSS, "Initial setup: BluetoothAdapter is null.")
            } else if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG_BOND_LOSS, "Initial setup: Bluetooth is not enabled.")
            } else {
                getPairedDevices(bluetoothAdapter, pairedDevices) // Get paired devices if permissions and BT are ready
            }
        }
    }

    DisposableEffect(context, bluetoothAdapter) {
        if (bluetoothAdapter == null) return@DisposableEffect onDispose {}

        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                addAction(BluetoothDevice.ACTION_KEY_MISSING)

            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }

        try {
            context.registerReceiver(deviceReceiver, intentFilter)
            Log.d(TAG_BOND_LOSS, "BroadcastReceiver registered with filter: $intentFilter")
        } catch (e: Exception) {
            Log.e(TAG_BOND_LOSS, "Failed to register BroadcastReceiver: ${e.message}", e)
        }

        onDispose {
            try {
                context.unregisterReceiver(deviceReceiver)
                Log.d(TAG_BOND_LOSS, "BroadcastReceiver unregistered.")
            } catch (e: Exception) {
                Log.e(TAG_BOND_LOSS, "Failed to unregister BroadcastReceiver: ${e.message}", e)
            }

            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
                Log.d(TAG_BOND_LOSS, "Discovery cancelled on dispose.")
            }

            disconnect(
                coroutineScope,
                currentSocket,
                job
            )

            Log.d(TAG_BOND_LOSS, "Cleanup on dispose complete.")
        }
    }

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
            val selectedDeviceName = try{
                selectedDevice?.name ?: "Unknown"
            } catch (e: SecurityException) {
                "Unknown"
            }

            Text("Reminder to finish this later")

            Text(
                text = selectedDevice?.let {
                    "Selected: $selectedDeviceName (${it.address})"
                } ?: "No device selected"
            )

            Text("Status: $connectionStatus")

            Button(
                onClick = {
                    val permissionsGranted = hasRequiredPermissions(context, requiredPermissions)
                    val btAvailableAndEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled
                    val locationEnabledForDiscovery = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    } else true // Not required for API 31+ for BT scan

                    Log.d(TAG_BOND_LOSS, "Discovery button clicked. Pre-checks:")
                    Log.d(TAG_BOND_LOSS, "  Permissions Granted: $permissionsGranted")
                    Log.d(TAG_BOND_LOSS, "  Bluetooth Available & Enabled: $btAvailableAndEnabled")
                    Log.d(TAG_BOND_LOSS, "  Location Enabled (if required): $locationEnabledForDiscovery")
                    Log.d(TAG_BOND_LOSS, "  Current isDiscovering state (local): $isDiscovering")
                    Log.d(TAG_BOND_LOSS, "  BluetoothAdapter.isDiscovering (actual): ${bluetoothAdapter?.isDiscovering}")


                    if (!permissionsGranted) {
                        permissionLauncher.launch(requiredPermissions)
                        return@Button
                    }

                    if (!btAvailableAndEnabled) {
                        // Consider prompting user to enable Bluetooth:
                        // val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        // context.startActivity(enableBtIntent)
                        return@Button
                    }

                    if (!locationEnabledForDiscovery) {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        return@Button
                    }

                    if (bluetoothAdapter.isDiscovering) { // Use actual adapter state
                        val cancelled = bluetoothAdapter.cancelDiscovery()
                        Log.d(TAG_BOND_LOSS, "Attempting to cancel discovery. Success: $cancelled")
                    } else {
                        discoveredDevices.clear() // Clear previous results
                        val started = bluetoothAdapter.startDiscovery()
                        Log.d(TAG_BOND_LOSS, "Attempting to start discovery. Success: $started")
                    }
                }
            ) {
                Text(if (isDiscovering) "Stop Discovery" else "Start Discovery")
            }

            DeviceConnectionBlock(
                pairedDevices = pairedDevices,
                discoveredDevices = discoveredDevices,
                onDeviceClick = { device ->
                    selectedDevice = device
                    if (isDiscovering && bluetoothAdapter?.isDiscovering == true)
                        bluetoothAdapter.cancelDiscovery()
                },
                areButtonsEnabled = selectedDevice != null && (connectionStatus != ConnectionStatus.CONNECTING),
                onPairClick = {
                    if (selectedDevice == null) {
                        return@DeviceConnectionBlock
                    }
                    if (!hasRequiredPermissions(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                        return@DeviceConnectionBlock
                    }
                    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                        return@DeviceConnectionBlock
                    }

                    // Initiate bonding!
                    if (selectedDevice?.bondState == BluetoothDevice.BOND_NONE) {
                        val initiated = selectedDevice!!.createBond() // Requires BLUETOOTH_CONNECT
                        if (initiated) {
                            Log.d(
                                TAG_BOND_LOSS,
                                "Pairing initiated for ${selectedDevice?.address}"
                            )
                        } else {
                            Log.e(
                                TAG_BOND_LOSS,
                                "createBond() returned false for ${selectedDevice?.address}"
                            )
                        }
                    }
                },
                onConnectClick = {
                    val deviceToConnect = selectedDevice
                    if (!hasRequiredPermissions(context, arrayOf(Manifest.permission.BLUETOOTH_CONNECT))) { // Just connect for this one
                        permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                        return@DeviceConnectionBlock
                    }

                    if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled)
                        return@DeviceConnectionBlock

                    if (deviceToConnect == null)
                        return@DeviceConnectionBlock

                    if (deviceToConnect.bondState != BluetoothDevice.BOND_BONDED)
                        return@DeviceConnectionBlock

                    connectionStatus = ConnectionStatus.CONNECTING
                    job = connectToDevice(coroutineScope, deviceToConnect) { status, socket, data ->
                        connectionStatus = status
                        currentSocket = socket
                    }
                }
            )
        }
    }
}

@Composable
fun DeviceConnectionBlock(
    pairedDevices: List<BluetoothDevice>,
    discoveredDevices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit,
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
    devices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    LazyColumn(Modifier.fillMaxWidth()) {
        items(devices, key = { it.address })  { device ->
            val deviceName = try {
                device.name ?: "Unknown Device"
            } catch (e: SecurityException) {
                "Name requires BLUETOOTH_CONNECT"
            }

            Text(
                text = deviceName,
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

@SuppressLint("MissingPermission")
private fun getPairedDevices(
    bluetoothAdapter: BluetoothAdapter,
    pairedDeviceList: MutableList<BluetoothDevice>
) {
    try {
        val currentlyPaired = bluetoothAdapter.bondedDevices
        pairedDeviceList.clear()
        pairedDeviceList.addAll(currentlyPaired)
    } catch (se: SecurityException) {
        Log.e(
            TAG_BOND_LOSS,
            "SecurityException getting bonded devices: ${se.message}"
        )
    }
}

@SuppressLint("MissingPermission")
private fun handleBluetoothSearchAction(
    intent: Intent,
    context: Context,
    deviceFromIntent: BluetoothDevice?,
    discoveredDevices: MutableList<BluetoothDevice>,
    pairedDevices: MutableList<BluetoothDevice>,
    selectedDevice: BluetoothDevice?,
    setDiscovering: (Boolean) -> Unit,
    bluetoothAdapter: BluetoothAdapter,
    connectionStatus: ConnectionStatus,
    setConnectionStatus: (ConnectionStatus) -> Unit,
    onDisconnect: () -> Unit
) {
    val action = intent.action

    when (action) {
        BluetoothDevice.ACTION_FOUND -> {
            deviceFromIntent?.let { device ->
                val deviceName = device.name ?: "Unknown Device"
                Log.d(TAG_BOND_LOSS, "ACTION_FOUND: $deviceName (${device.address})")

                handleDeviceFound(
                    context = context,
                    device = device,
                    discoveredDevices = discoveredDevices
                )
            }
        }

        BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
            Log.d(TAG_BOND_LOSS, "Discovery Started")
            discoveredDevices.clear()
            setDiscovering(true)
        }

        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            Log.d(TAG_BOND_LOSS, "Discovery Finished")
            setDiscovering(false)
        }

        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
            val needToSwitchAddress = selectedDevice?.address != null
                    && deviceFromIntent?.address != selectedDevice.address

            if (deviceFromIntent == null || needToSwitchAddress) {
                getPairedDevices(bluetoothAdapter, pairedDevices)

                return
            }

            val deviceName = try {
                deviceFromIntent.name ?: deviceFromIntent.address
            } catch (e: SecurityException) {
                deviceFromIntent.address
            }

            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.ERROR
            )

            val previousBondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                BluetoothDevice.ERROR
            )

            val logMsg = "ACTION_BOND_STATE_CHANGED for $deviceName: " +
                    "${bondStateToConnectionStatus(previousBondState)} -> " +
                    bondStateToConnectionStatus(bondState)

            Log.d(TAG_BOND_LOSS, logMsg)

            setConnectionStatus(bondStateToConnectionStatus(bondState))

            if (
                bondState == BluetoothDevice.BOND_NONE &&
                deviceFromIntent.address == selectedDevice?.address
            ) {
                setConnectionStatus(ConnectionStatus.BOND_LOST)
                onDisconnect()
            }

            getPairedDevices(bluetoothAdapter, pairedDevices)
        }

        BluetoothDevice.ACTION_KEY_MISSING -> {
            if (
                deviceFromIntent == null ||
                deviceFromIntent.address != selectedDevice?.address
            ) { return }

            val deviceName = try {
                deviceFromIntent.name ?: deviceFromIntent.address
            } catch (e: SecurityException) {
                deviceFromIntent.address
            }

            val logMsg = "ACTION_KEY_MISSING received for $deviceName. System should show dialog. Local bond retained."
            Log.d(TAG_BOND_LOSS, logMsg)
            setConnectionStatus(ConnectionStatus.KEY_MISSING)
            onDisconnect()
        }

        BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
            if (
                deviceFromIntent == null ||
                deviceFromIntent.address != selectedDevice?.address
            ) {
                return
            }

            val connectionState = intent.getIntExtra(
                BluetoothAdapter.EXTRA_CONNECTION_STATE,
                BluetoothAdapter.ERROR
            )

            if (connectionState == BluetoothAdapter.STATE_DISCONNECTED) {
                if (connectionStatus != ConnectionStatus.BOND_LOST && connectionStatus != ConnectionStatus.KEY_MISSING) {
                    setConnectionStatus(ConnectionStatus.DISCONNECTED)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun handleDeviceFound(
    context: Context,
    device: BluetoothDevice,
    discoveredDevices: MutableList<BluetoothDevice>
) {
    if (
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        try {
            val deviceName = device.name ?: "Unknown Device"
            Log.d(TAG_BOND_LOSS, "Device Found: $deviceName (${device.address})")
            if (discoveredDevices.none { it.address == device.address }) {
                discoveredDevices.add(device)
            }
        } catch (se: SecurityException) {
            Log.e(TAG_BOND_LOSS, "SecurityException on ACTION_FOUND: ${se.message}")
        }
    }
}

private fun hasRequiredPermissions(
    context: Context,
    permissions: Array<String>
) = permissions.all {
    ContextCompat.checkSelfPermission(
        context,
        it
    ) == PackageManager.PERMISSION_GRANTED
}

private fun connectToDevice(
    scope: CoroutineScope,
    device: BluetoothDevice,
    onResult: (
        status: ConnectionStatus,
        socket: BluetoothSocket?,
        data: String?
    ) -> Unit
): Job {
    return scope.launch(Dispatchers.IO) {
        var socket: BluetoothSocket? = null

        try {
            withContext(Dispatchers.Main) {
                val deviceName = try {
                    device.name ?: device.address
                } catch (e: SecurityException) {
                    device.address
                }

                onResult(
                    ConnectionStatus.CONNECTING,
                    null,
                    null
                )
            }

            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            Log.d(TAG_BOND_LOSS, "Attempting to connect to ${device.address}...")
            socket.connect()
            withContext(Dispatchers.Main) {
                onResult(
                    ConnectionStatus.CONNECTED,
                    socket,
                    null
                )
            }

            Log.d(TAG_BOND_LOSS, "Connection successful.")

            val inputStream = socket.inputStream
            val buffer = ByteArray(1024)
            var bytes: Int

            // Loop while the socket is connected and the coroutine is still active (not cancelled)
            while (socket.isConnected && this.isActive) {
                try {
                    bytes = inputStream.read(buffer) // Blocking read
                    if (bytes == -1) { // End of stream (peer disconnected gracefully)
                        Log.d(TAG_BOND_LOSS, "Input stream ended (peer disconnected gracefully) for ${device.address}.")
                        break // Exit loop, connection effectively closed
                    }
                    val readMessage = String(buffer, 0, bytes)
                    Log.d(TAG_BOND_LOSS, "Received from ${device.address}: $readMessage")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        onResult(ConnectionStatus.CONNECTED, socket, readMessage) // Update UI with data
                    }
                } catch (e: IOException) {
                    Log.e(TAG_BOND_LOSS, "Read/write error during communication with ${device.address}: ${e.message}")
                    if (this.isActive) { // Check if the job is still active, means error happened unexpectedly
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onResult(ConnectionStatus.DISCONNECTED, null, null)
                        }
                    }
                    break // Exit loop on communication error
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG_BOND_LOSS, "SecurityException during connect: ${se.message}")
            withContext(Dispatchers.Main) {
                onResult(
                    ConnectionStatus.CONNECTION_FAILED,
                    null,
                    null
                )
            }
        } catch (e: IOException) { // <-- IMPORTANT: CATCH THE IOException HERE
            // This catches the 'read failed, socket might closed or timeout' and other connection errors
            Log.e(
                TAG_BOND_LOSS,
                "IOException during connect to ${device.address}: ${e.message}"
            )

            withContext(Dispatchers.Main) {
                onResult(
                    ConnectionStatus.CONNECTION_FAILED,
                    null,
                    null
                )
            }
        }
    }
}

private fun disconnect(
    scope: CoroutineScope,
    socket: BluetoothSocket?,
    job: Job?
) {
    scope.launch {
        job?.cancel()
        socket?.close()
    }
}

private fun bondStateToConnectionStatus(bondState: Int) = when (bondState) {
    BluetoothDevice.BOND_NONE -> ConnectionStatus.BOND_NONE
    BluetoothDevice.BOND_BONDING -> ConnectionStatus.BOND_BONDING
    BluetoothDevice.BOND_BONDED -> ConnectionStatus.BOND_BONDED
    BluetoothDevice.ERROR -> ConnectionStatus.ERROR
    else -> ConnectionStatus.UNKNOWN
}

private const val TAG_BOND_LOSS = "BondLossHandling"
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

enum class ConnectionStatus(val displayName: String) {
    NOT_CONNECTED("Not Connected"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    BOND_NONE("Bond State: BOND_NONE"),
    BOND_BONDING("Bond State: BOND_BONDING"),
    BOND_BONDED("Bond State: BOND_BONDED"),
    ERROR("Bond State: ERROR"),
    BOND_LOST("Device unbonded"),
    KEY_MISSING("Key Missing. Check System Dialog."),
    DISCONNECTED("Disconnected"),
    CONNECTION_FAILED("Connection failed"),
    UNKNOWN("Bond State: UNKNOWN");
}