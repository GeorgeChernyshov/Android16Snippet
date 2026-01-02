package com.example.post36.data

import android.content.Context
import com.example.post36.domain.bondloss.BluetoothRepository
import com.example.post36.data.bondloss.BluetoothRepositoryImpl
import com.example.post36.data.bondloss.BluetoothServerRepositoryImpl
import com.example.post36.domain.bondloss.BluetoothServerRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindBluetoothRepository(
        bluetoothRepositoryImpl: BluetoothRepositoryImpl
    ): BluetoothRepository

    @Binds
    abstract fun bindBluetoothServerRepository(
        bluetoothServerRepositoryImpl: BluetoothServerRepositoryImpl
    ): BluetoothServerRepository
}