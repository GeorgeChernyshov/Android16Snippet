package com.example.post36.domain.bondloss

import kotlinx.coroutines.flow.Flow

interface BluetoothServerRepository {

    val isServerRunning: Flow<Boolean>

    fun startServer()
    fun stopServer()
}