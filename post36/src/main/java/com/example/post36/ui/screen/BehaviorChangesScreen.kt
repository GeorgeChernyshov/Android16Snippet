package com.example.post36.ui.screen

import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.post36.R
import com.example.post36.ui.components.AppBar
import com.example.post36.ui.theme.Android16SnippetTheme
import java.util.regex.Pattern

@Composable
fun BehaviorChangesScreen(
    onNextClick: () -> Unit
) {

    val companionDeviceManager = LocalContext.current
        .getSystemService(
            Context.COMPANION_DEVICE_SERVICE
        ) as CompanionDeviceManager

    val associationRequest: AssociationRequest by lazy {
        val deviceFilter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("MyDevice.*"))
            .setAddress("00:11:22:33:AA:BB")
            .build()

        AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()
    }

    val deviceAssociationResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->

    }

    val deviceDiscoveryCallback = object : CompanionDeviceManager.Callback() {
        override fun onAssociationPending(intentSender: IntentSender) {
            super.onAssociationPending(intentSender)
            Log.d("CompanionPairing", "onAssociationPending called.")
            try {
                val intentSenderRequest = IntentSenderRequest
                    .Builder(intentSender)
                    .build()

                deviceAssociationResultLauncher.launch(intentSenderRequest)
            } catch (e: Exception) {
                Log.e(
                    "CompanionPairing",
                    "Failed to launch from onAssociationPending",
                    e
                )
            }
        }

        override fun onFailure(p0: CharSequence?) {
            Log.e(
                "CompanionPairing",
                "Failed to send intent for association"
            )
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = { AppBar(name = stringResource(R.string.label_behavior_changes)) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                        R.string.bc_predictive_back_for_buttons_supported
                    else R.string.bc_predictive_back_for_buttons_not_supported
                )
            )

            CompanionAppsBlock(
                startCompanionDevicePairing = {
                    companionDeviceManager.associate(
                        associationRequest,
                        deviceDiscoveryCallback,
                        null
                    )
                }
            )

            Button(onClick = onNextClick) {
                Text(stringResource(R.string.button_next))
            }
        }
    }
}

@Composable
fun CompanionAppsBlock(
    startCompanionDevicePairing: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                    R.string.bc_device_discovery_dialog
                else R.string.bc_device_discovery_no_dialog
            )
        )

        Button(onClick = startCompanionDevicePairing) {
            Text(text = stringResource(R.string.bc_device_discovery_start))
        }

        Text(stringResource(R.string.bc_device_discovery_hint))
    }
}

@Preview(showBackground = true)
@Composable
fun CompanionAppsBlockPreview() {
    Android16SnippetTheme {
        CompanionAppsBlock(
            startCompanionDevicePairing = {}
        )
    }
}