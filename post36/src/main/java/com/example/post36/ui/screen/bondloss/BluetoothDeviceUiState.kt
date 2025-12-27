package com.example.post36.ui.screen.bondloss

import com.example.post36.domain.bondloss.BluetoothDeviceModel

data class BluetoothDeviceUiState(
    val name: String,
    val address: String
) {
    companion object {
        fun from(model: BluetoothDeviceModel) = BluetoothDeviceUiState(
            name = model.name,
            address = model.address
        )
    }
}