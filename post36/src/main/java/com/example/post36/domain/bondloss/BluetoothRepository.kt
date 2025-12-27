package com.example.post36.domain.bondloss

import kotlinx.coroutines.flow.Flow

interface BluetoothRepository {

    val state: Flow<BluetoothState>
    val events: Flow<BluetoothEvent>

    fun setConnectionStatus(bondState: Int)
    fun setConnectionStatus(status: ConnectionStatus)
    fun startDiscovery()
    fun stopDiscovery()
    fun refreshPairedDevices()
    fun pairDevice(deviceName: String)
    fun connectToDevice(deviceName: String)
    fun disconnect()
    fun registerReceiver()
    fun unregisterReceiver()
}