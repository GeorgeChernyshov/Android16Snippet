package com.example.post36.data.bondloss

import android.Manifest
import android.bluetooth.BluetoothDevice
import androidx.annotation.RequiresPermission
import com.example.post36.domain.bondloss.BluetoothDeviceModel

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun BluetoothDevice.toBluetoothDeviceModel() = BluetoothDeviceModel(
    name = name ?: "Unknown Device",
    address = address
)