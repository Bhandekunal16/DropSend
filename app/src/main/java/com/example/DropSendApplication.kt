package com.example

import android.app.Application
import android.os.Build
import com.example.data.connectivity.ConnectivityMonitor
import com.example.data.connectivity.HotspotAutoConnector
import com.example.data.connectivity.LocalHotspotManager
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.discovery.DeviceDiscoveryManager
import com.example.data.storage.StorageManager
import com.example.data.storage.ThemePreferences

class DropSendApplication : Application() {

    lateinit var storageManager: StorageManager
        private set

    lateinit var discoveryManager: DeviceDiscoveryManager
        private set

    lateinit var connectivityMonitor: ConnectivityMonitor
        private set

    lateinit var themePreferences: ThemePreferences
        private set

    lateinit var localHotspotManager: LocalHotspotManager
        private set

    lateinit var hotspotAutoConnector: HotspotAutoConnector
        private set

    lateinit var transferHistoryRepository: TransferHistoryRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = AppDatabase.getInstance(this)
        transferHistoryRepository = TransferHistoryRepository(db.transferHistoryDao())
        storageManager = StorageManager(this)
        discoveryManager = DeviceDiscoveryManager(this)
        connectivityMonitor = ConnectivityMonitor(this)
        themePreferences = ThemePreferences(this)
        localHotspotManager = LocalHotspotManager(this)
        hotspotAutoConnector = HotspotAutoConnector(this)
        connectivityMonitor.startMonitoring()
    }

    override fun onTerminate() {
        super.onTerminate()
        discoveryManager.release()
        connectivityMonitor.stopMonitoring()
        localHotspotManager.stopLocalHotspot()
        hotspotAutoConnector.release()
    }

    companion object {
        lateinit var instance: DropSendApplication
            private set
    }
}
