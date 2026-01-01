package com.example.post36.data.bondloss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.post36.ui.screen.bondloss.TAG_BOND_LOSS
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothAdapterManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val _events = MutableSharedFlow<Event>()
    val events = _events.asSharedFlow()

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val btAvailableAndEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled
        val locationEnabledForDiscovery = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } else true // Not required for API 31+ for BT scan

        Log.d(TAG_BOND_LOSS, "Discovery button clicked. Pre-checks:")
        Log.d(TAG_BOND_LOSS, "  Permissions Granted: true")
        Log.d(TAG_BOND_LOSS, "  Bluetooth Available & Enabled: $btAvailableAndEnabled")
        Log.d(TAG_BOND_LOSS, "  Location Enabled (if required): $locationEnabledForDiscovery")
        Log.d(TAG_BOND_LOSS, "  BluetoothAdapter.isDiscovering (actual): ${bluetoothAdapter?.isDiscovering}")

        if (!btAvailableAndEnabled)
            return

        if (!locationEnabledForDiscovery) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

            return
        }

        coroutineScope.launch {
            _events.emit(Event.DiscoveryStarted)
        }

        val started = bluetoothAdapter.startDiscovery()
        Log.d(TAG_BOND_LOSS, "Attempting to start discovery. Success: $started")
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val cancelled = bluetoothAdapter?.cancelDiscovery()
        Log.d(TAG_BOND_LOSS, "Attempting to cancel discovery. Success: $cancelled")
    }

    fun getBondedDevices(): List<BluetoothDevice> {
        if (bluetoothAdapter == null) {
            Log.e(TAG_BOND_LOSS, "Initial setup: BluetoothAdapter is null.")

            return emptyList()
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w(TAG_BOND_LOSS, "Initial setup: Bluetooth is not enabled.")

            return emptyList()
        }

        try {
            val currentlyPaired = bluetoothAdapter.bondedDevices
                ?.toList()
                ?: emptyList()

            return currentlyPaired
        } catch (se: SecurityException) {
            Log.e(
                TAG_BOND_LOSS,
                "SecurityException getting bonded devices: ${se.message}"
            )
        }

        return emptyList()
    }

    @SuppressLint("MissingPermission")
    fun bondDevice(device: BluetoothDevice) {
        // Initiate bonding!
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            val initiated = device.createBond() // Requires BLUETOOTH_CONNECT
            if (initiated) {
                Log.d(
                    TAG_BOND_LOSS,
                    "Pairing initiated for ${device.address}"
                )
            } else {
                Log.e(
                    TAG_BOND_LOSS,
                    "createBond() returned false for ${device.address}"
                )
            }
        }
    }

    sealed class Event() {
        object DiscoveryStarted : Event()
    }
}