package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.foundation.gestures.forEach
import androidx.compose.ui.geometry.isEmpty
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothReceiverManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adapterManager: BluetoothAdapterManager
) {

    val deviceReceiver = object : BroadcastReceiver() {

        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

            handleBluetoothSearchAction(
                intent = intent ?: return,
                deviceFromIntent = deviceFromIntent
            )
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices = _discoveredDevices.asStateFlow()

    private val _isDiscoveringFlow = MutableStateFlow(false)
    val isDiscoveringFlow = _isDiscoveringFlow.asStateFlow()

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        try {
            context.registerReceiver(deviceReceiver, intentFilter)
            Log.d(TAG_BOND_LOSS, "Bluetooth BroadcastReceiver registered.")
        } catch (e: Exception) {
            Log.e(
                TAG_BOND_LOSS,
                "Failed to register BroadcastReceiver: ${e.message}",
                e
            )
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(deviceReceiver)
            Log.d(TAG_BOND_LOSS, "Bluetooth BroadcastReceiver unregistered.")
        } catch (e: Exception) {
            Log.e(
                TAG_BOND_LOSS,
                "Failed to unregister BroadcastReceiver: ${e.message}",
                e
            )
        }
    }

    private fun setDiscovering(
        isDiscovering: Boolean
    ) = coroutineScope.launch {
        _isDiscoveringFlow.emit(isDiscovering)
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothSearchAction(
        intent: Intent,
        deviceFromIntent: BluetoothDevice?
    ) {
        val action = intent.action

        when (action) {
            BluetoothDevice.ACTION_FOUND -> {
                deviceFromIntent?.let { device ->
                    val deviceName = device.name ?: "Unknown Device"
                    Log.d(TAG_BOND_LOSS, "ACTION_FOUND: $deviceName (${device.address})")

                    handleDeviceFound(device)
                }
            }

            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                Log.d(TAG_BOND_LOSS, "Discovery Started")
                _discoveredDevices.value = emptyList()
                setDiscovering(true)
            }

            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                Log.d(TAG_BOND_LOSS, "Discovery Finished")
                setDiscovering(false)
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR
                )

                val previousBondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.ERROR
                )

                val deviceName = try {
                    deviceFromIntent?.name ?: deviceFromIntent?.address
                } catch (e: SecurityException) {
                    deviceFromIntent?.address
                }

                Log.d(TAG_BOND_LOSS, "--- BOND_STATE_CHANGED Broadcast Received ---")
                Log.d(TAG_BOND_LOSS, "Device: ${deviceFromIntent?.name ?: deviceFromIntent?.address}")
                Log.d(TAG_BOND_LOSS, "Previous Bond State: ${previousBondState.toConnectionStatus()}")
                Log.d(TAG_BOND_LOSS, "New Bond State: ${previousBondState.toConnectionStatus()}")

                // Explicitly list bonded devices from the adapterManager
                val bondedDevices = adapterManager.getBondedDevices() // Requires BLUETOOTH_CONNECT permission
                Log.d(TAG_BOND_LOSS, "Bonded devices list AFTER BOND_STATE_CHANGED:")
                if (bondedDevices.isEmpty()) {
                    Log.d(TAG_BOND_LOSS, "  (None)")
                } else {
                    bondedDevices.forEach { bd ->
                        Log.d(TAG_BOND_LOSS, "  - ${bd.name ?: bd.address} (Bond State: ${bd.bondState.toConnectionStatus()})")
                    }
                }

                emitEvent(
                    Event.BondStateChanged(
                        deviceAddress = deviceFromIntent?.address ?: return,
                        bondState = bondState,
                        previousBondState = previousBondState
                    )
                )
            }

            BluetoothDevice.ACTION_KEY_MISSING -> {
                val deviceName = try {
                    deviceFromIntent?.name ?: deviceFromIntent?.address
                } catch (e: SecurityException) {
                    deviceFromIntent?.address
                }

                val logMsg = "ACTION_KEY_MISSING received for $deviceName. System should show dialog. Local bond retained."
                Log.d(TAG_BOND_LOSS, logMsg)

                emitEvent(
                    Event.KeyMissing(
                        deviceFromIntent?.address ?: return
                    )
                )
            }

            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val deviceName = try {
                    deviceFromIntent?.name ?: deviceFromIntent?.address
                } catch (e: SecurityException) {
                    deviceFromIntent?.address
                }

                val connectionState = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.ERROR
                )

                emitEvent(Event.ConnectionStateChanged(
                    deviceName = deviceName.orEmpty(),
                    connectionState = connectionState
                ))
            }
        }
    }

    private fun handleDeviceFound(device: BluetoothDevice) {
        try {
            val deviceName = device.name ?: "Unknown Device"
            Log.d(TAG_BOND_LOSS, "Device Found: $deviceName (${device.address})")
            if (discoveredDevices.value
                    .none { it.address == device.address }
            ) {
                _discoveredDevices.value = _discoveredDevices.value + device
            }
        } catch (se: SecurityException) {
            Log.e(TAG_BOND_LOSS, "SecurityException on ACTION_FOUND: ${se.message}")
        }
    }

    private fun emitEvent(event: Event) = coroutineScope.launch {
        _events.emit(event)
    }

    sealed class Event {
        class BondStateChanged(
            val deviceAddress: String,
            val bondState: Int,
            val previousBondState: Int
        ) : Event()

        class KeyMissing(val deviceAddress: String) : Event()
        class ConnectionStateChanged(
            val deviceName: String,
            val connectionState: Int
        ) : Event()
    }
}