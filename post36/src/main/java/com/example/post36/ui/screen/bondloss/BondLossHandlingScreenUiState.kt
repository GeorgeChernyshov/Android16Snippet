package com.example.post36.ui.screen.bondloss

import android.content.Context
import com.example.post36.R
import com.example.post36.domain.bondloss.ConnectionStatus

data class BondLossHandlingScreenUiState(
    val discoveredDevices: List<BluetoothDeviceUiState> = emptyList(),
    val pairedDevices: List<BluetoothDeviceUiState> = emptyList(),
    val selectedDevice: BluetoothDeviceUiState? = null,
    // Not sure if needed
    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
    val isDiscovering: Boolean = false,
    val bluetoothServerRunning: Boolean = false
) {
    val areButtonsEnabled
        get() = selectedDevice != null && (connectionStatus != ConnectionStatus.CONNECTING)

    fun getSelectedDeviceDescription(context: Context) : String {
        val selectedDeviceName = selectedDevice?.name
            ?: context.getString(R.string.bond_loss_selected_unknown)

        return selectedDevice?.let {
            context.getString(
                R.string.bond_loss_selected_description,
                selectedDeviceName,
                it.address
            )
        } ?: context.getString(R.string.bond_loss_selected_none)
    }
}