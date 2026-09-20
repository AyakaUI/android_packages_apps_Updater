/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.ayaka.ota

import android.app.Application
import com.android.settingslib.spa.framework.common.SettingsPageProviderRepository
import com.android.settingslib.spa.framework.common.SpaEnvironment
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.ayaka.ota.certifiedprops.CertifiedPropsRepository
import net.ayaka.ota.data.AppStateRepository
import net.ayaka.ota.data.ChangelogRepository
import net.ayaka.ota.data.UpdatesRepository
import net.ayaka.ota.data.UserPreferencesRepository
import net.ayaka.ota.data.source.local.UpdatesDatabase
import net.ayaka.ota.data.source.local.UpdatesLocalDataSource
import net.ayaka.ota.data.source.network.UpdatesNetworkDataSource
import net.ayaka.ota.deviceinfo.DeviceInfoUtils
import net.ayaka.ota.notifications.NotificationHelper
import net.ayaka.ota.util.BatteryMonitor
import net.ayaka.ota.util.NetworkMonitor

class UpdaterApplication : Application() {
    private val coroutineScope = MainScope()
    private val database by lazy { UpdatesDatabase.getInstance(applicationContext) }
    private val networkDataSource by lazy { UpdatesNetworkDataSource(applicationContext) }
    private val localDataSource by lazy { UpdatesLocalDataSource(database.updateDao()) }


    val batteryMonitor by lazy {
        BatteryMonitor(applicationContext, coroutineScope, userPreferencesRepository)
    }
    val networkMonitor by lazy { NetworkMonitor(applicationContext, coroutineScope) }
    val notificationHelper by lazy { NotificationHelper(applicationContext) }
    val appStateRepository by lazy { AppStateRepository(applicationContext) }
    val changelogRepository by lazy { ChangelogRepository(applicationContext) }
    val certifiedPropsRepository by lazy { CertifiedPropsRepository(applicationContext) }
    val userPreferencesRepository by lazy { UserPreferencesRepository(applicationContext) }
    val updatesRepository by lazy {
        UpdatesRepository(
            context = applicationContext,
            networkMonitor = networkMonitor,
            notificationHelper = notificationHelper,
            networkDataSource = networkDataSource,
            localDataSource = localDataSource,
            userPreferencesRepository = userPreferencesRepository,
        )
    }

    override fun onCreate() {
        super.onCreate()
        DeviceInfoUtils.initialize(applicationContext)
        notificationHelper.setUpNotificationChannels()
        coroutineScope.launch {
            userPreferencesRepository.migrateLegacyPreferences()
        }
        coroutineScope.launch {
            updatesRepository.pruneInstalledUpdates()
        }
        SpaEnvironmentFactory.reset(object : SpaEnvironment(applicationContext) {
            override val pageProviderRepository = lazy {
                SettingsPageProviderRepository(emptyList())
            }

            override val isSpaExpressiveEnabled = true
        })
    }
}
