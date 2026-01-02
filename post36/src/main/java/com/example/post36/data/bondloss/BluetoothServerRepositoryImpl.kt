package com.example.post36.data.bondloss

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.post36.domain.bondloss.BluetoothServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

class BluetoothServerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: BluetoothPermissionChecker
) : BluetoothServerRepository {

    private val _isServerRunning = MutableStateFlow(false)
    override val isServerRunning = _isServerRunning.asStateFlow()

    override fun startServer() {
        val serviceIntent = Intent(
            context,
            BluetoothServerService::class.java
        ).apply {
            action = BluetoothServerService.Action
                .START_BLUETOOTH_SERVER
                .value
        }

        ContextCompat.startForegroundService(context, serviceIntent)
        Log.d(TAG_SERVER, "Requested BluetoothServerService to start.")
        _isServerRunning.value = true
    }

    override fun stopServer() {
        val serviceIntent = Intent(
            context,
            BluetoothServerService::class.java
        ).apply {
            action = BluetoothServerService.Action
                .STOP_BLUETOOTH_SERVER
                .value
        }

        context.stopService(serviceIntent)
        Log.d(TAG_SERVER, "Requested BluetoothServerService to stop.")
        _isServerRunning.value = false
    }

    companion object {
        const val TAG_SERVER = "BluetoothServer"
    }
}