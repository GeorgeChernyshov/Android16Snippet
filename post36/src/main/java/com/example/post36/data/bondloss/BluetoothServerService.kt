package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.util.Log
import com.example.post36.ui.notification.NotificationHelper
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class BluetoothServerService : Service() {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var serverJob: Job? = null
    private var clientCommunicationJob: Job? = null

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothServerSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null

    override fun onBind(p0: Intent?) = null

    override fun onCreate() {
        super.onCreate()

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG_BOND_LOSS, "BluetoothServerService onDestroy")
        stopServerInternal()
        coroutineScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG_BOND_LOSS, "BluetoothServerService onStartCommand: ${intent?.action}")

        val action = Action.from(intent?.action ?: "")

        when (action) {
            Action.START_BLUETOOTH_SERVER -> startForegroundServer()
            Action.STOP_BLUETOOTH_SERVER -> stopSelf()
//            Action.MAKE_DISCOVERABLE -> {
//                val duration = intent?.getIntExtra(EXTRA_DISCOVERABLE_DURATION, 300)
//                makeDeviceDiscoverable(duration)
//            }

            else -> {}
        }

        return START_STICKY // Service will be restarted if killed by system
    }

    @SuppressLint("MissingPermission")
    private fun startForegroundServer() {
        if (serverJob?.isActive == true) {
            Log.d(TAG_BOND_LOSS, "Server already running, not starting again.")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG_BOND_LOSS, "Cannot start server: Bluetooth permissions or adapter issue.")
            stopSelf()

            return
        }

        val notification = notificationHelper.createBluetoothServerNotification()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG_BOND_LOSS, "BluetoothServerService started in foreground.")

        serverJob = coroutineScope.launch {
            try {
                bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                Log.d(TAG_BOND_LOSS, "Server listening on UUID: $SPP_UUID")

                while (isActive) {
                    Log.d(TAG_BOND_LOSS, "Waiting for client connection...")
                    clientSocket = bluetoothServerSocket!!.accept() // Blocking call
                    clientSocket?.let { socket ->
                        val remoteDevice = socket.remoteDevice
                        Log.d(TAG_BOND_LOSS, "Client connected: ${remoteDevice.name ?: remoteDevice.address}")

                        clientCommunicationJob = coroutineScope.launch {
                            handleClientCommunication(socket, remoteDevice)
                        }
                        clientCommunicationJob?.join() // Wait for communication to finish
                    }
                }
            } catch (e: IOException) {
                if (isActive) { // Only log as error if not cancelled intentionally
                    Log.e(TAG_BOND_LOSS, "Server socket accept() failed: ${e.message}")
                } else {
                    Log.d(TAG_BOND_LOSS, "Server job cancelled (expected).")
                }
            } catch (e: SecurityException) {
                Log.e(TAG_BOND_LOSS, "SecurityException in server: ${e.message}")
            } finally {
                withContext(Dispatchers.IO) {
                    bluetoothServerSocket?.close()
                    bluetoothServerSocket = null
                    Log.d(TAG_BOND_LOSS, "Server socket closed and server stopped.")
                }

                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        }
    }


    private fun stopServerInternal() {
        serverJob?.cancel()
        clientCommunicationJob?.cancel()
        serverJob = null
        clientCommunicationJob = null

        try {
            clientSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG_BOND_LOSS, "Error closing client socket on stop: ${e.message}")
        } finally {
            clientSocket = null
        }
        try {
            bluetoothServerSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG_BOND_LOSS, "Error closing server socket on stop: ${e.message}")
        } finally {
            bluetoothServerSocket = null
        }
    }

    private suspend fun handleClientCommunication(
        socket: BluetoothSocket,
        remoteDevice: BluetoothDevice
    ) {
        try {
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            val buffer = ByteArray(1024)
            var bytes: Int

            Log.d(TAG_BOND_LOSS, "Starting communication with ${remoteDevice.address}")

            while (socket.isConnected && clientCommunicationJob?.isActive == true) {
                bytes = inputStream.read(buffer) // Blocking read
                if (bytes == -1) {
                    Log.d(TAG_BOND_LOSS, "Client ${remoteDevice.address} disconnected gracefully.")
                    break
                }
                val receivedData = String(buffer, 0, bytes)
                Log.d(TAG_BOND_LOSS, "Received from ${remoteDevice.address}: $receivedData")

                // Optionally echo back received data
                outputStream.write("Echo from server: $receivedData".toByteArray())
                outputStream.flush()
            }
        } catch (e: IOException) {
            Log.e(TAG_BOND_LOSS, "Communication error with ${remoteDevice.address}: ${e.message}")
        } finally {
            try {
                socket.close()
                Log.d(TAG_BOND_LOSS, "Client socket closed for ${remoteDevice.address} after communication.")
            } catch (e: IOException) {
                Log.e(TAG_BOND_LOSS, "Error closing client socket for ${remoteDevice.address}: ${e.message}")
            } finally {
                clientSocket = null
            }
        }
    }

    enum class Action(val value: String) {
        START_BLUETOOTH_SERVER("com.example.post36.action.START_BLUETOOTH_SERVER"),
        STOP_BLUETOOTH_SERVER("com.example.post36.action.STOP_BLUETOOTH_SERVER"),
        MAKE_DISCOVERABLE("com.example.post36.action.MAKE_DISCOVERABLE");

        companion object {
            fun from(str: String) = entries.find { it.value == str }
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val EXTRA_DISCOVERABLE_DURATION = "extra_discoverable_duration"

        private const val SERVICE_NAME = "MySPPService"
        private const val NOTIFICATION_ID = 1001
    }
}