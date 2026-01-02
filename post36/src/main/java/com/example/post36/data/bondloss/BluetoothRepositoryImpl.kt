package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.example.post36.data.bondloss.BluetoothAdapterManager.Event
import com.example.post36.domain.bondloss.BluetoothEvent
import com.example.post36.domain.bondloss.BluetoothRepository
import com.example.post36.domain.bondloss.BluetoothState
import com.example.post36.domain.bondloss.ConnectionStatus
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
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

    init {
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
            receiverManager.isDiscoveringFlow
                .collect {
                    _state.value = _state.value.copy(isDiscovering = it)
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
                        Log.d(TAG_BOND_LOSS, "--- CONNECTION_STATE_CHANGED Broadcast Received ---")
                        Log.d(TAG_BOND_LOSS, "Device: ${event.deviceName}")
                        Log.d(TAG_BOND_LOSS, "Connection State: ${event.connectionState}")

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
            Log.d(TAG_BOND_LOSS, "--- Client Connect Attempt ---")
            Log.d(TAG_BOND_LOSS, "Device Name: ${it.name}")
            Log.d(TAG_BOND_LOSS, "Device Address: ${it.address}")
            Log.d(TAG_BOND_LOSS, "Local Bond State BEFORE connect: ${it.bondState.toConnectionStatus()}")

            connectionManager.connect(it)
        }
    }

    override fun disconnect() = connectionManager.disconnect()

    override fun registerReceiver() = receiverManager.registerReceiver()

    override fun unregisterReceiver() {
        receiverManager.unregisterReceiver()
        stopDiscovery()
        disconnect()
    }

    private fun setDiscoveredDevices(
        devices: List<BluetoothDevice>
    ) = coroutineScope.launch {
        _state.value = _state.value.copy(discoveredDevices = devices)
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothDevice(deviceName: String) =
        (_state.value.pairedDevices + _state.value.discoveredDevices)
            .firstOrNull { it.name == deviceName }
}