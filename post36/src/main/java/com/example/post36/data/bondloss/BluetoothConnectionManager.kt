package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.post36.domain.bondloss.ConnectionStatus
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothConnectionManager @Inject constructor(
    private val adapterManager: BluetoothAdapterManager
) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    var job: Job? = null
    var currentSocket: BluetoothSocket? = null

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus = _connectionStatus.asStateFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_BONDED)
            return

        _connectionStatus.value = ConnectionStatus.CONNECTING

        job = coroutineScope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket?

            try {
                _connectionStatus.value = ConnectionStatus.CONNECTING
                currentSocket = null

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                Log.d(TAG_BOND_LOSS, "Attempting to connect to ${device.address}...")
                socket.connect()
                _connectionStatus.value = ConnectionStatus.CONNECTED
                currentSocket = socket

                Log.d(TAG_BOND_LOSS, "Connection successful.")

                val inputStream = socket.inputStream
                val buffer = ByteArray(1024)
                var bytes: Int

                // Loop while the socket is connected and the coroutine is still active (not cancelled)
                while (socket.isConnected && this.isActive) {
                    try {
                        bytes = inputStream.read(buffer) // Blocking read
                        if (bytes == -1) { // End of stream (peer disconnected gracefully)
                            Log.d(TAG_BOND_LOSS, "Input stream ended (peer disconnected gracefully) for ${device.address}.")
                            break // Exit loop, connection effectively closed
                        }
                        val readMessage = String(buffer, 0, bytes)
                        Log.d(TAG_BOND_LOSS, "Received from ${device.address}: $readMessage")
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        currentSocket = socket
                    } catch (e: IOException) {
                        Log.e(TAG_BOND_LOSS, "Read/write error during communication with ${device.address}: ${e.message}")
                        if (this.isActive) { // Check if the job is still active, means error happened unexpectedly
                            _connectionStatus.value = ConnectionStatus.DISCONNECTED
                            currentSocket = null
                        }

                        break // Exit loop on communication error
                    }
                }
            } catch (se: SecurityException) {
                Log.e(TAG_BOND_LOSS, "SecurityException during connect: ${se.message}")
                _connectionStatus.value = ConnectionStatus.CONNECTION_FAILED
                currentSocket = null
            } catch (e: IOException) { // <-- IMPORTANT: CATCH THE IOException HERE
                // This catches the 'read failed, socket might closed or timeout' and other connection errors
                Log.e(
                    TAG_BOND_LOSS,
                    "IOException during connect to ${device.address}: ${e.message}"
                )

                Log.e(TAG_BOND_LOSS, "Local Bond State AFTER IOException: ${device.bondState.toConnectionStatus()}")

                // Explicitly list bonded devices from the adapterManager
                val bondedDevices = adapterManager.getBondedDevices() // This requires adapterManager access
                Log.e(TAG_BOND_LOSS, "Bonded devices list AFTER IOException:")
                if (bondedDevices.isEmpty()) {
                    Log.e(TAG_BOND_LOSS, "  (None)")
                } else {
                    bondedDevices.forEach { bd ->
                        Log.e(TAG_BOND_LOSS, "  - ${bd.name ?: bd.address} (Bond State: ${bd.bondState.toConnectionStatus()})")
                    }
                }

                _connectionStatus.value = ConnectionStatus.CONNECTION_FAILED
                currentSocket = null
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        currentSocket?.close()
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}