/*
 * SPDX-FileCopyrightText: 2026 PixelOS
 * SPDX-License-Identifier: Apache-2.0
 */

package net.ayakaui.ota.certifiedprops

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.ayakaui.ota.R
import net.ayakaui.ota.deviceinfo.DeviceInfoUtils
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

/**
 * Downloads certified_build_props.json and stores it in Settings.Secure.FETCHED_PIF,
 * which PropImitationHooks (framework) reads when GMS / Play Store processes start.
 * No APK / overlay install and no reboot needed.
 */
class CertifiedPropsRepository(private val context: Context) {

    private val _state = MutableStateFlow<CertifiedPropsState>(
        CertifiedPropsState.Idle(getLocalVersion())
    )
    val state = _state.asStateFlow()

    // Validated payload waiting to be applied (kept in memory only)
    @Volatile
    private var pendingPayload: String? = null

    suspend fun checkForUpdate() {
        if (_state.value is CertifiedPropsState.Checking ||
            _state.value is CertifiedPropsState.Installing
        ) {
            return
        }

        val installedRaw = readInstalledPayload()
        val installedVersion = versionOf(installedRaw)
        _state.value = CertifiedPropsState.Checking(installedVersion)

        val url = context.getString(R.string.certified_prop_url)
            .replace("{branch}", DeviceInfoUtils.otaBranch)
        val body = withContext(Dispatchers.IO) { download(url) }
        if (body == null) {
            _state.value = CertifiedPropsState.Error(
                installedVersion,
                CertifiedPropsError.DOWNLOAD_FAILED,
            )
            return
        }

        val remote = sanitize(body)
        if (remote == null) {
            _state.value = CertifiedPropsState.Error(
                installedVersion,
                CertifiedPropsError.INVALID_PACKAGE,
            )
            return
        }

        val remotePayload = remote.toString()
        val remoteVersion = versionOf(remotePayload)
        // Compare content (not only version) so a rollback of the fingerprint is also applied
        if (remotePayload != installedRaw) {
            pendingPayload = remotePayload
            _state.value = CertifiedPropsState.UpdateAvailable(installedVersion, remoteVersion)
        } else {
            pendingPayload = null
            _state.value = CertifiedPropsState.UpToDate(installedVersion, remoteVersion)
        }
    }

    suspend fun installUpdate() {
        val update = _state.value as? CertifiedPropsState.UpdateAvailable ?: return
        val payload = pendingPayload ?: return
        _state.value = CertifiedPropsState.Installing(
            update.installedVersion,
            update.remoteVersion,
        )

        val applied = withContext(Dispatchers.IO) { applyPayload(payload) }
        _state.value = if (applied) {
            pendingPayload = null
            CertifiedPropsState.Installed(update.remoteVersion)
        } else {
            CertifiedPropsState.Error(
                update.installedVersion,
                CertifiedPropsError.INSTALL_FAILED,
            )
        }
    }

    private fun applyPayload(payload: String): Boolean {
        return try {
            val ok = Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.FETCHED_PIF,
                payload,
            )
            if (ok) restartIntegrityProcesses()
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store certified props", e)
            false
        }
    }

    // PropImitationHooks only runs at process start, so restart the processes that use it.
    private fun restartIntegrityProcesses() {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        TARGET_PACKAGES.forEach { pkg ->
            try {
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to kill $pkg", e)
            }
        }
    }

    private fun readInstalledPayload(): String? {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.FETCHED_PIF,
        )
        return sanitize(raw)?.toString()
    }

    private fun getLocalVersion(): Long = versionOf(readInstalledPayload())

    private fun versionOf(payload: String?): Long {
        if (payload.isNullOrBlank()) return -1
        return try {
            JSONObject(payload).optString(KEY_INCREMENTAL).toLongOrNull() ?: -1
        } catch (_: JSONException) {
            -1
        }
    }

    /**
     * Keeps only whitelisted string fields (so the remote file can't touch arbitrary
     * Build fields) and makes sure the mandatory ones are present. Field order is fixed,
     * which makes toString() comparable between remote and stored payloads.
     */
    private fun sanitize(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        return try {
            val src = JSONObject(raw)
            val out = JSONObject()
            for (key in ALLOWED_KEYS) {
                val value = src.optString(key, "").trim()
                if (value.isNotEmpty()) out.put(key, value)
            }
            if (REQUIRED_KEYS.any { !out.has(it) }) null else out
        } catch (_: JSONException) {
            null
        }
    }

    private fun download(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_CONNECT
                readTimeout = TIMEOUT_READ
                useCaches = false
                requestMethod = "GET"
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Unexpected HTTP ${conn.responseCode}")
                return null
            }
            val bytes = conn.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val TAG = "CertifiedProps"
        const val TIMEOUT_CONNECT = 15000
        const val TIMEOUT_READ = 30000
        const val MAX_BYTES = 16 * 1024
        const val KEY_INCREMENTAL = "VERSION.INCREMENTAL"

        val TARGET_PACKAGES = listOf("com.google.android.gms", "com.android.vending")

        val REQUIRED_KEYS = listOf(
            "MANUFACTURER", "MODEL", "FINGERPRINT", "BRAND", "PRODUCT", "DEVICE",
        )
        val ALLOWED_KEYS = REQUIRED_KEYS + listOf(
            "HARDWARE", "BOARD", "ID", "TYPE", "TAGS",
            "VERSION.RELEASE", KEY_INCREMENTAL, "VERSION.SECURITY_PATCH",
            "VERSION.DEVICE_INITIAL_SDK_INT",
        )
    }
}
