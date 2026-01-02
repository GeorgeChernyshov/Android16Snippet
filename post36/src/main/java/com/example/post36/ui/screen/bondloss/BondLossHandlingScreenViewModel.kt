package com.example.post36.ui.screen.bondloss

import android.bluetooth.BluetoothDevice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.post36.domain.bondloss.BluetoothEvent
import com.example.post36.domain.bondloss.BluetoothRepository
import com.example.post36.domain.bondloss.BluetoothServerRepository
import com.example.post36.domain.bondloss.BluetoothState
import com.example.post36.domain.bondloss.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BondLossHandlingScreenViewModel @Inject constructor(
    private val bluetoothRepository: BluetoothRepository,
    private val bluetoothServerRepository: BluetoothServerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BondLossHandlingScreenUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BondLossHandlingEvent>()
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            combine(
                bluetoothRepository.state,
                bluetoothServerRepository.isServerRunning
            ) { bluetoothState, bluetoothServerRunning ->
                BondLossHandlingScreenUiState(
                    discoveredDevices = bluetoothState.discoveredDevices
                        .map { BluetoothDeviceUiState.from(it) },
                    pairedDevices = bluetoothState.pairedDevices
                        .map { BluetoothDeviceUiState.from(it) },
                    selectedDevice = _uiState.value.selectedDevice,
                    connectionStatus = bluetoothState.connectionStatus,
                    isDiscovering = bluetoothState.isDiscovering,
                    bluetoothServerRunning = bluetoothServerRunning
                )
            }.collect { _uiState.value = it }
        }

        viewModelScope.launch {
            bluetoothRepository.events.collect(::handleEvent)
        }
    }

    fun refreshPairedDevices() = bluetoothRepository.refreshPairedDevices()

    fun toggleDiscovery() {
        if (_uiState.value.isDiscovering)
            bluetoothRepository.stopDiscovery()
        else bluetoothRepository.startDiscovery()
    }

    fun selectDevice(device: BluetoothDeviceUiState?) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(selectedDevice = device)
        if (_uiState.value.isDiscovering)
            bluetoothRepository.stopDiscovery()
    }

    fun pairDevice() = uiState.value
        .selectedDevice
        ?.let { bluetoothRepository.pairDevice(it.name) }

    fun connectToDevice() = uiState.value
        .selectedDevice
        ?.let { bluetoothRepository.connectToDevice(it.name) }

    fun registerBluetoothReceiver() = bluetoothRepository.registerReceiver()
    fun unregisterBluetoothReceiver() = bluetoothRepository.unregisterReceiver()

    fun toggleBluetoothServer() {
        if (uiState.value.bluetoothServerRunning)
            bluetoothServerRepository.stopServer()
        else bluetoothServerRepository.startServer()
    }

    private fun handleEvent(event: BluetoothEvent) {
        when (event) {
            is BluetoothEvent.MissingPermissions -> {
                viewModelScope.launch {
                    _events.emit(
                        BondLossHandlingEvent.RequestPermissions(event.permissions)
                    )
                }
            }

            is BluetoothEvent.BondStateChanged -> {
                val newDeviceAddress = event.deviceAddress
                val selectedDevice = uiState.value.selectedDevice

                val needToSwitchAddress = selectedDevice?.address != null
                        && newDeviceAddress != selectedDevice.address

                if (!needToSwitchAddress) {
                    bluetoothRepository.setConnectionStatus(event.bondState)

                    if (event.bondState == BluetoothDevice.BOND_NONE) {
                        bluetoothRepository.setConnectionStatus(
                            ConnectionStatus.BOND_LOST
                        )

                        bluetoothRepository.disconnect()
                        selectDevice(null)
                    }
                }

                refreshPairedDevices()
            }

            is BluetoothEvent.KeyMissing -> {
                val newDeviceAddress = event.deviceAddress
                val selectedDevice = uiState.value.selectedDevice

                if (newDeviceAddress != selectedDevice?.address)
                    return

                bluetoothRepository.setConnectionStatus(
                    ConnectionStatus.KEY_MISSING
                )

                bluetoothRepository.disconnect()
                selectDevice(null)
            }
        }
    }
}