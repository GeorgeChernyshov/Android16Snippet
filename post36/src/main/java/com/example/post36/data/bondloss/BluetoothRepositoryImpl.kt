package com.example.post36.data.bondloss

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.post36.data.bondloss.BluetoothAdapterManager.Event
import com.example.post36.domain.bondloss.BluetoothEvent
import com.example.post36.domain.bondloss.BluetoothRepository
import com.example.post36.domain.bondloss.BluetoothState
import com.example.post36.domain.bondloss.ConnectionStatus
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.collections.plus

class BluetoothRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapterManager: BluetoothAdapterManager,
    private val connectionManager: BluetoothConnectionManager,
    private val receiverManager: BluetoothReceiverManager,
    private val permissionChecker: BluetoothPermissionChecker
) : BluetoothRepository {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

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
        if (permissionChecker.hasPermission(Manifest.permission.BLUETOOTH_SCAN))
            adapterManager.observeBluetoothAdapter()

        coroutineScope.launch {
            adapterManager.isDiscoveringFlow
                .collect {
                    _state.value = _state.value.copy(isDiscovering = it)
                }
        }

        coroutineScope.launch {
            adapterManager.events.collect { event ->
                when(event) {
                    is Event.DiscoveryStarted -> {
                        _state.value = _state.value.copy(
                            discoveredDevices = emptyList()
                        )
                    }
                }
            }
        }

        coroutineScope.launch {
            connectionManager.connectionStatus.collect {
                setConnectionStatus(it)
            }
        }

        coroutineScope.launch {
            receiverManager.discoveredDevices.collect {
                setDiscoveredDevices(it)
            }
        }

        coroutineScope.launch {
            receiverManager.events.collect { event ->
                when (event) {
                    is BluetoothReceiverManager.Event.BondStateChanged -> {
                        _events.emit(
                            BluetoothEvent.BondStateChanged(
                                deviceAddress = event.deviceAddress,
                                bondState = event.bondState,
                                previousBondState = event.previousBondState
                            )
                        )
                    }

                    is BluetoothReceiverManager.Event.KeyMissing -> {
                        _events.emit(
                            BluetoothEvent.KeyMissing(event.deviceAddress)
                        )
                    }

                    is BluetoothReceiverManager.Event.ConnectionStateChanged -> {
                        val connectionState = event.connectionState
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
        }
    }

    override fun setConnectionStatus(bondState: Int) = setConnectionStatus(
        bondState.toConnectionStatus()
    )

    override fun setConnectionStatus(
        status: ConnectionStatus
    ) {
        coroutineScope.launch {
            _state.value = _state.value.copy(connectionStatus = status)
        }
    }

    override fun startDiscovery() {
        if (!permissionChecker.hasRequiredPermissions()) {
            coroutineScope.launch {
                _events.emit(
                    BluetoothEvent.MissingPermissions(
                        permissionChecker.getMissingPermissions()
                    )
                )
            }

            return
        }

        adapterManager.startDiscovery()
    }

    override fun stopDiscovery() = adapterManager.stopDiscovery()

    override fun refreshPairedDevices() {
        val permissionsToRequest = permissionChecker.getMissingPermissions()

        if (permissionsToRequest.isNotEmpty()) {
            coroutineScope.launch {
                _events.emit(
                    BluetoothEvent.MissingPermissions(permissionsToRequest)
                )
            }

            return
        } else Log.d(TAG_BOND_LOSS, "Initial setup: Permissions already granted.")

        _state.value = _state.value.copy(
            pairedDevices = adapterManager.getBondedDevices()
        )
    }

    override fun pairDevice(deviceName: String) {
        getBluetoothDevice(deviceName)?.let {
            adapterManager.bondDevice(it)
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToDevice(deviceName: String) {
        getBluetoothDevice(deviceName)?.let {
            connectionManager.connect(it)
        }
    }

    override fun disconnect() = connectionManager.disconnect()

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
                        "${previousBondState.toConnectionStatus()} -> " +
                        bondState.toConnectionStatus()

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
        if (permissionChecker.hasPermission(
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

    @SuppressLint("MissingPermission")
    private fun getBluetoothDevice(deviceName: String) =
        (_state.value.pairedDevices + _state.value.discoveredDevices)
            .firstOrNull { it.name == deviceName }

    private fun emitEvent(event: BluetoothEvent) = coroutineScope.launch {
        _events.emit(event)
    }
}