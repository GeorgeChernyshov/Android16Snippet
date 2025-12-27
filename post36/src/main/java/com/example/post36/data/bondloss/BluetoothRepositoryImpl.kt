package com.example.post36.data.bondloss

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.example.post36.domain.bondloss.BluetoothEvent
import com.example.post36.domain.bondloss.BluetoothRepository
import com.example.post36.domain.bondloss.BluetoothState
import com.example.post36.domain.bondloss.ConnectionStatus
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.collections.plus

class BluetoothRepositoryImpl(
    private val context: Context
) : BluetoothRepository {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var bluetoothAdapterObserver: Job? = null
    var job: Job? = null
    var currentSocket: BluetoothSocket? = null

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val _state = MutableStateFlow(BluetoothStateImpl())
    override val state = _state
        .mapNotNull {
            try {
                BluetoothState(
                    discoveredDevices = it.discoveredDevices.map { device ->
                        device.toBluetoothDeviceModel()
                    },
                    pairedDevices = it.pairedDevices.map { device ->
                        device.toBluetoothDeviceModel()
                    },
                    connectionStatus = it.connectionStatus,
                    isDiscovering = it.isDiscovering
                )
            }
            catch (_: SecurityException) { null }
        }
        .stateIn(
            scope = coroutineScope,
            started = WhileSubscribed(5000),
            initialValue = BluetoothState()
        )

    private val _events = MutableSharedFlow<BluetoothEvent>()
    override val events = _events.asSharedFlow()

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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


    val deviceReceiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

            handleBluetoothSearchAction(
                intent = intent ?: return,
                deviceFromIntent = deviceFromIntent
            )
        }
    }

    init {
        if (hasRequiredPermission(Manifest.permission.BLUETOOTH_SCAN))
            observeBluetoothAdapter()
    }

    override fun setConnectionStatus(bondState: Int) = setConnectionStatus(
        bondStateToConnectionStatus(bondState)
    )

    override fun setConnectionStatus(
        status: ConnectionStatus
    ) {
        coroutineScope.launch {
            _state.value = _state.value.copy(connectionStatus = status)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery() {
        val permissionsGranted = hasRequiredPermissions(requiredPermissions)
        val btAvailableAndEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled
        val locationEnabledForDiscovery = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } else true // Not required for API 31+ for BT scan

        if (!permissionsGranted) {
            coroutineScope.launch {
                _events.emit(
                    BluetoothEvent.MissingPermissions(
                    getMissingPermissions(requiredPermissions)
                ))
            }

            return
        }

        Log.d(TAG_BOND_LOSS, "Discovery button clicked. Pre-checks:")
        Log.d(TAG_BOND_LOSS, "  Permissions Granted: true")
        Log.d(TAG_BOND_LOSS, "  Bluetooth Available & Enabled: $btAvailableAndEnabled")
        Log.d(TAG_BOND_LOSS, "  Location Enabled (if required): $locationEnabledForDiscovery")
        Log.d(TAG_BOND_LOSS, "  Current isDiscovering state (local): ${state.value.isDiscovering}")
        Log.d(TAG_BOND_LOSS, "  BluetoothAdapter.isDiscovering (actual): ${bluetoothAdapter?.isDiscovering}")

        if (!btAvailableAndEnabled)
            return

        if (!locationEnabledForDiscovery) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

            return
        }

        if (state.value.isDiscovering)
            return

        _state.value = _state.value.copy(
            discoveredDevices = emptyList()
        )

        val started = bluetoothAdapter.startDiscovery()
        Log.d(TAG_BOND_LOSS, "Attempting to start discovery. Success: $started")
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        if (hasRequiredPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN))) {
            val cancelled = bluetoothAdapter?.cancelDiscovery()
            Log.d(TAG_BOND_LOSS, "Attempting to cancel discovery. Success: $cancelled")
        }
    }

    override fun refreshPairedDevices() {
        val permissionsToRequest = getMissingPermissions(requiredPermissions)

        if (permissionsToRequest.isNotEmpty()) {
            coroutineScope.launch {
                _events.emit(
                    BluetoothEvent.MissingPermissions(permissionsToRequest)
                )
            }

            return
        } else {
            Log.d(TAG_BOND_LOSS, "Initial setup: Permissions already granted.")
            if (bluetoothAdapter == null) {
                Log.e(TAG_BOND_LOSS, "Initial setup: BluetoothAdapter is null.")

                return
            }

            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG_BOND_LOSS, "Initial setup: Bluetooth is not enabled.")

                return
            }
        }

        try {
            val currentlyPaired = bluetoothAdapter.bondedDevices
                ?.toList()
                ?: emptyList()

            _state.value = _state.value.copy(
                pairedDevices = currentlyPaired
            )
        } catch (se: SecurityException) {
            Log.e(
                TAG_BOND_LOSS,
                "SecurityException getting bonded devices: ${se.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun pairDevice(deviceName: String) {
        val device = getBluetoothDevice(deviceName)

        if (device == null)
            return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasRequiredPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                val event = BluetoothEvent.MissingPermissions(
                    listOf(Manifest.permission.BLUETOOTH_CONNECT)
                )

                coroutineScope.launch {
                    _events.emit(event)
                }

                return
            }
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled)
            return

        // Initiate bonding!
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            val initiated = device.createBond() // Requires BLUETOOTH_CONNECT
            if (initiated) {
                Log.d(
                    TAG_BOND_LOSS,
                    "Pairing initiated for ${device.address}"
                )
            } else {
                Log.e(
                    TAG_BOND_LOSS,
                    "createBond() returned false for ${device.address}"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToDevice(deviceName: String) {
        val deviceToConnect = getBluetoothDevice(deviceName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasRequiredPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                val event = BluetoothEvent.MissingPermissions(
                    listOf(Manifest.permission.BLUETOOTH_CONNECT)
                )

                coroutineScope.launch {
                    _events.emit(event)
                }

                return
            }
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled)
            return

        if (deviceToConnect == null)
            return

        if (deviceToConnect.bondState != BluetoothDevice.BOND_BONDED)
            return

        coroutineScope.launch {
            _state.value = _state.value.copy(
                connectionStatus = ConnectionStatus.CONNECTING
            )
        }

        job = connectToDevice(
            coroutineScope,
            deviceToConnect
        ) { status, socket, data ->
            coroutineScope.launch {
                _state.value = _state.value.copy(
                    connectionStatus = status
                )
            }

            currentSocket = socket
        }
    }

    override fun disconnect() {
        coroutineScope.launch {
            job?.cancel()
            currentSocket?.close()
        }
    }

    override fun registerReceiver() {
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
            Log.d(TAG_BOND_LOSS, "Bluetooth BroadcastReceiver registered.")
        } catch (e: Exception) {
            Log.e(
                TAG_BOND_LOSS,
                "Failed to register BroadcastReceiver: ${e.message}",
                e
            )
        }
    }

    override fun unregisterReceiver() {
        try {
            context.unregisterReceiver(deviceReceiver)
            Log.d(TAG_BOND_LOSS, "Bluetooth BroadcastReceiver unregistered.")
        } catch (e: Exception) {
            Log.e(
                TAG_BOND_LOSS,
                "Failed to unregister BroadcastReceiver: ${e.message}",
                e
            )
        }

        stopDiscovery()
        disconnect()
    }

    private fun setDiscoveredDevices(
        devices: List<BluetoothDevice>
    ) = coroutineScope.launch {
        _state.value = _state.value.copy(discoveredDevices = devices)
    }

    private fun addDiscoveredDevice(
        device: BluetoothDevice
    ) = coroutineScope.launch {
        _state.value = _state.value.copy(
            discoveredDevices = _state.value.discoveredDevices + device
        )
    }

    private fun setDiscovering(
        discovering: Boolean
    ) = coroutineScope.launch {
        _state.value = _state.value.copy(isDiscovering = discovering)
    }

    @SuppressLint("MissingPermission")
    private fun observeBluetoothAdapter() {
        bluetoothAdapterObserver?.cancel()
        bluetoothAdapterObserver = coroutineScope.launch {
            snapshotFlow({
                bluetoothAdapter?.isDiscovering
            }).collect {
                _state.value = _state.value.copy(
                    isDiscovering = (it == true)
                )
            }
        }
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
            var socket: BluetoothSocket?

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
                        withContext(Dispatchers.Main) {
                            onResult(ConnectionStatus.CONNECTED, socket, readMessage) // Update UI with data
                        }
                    } catch (e: IOException) {
                        Log.e(TAG_BOND_LOSS, "Read/write error during communication with ${device.address}: ${e.message}")
                        if (this.isActive) { // Check if the job is still active, means error happened unexpectedly
                            withContext(Dispatchers.Main) {
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

    @SuppressLint("MissingPermission")
    private fun handleBluetoothSearchAction(
        intent: Intent,
        deviceFromIntent: BluetoothDevice?
    ) {
        val action = intent.action

        when (action) {
            BluetoothDevice.ACTION_FOUND -> {
                deviceFromIntent?.let { device ->
                    val deviceName = device.name ?: "Unknown Device"
                    Log.d(TAG_BOND_LOSS, "ACTION_FOUND: $deviceName (${device.address})")

                    handleDeviceFound(device)
                }
            }

            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                Log.d(TAG_BOND_LOSS, "Discovery Started")
                setDiscoveredDevices(emptyList())
                setDiscovering(true)
            }

            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                Log.d(TAG_BOND_LOSS, "Discovery Finished")
                setDiscovering(false)
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR
                )

                val previousBondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.ERROR
                )

                val deviceName = try {
                    deviceFromIntent?.name ?: deviceFromIntent?.address
                } catch (e: SecurityException) {
                    deviceFromIntent?.address
                }

                val logMsg = "ACTION_BOND_STATE_CHANGED for $deviceName: " +
                        "${bondStateToConnectionStatus(previousBondState)} -> " +
                        bondStateToConnectionStatus(bondState)

                Log.d(TAG_BOND_LOSS, logMsg)

                emitEvent(
                    BluetoothEvent.BondStateChanged(
                        deviceAddress = deviceFromIntent?.address ?: return,
                        bondState = bondState,
                        previousBondState = previousBondState
                    )
                )
            }

            BluetoothDevice.ACTION_KEY_MISSING -> {
                val deviceName = try {
                    deviceFromIntent?.name ?: deviceFromIntent?.address
                } catch (e: SecurityException) {
                    deviceFromIntent?.address
                }

                val logMsg = "ACTION_KEY_MISSING received for $deviceName. System should show dialog. Local bond retained."
                Log.d(TAG_BOND_LOSS, logMsg)

                emitEvent(
                    BluetoothEvent.KeyMissing(
                        deviceFromIntent?.address ?: return
                    )
                )
            }

            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val connectionState = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.ERROR
                )

                if (connectionState == BluetoothAdapter.STATE_DISCONNECTED) {
                    val connectionStatus = _state.value.connectionStatus
                    if (connectionStatus != ConnectionStatus.BOND_LOST &&
                        connectionStatus != ConnectionStatus.KEY_MISSING
                    ) {

                        setConnectionStatus(ConnectionStatus.DISCONNECTED)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDeviceFound(device: BluetoothDevice) {
        if (hasRequiredPermission(
            permission = Manifest.permission.BLUETOOTH_CONNECT
        )) {
            try {
                val deviceName = device.name ?: "Unknown Device"
                Log.d(TAG_BOND_LOSS, "Device Found: $deviceName (${device.address})")
                if (_state.value
                    .discoveredDevices
                    .none { it.address == device.address }
                ) {
                    addDiscoveredDevice(device)
                }
            } catch (se: SecurityException) {
                Log.e(TAG_BOND_LOSS, "SecurityException on ACTION_FOUND: ${se.message}")
            }
        }
    }

    private fun bondStateToConnectionStatus(bondState: Int) = when (bondState) {
        BluetoothDevice.BOND_NONE -> ConnectionStatus.BOND_NONE
        BluetoothDevice.BOND_BONDING -> ConnectionStatus.BOND_BONDING
        BluetoothDevice.BOND_BONDED -> ConnectionStatus.BOND_BONDED
        BluetoothDevice.ERROR -> ConnectionStatus.ERROR
        else -> ConnectionStatus.UNKNOWN
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothDevice(deviceName: String) =
        (_state.value.pairedDevices + _state.value.discoveredDevices)
            .firstOrNull { it.name == deviceName }

    private fun hasRequiredPermission(
        permission: String
    ) = hasRequiredPermissions(arrayOf(permission))

    private fun hasRequiredPermissions(
        permissions: Array<String>
    ) = getMissingPermissions(permissions).isEmpty()

    private fun getMissingPermissions(
        permissions: Array<String>
    ) = permissions.filter {
        ContextCompat.checkSelfPermission(
            context,
            it
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun emitEvent(event: BluetoothEvent) = coroutineScope.launch {
        _events.emit(event)
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}