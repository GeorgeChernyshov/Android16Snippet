package com.example.post36.data.bondloss

import android.bluetooth.BluetoothDevice
import com.example.post36.domain.bondloss.ConnectionStatus

fun Int.toConnectionStatus() = when (this) {
    BluetoothDevice.BOND_NONE -> ConnectionStatus.BOND_NONE
    BluetoothDevice.BOND_BONDING -> ConnectionStatus.BOND_BONDING
    BluetoothDevice.BOND_BONDED -> ConnectionStatus.BOND_BONDED
    BluetoothDevice.ERROR -> ConnectionStatus.ERROR
    else -> ConnectionStatus.UNKNOWN
}