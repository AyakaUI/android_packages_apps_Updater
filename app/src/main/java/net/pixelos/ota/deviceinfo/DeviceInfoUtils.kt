/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package net.pixelos.ota.deviceinfo

import android.content.Context
import android.os.Build
import android.os.SystemProperties
import com.android.settingslib.DeviceInfoUtils as SettingsLibDeviceInfoUtils
import net.pixelos.ota.R

object DeviceInfoUtils : SettingsLibDeviceInfoUtils() {

    private const val PROP_AB_DEVICE = "ro.build.ab_update"
    private const val PROP_BUILD_DATE = "ro.build.date.utc"
    private const val PROP_BUILD_TYPE = "net.pixelos.build_type"
    private const val PROP_BUILD_VERSION = "ro.custom.version"
    private const val PROP_DEVICE = "ro.custom.device"
    private const val PROP_OTA_BRANCH = "net.pixelos.version"
    private const val PROP_UPDATE_RECOVERY = "persist.vendor.recovery_update"

    // Read-only
    val androidVersion: String = Build.VERSION.RELEASE

    val buildSecurityPatch: String = Build.VERSION.SECURITY_PATCH

    val sdkLevel: Int = Build.VERSION.SDK_INT

    @JvmStatic
    val buildDateTimestamp: Long = SystemProperties.getLong(PROP_BUILD_DATE, 0)

    @JvmStatic
    val buildVersion: String = SystemProperties.get(PROP_BUILD_VERSION, "")

    @JvmStatic
    val device: String = SystemProperties.get(PROP_DEVICE)

    @JvmStatic
    val isABDevice: Boolean = SystemProperties.getBoolean(PROP_AB_DEVICE, false)

    @JvmStatic
    val buildType: String = SystemProperties.get(PROP_BUILD_TYPE)

    @JvmStatic
    val otaBranch: String = SystemProperties.get(PROP_OTA_BRANCH)

    // Mutable at runtime
    @JvmStatic
    val isDowngradingAllowed: Boolean = false

    @JvmStatic
    var isMajorUpdateAllowed: Boolean = true
        private set

    @JvmStatic
    var isRecoveryUpdateEnabled: Boolean
        get() = SystemProperties.getBoolean(PROP_UPDATE_RECOVERY, false)
        set(value) = SystemProperties.set(PROP_UPDATE_RECOVERY, value.toString())

    fun initialize(context: Context) {
        isMajorUpdateAllowed = context.resources.getBoolean(R.bool.config_allowMajorUpgrades)
    }
}
