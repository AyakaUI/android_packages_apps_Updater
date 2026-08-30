/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package net.ayakaui.ota.certifiedprops

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.ayakaui.ota.PackageInstallerStatusReceiver
import net.ayakaui.ota.R
import net.ayakaui.ota.deviceinfo.DeviceInfoUtils
import net.ayakaui.ota.download.APKDownloader
import java.io.File
import java.io.FileInputStream

enum class CertifiedPropsError {
    DOWNLOAD_FAILED,
    INSTALL_FAILED,
    INVALID_PACKAGE,
}

sealed interface CertifiedPropsState {
    val installedVersion: Long

    data class Idle(override val installedVersion: Long) : CertifiedPropsState
    data class Checking(override val installedVersion: Long) : CertifiedPropsState
    data class UpToDate(
        override val installedVersion: Long,
        val remoteVersion: Long,
    ) : CertifiedPropsState
    data class UpdateAvailable(
        override val installedVersion: Long,
        val remoteVersion: Long,
    ) : CertifiedPropsState
    data class Installing(
        override val installedVersion: Long,
        val remoteVersion: Long,
    ) : CertifiedPropsState
    data class Installed(override val installedVersion: Long) : CertifiedPropsState
    data class Error(
        override val installedVersion: Long,
        val error: CertifiedPropsError,
    ) : CertifiedPropsState
}

class CertifiedPropsRepository(private val context: Context) {
    private val packageName = context.getString(R.string.certified_prop_overlay_name)
    private val apkPath = File(context.getExternalFilesDir(null), APK_FILE_NAME).absolutePath

    private val _state = MutableStateFlow<CertifiedPropsState>(
        CertifiedPropsState.Idle(getLocalVersion())
    )
    val state = _state.asStateFlow()

    suspend fun checkForUpdate() {
        if (_state.value is CertifiedPropsState.Checking ||
            _state.value is CertifiedPropsState.Installing
        ) {
            return
        }

        val installedVersion = getLocalVersion()
        _state.value = CertifiedPropsState.Checking(installedVersion)

        val file = File(apkPath)
        if (file.exists()) file.delete()

        val url = context.getString(R.string.certified_prop_url)
            .replace("{branch}", DeviceInfoUtils.otaBranch)
        if (!APKDownloader.downloadApk(apkPath, url)) {
            _state.value = CertifiedPropsState.Error(
                installedVersion,
                CertifiedPropsError.DOWNLOAD_FAILED,
            )
            return
        }

        val packageInfo = withContext(Dispatchers.IO) { getPackageInfo(apkPath) }
        if (packageInfo == null || packageInfo.packageName != packageName) {
            file.delete()
            _state.value = CertifiedPropsState.Error(
                installedVersion,
                CertifiedPropsError.INVALID_PACKAGE,
            )
            return
        }

        val remoteVersion = packageInfo.longVersionCode
        _state.value = if (remoteVersion > installedVersion) {
            CertifiedPropsState.UpdateAvailable(installedVersion, remoteVersion)
        } else {
            file.delete()
            CertifiedPropsState.UpToDate(installedVersion, remoteVersion)
        }
    }

    suspend fun installUpdate() {
        val update = _state.value as? CertifiedPropsState.UpdateAvailable ?: return
        _state.value = CertifiedPropsState.Installing(
            update.installedVersion,
            update.remoteVersion,
        )

        val installed = withContext(Dispatchers.IO) { installApk(apkPath) }
        _state.value = if (installed) {
            CertifiedPropsState.Installed(update.remoteVersion)
        } else {
            CertifiedPropsState.Error(
                update.installedVersion,
                CertifiedPropsError.INSTALL_FAILED,
            )
        }
    }

    private fun getPackageInfo(path: String): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(path, 0)

    private fun getLocalVersion(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.MATCH_SYSTEM_ONLY,
            )
            packageInfo.longVersionCode
        } catch (_: PackageManager.NameNotFoundException) {
            -1
        }
    }

    private fun installApk(path: String): Boolean {
        return try {
            val packageInstaller = context.packageManager.packageInstaller
            val sessionParams =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)

            FileInputStream(File(path)).use { inputStream ->
                session.openWrite("app_install", 0, -1).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
            }
            val receiverIntent = Intent(context, PackageInstallerStatusReceiver::class.java)
            val flags = PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val receiverPendingIntent =
                PendingIntent.getBroadcast(context, 0, receiverIntent, flags)
            session.commit(receiverPendingIntent.intentSender)

            session.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val APK_FILE_NAME = "prop.apk"
    }
}
