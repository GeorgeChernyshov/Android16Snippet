package com.example.post36.domain.bondloss

sealed class BluetoothEvent {
    class MissingPermissions(val permissions: List<String>) : BluetoothEvent()
    class BondStateChanged(
        val deviceAddress: String,
        val bondState: Int,
        val previousBondState: Int
    ) : BluetoothEvent()

    class KeyMissing(val deviceAddress: String) : BluetoothEvent()
}