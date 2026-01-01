package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.post36.domain.bondloss.BluetoothEvent
import com.example.post36.domain.bondloss.ConnectionStatus
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
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
class BluetoothReceiverManager @Inject constructor() {

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

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

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
//                setDiscovering(true)
            }

            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                Log.d(TAG_BOND_LOSS, "Discovery Finished")
//                setDiscovering(false)
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

                val logMsg = "ACTION_BOND_STATE_CHANGED for $deviceName: " +
                        "${previousBondState.toConnectionStatus()} -> " +
                        bondState.toConnectionStatus()

                Log.d(TAG_BOND_LOSS, logMsg)

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
                val connectionState = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.ERROR
                )

                emitEvent(Event.ConnectionStateChanged(connectionState))
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
        class ConnectionStateChanged(val connectionState: Int) : Event()
    }
}