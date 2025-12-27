package com.example.post36.domain.bondloss

data class BluetoothState(
    val discoveredDevices: List<BluetoothDeviceModel> = emptyList(),
    val pairedDevices: List<BluetoothDeviceModel> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val isDiscovering: Boolean = false
)

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