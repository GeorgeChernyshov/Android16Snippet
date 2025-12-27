package com.example.post36.data.bondloss

import android.bluetooth.BluetoothDevice
import com.example.post36.domain.bondloss.ConnectionStatus

data class BluetoothStateImpl(
    val discoveredDevices: List<BluetoothDevice> = emptyList(),
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val isDiscovering: Boolean = false
)