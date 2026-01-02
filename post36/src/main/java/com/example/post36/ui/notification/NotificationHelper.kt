package com.example.post36.ui.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import com.example.post36.Post36Application.Companion.NOTIFICATION_CHANNEL
import com.example.post36.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun createBluetoothServerNotification() = NotificationCompat
        .Builder(context, NOTIFICATION_CHANNEL)
        .setContentTitle(context.getString(R.string.notification_bluetooth_title))
        .setContentText(context.getString(R.string.notification_bluetooth_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .build()
}