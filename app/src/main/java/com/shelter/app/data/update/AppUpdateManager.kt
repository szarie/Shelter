package com.shelter.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.shelter.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

enum class ApkVariant {
    UNCOMPRESSED,
    ANDROID7,
    UNIVERSAL,
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("shelter_updates", Context.MODE_PRIVATE)

    private val _updateInfo = MutableStateFlow(
        UpdateInfo(
            currentVersion = getCurrentVersion(),
        )
    )
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    init {
        checkForUpdate(isSilent = true)
    }

    fun isUncompressedBuild(): Boolean {
        val pref = prefs.getString("preferred_variant", null)
        if (pref != null) {
            return pref.equals("uncompressed", ignoreCase = true) || pref.equals("raw", ignoreCase = true)
        }
        val buildType = runCatching { BuildConfig.BUILD_TYPE }.getOrDefault("")
        return buildType.equals("rawRelease", ignoreCase = true) ||
               buildType.contains("raw", ignoreCase = true) ||
               buildType.contains("uncompressed", ignoreCase = true)
    }

    fun getPreferredVariant(): String? = prefs.getString("preferred_variant", null)

    fun setPreferredVariant(variant: String?) {
        if (variant == null) {
            prefs.edit().remove("preferred_variant").apply()
        } else {
            prefs.edit().putString("preferred_variant", variant).apply()
        }
        checkForUpdate(isSilent = true)
    }

    fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.3.1"
    } catch (_: Exception) {
        "4.3.1"
    }

    fun checkForUpdate(isSilent: Boolean = false) {
        scope.launch {
            _updateInfo.update {
                it.copy(
                    isChecking = true,
                    message = if (!isSilent) "Checking for updates..." else it.message,
                )
            }
            try {
                val releaseResult = fetchLatestRelease()
                val tagName: String
                val releaseUrl: String
                val releaseNotes: String
                var apkAssets = emptyList<ReleaseAsset>()

                if (releaseResult != null) {
                    tagName = releaseResult.optString("tag_name", "")
                    releaseUrl = releaseResult.optString("html_url", "https://github.com/szarie/Shelter/releases")
                    releaseNotes = releaseResult.optString("body", "")
                    apkAssets = parseReleaseAssets(releaseResult)
                } else {
                    val latestTag = fetchLatestTag() ?: ""
                    tagName = latestTag
                    releaseUrl = if (latestTag.isBlank()) {
                        "https://github.com/szarie/Shelter/releases"
                    } else {
                        "https://github.com/szarie/Shelter/releases/tag/$latestTag"
                    }
                    releaseNotes = ""
                }

                var bestAsset = selectBestAsset(
                    assets = apkAssets,
                    isUncompressed = isUncompressedBuild(),
                    sdkInt = Build.VERSION.SDK_INT,
                )

                if (bestAsset == null && tagName.isNotBlank()) {
                    bestAsset = probeTagAsset(tagName)
                }

                val downloadUrl = bestAsset?.downloadUrl ?: releaseUrl
                val downloadFileName = bestAsset?.name

                val currentVersion = getCurrentVersion()
                val hasNewer = isNewerVersion(tagName, currentVersion)
                val cleanTag = tagName.removePrefix("v").removePrefix("V")
                val dismissedVersion = prefs.getString("dismissed_version", null)
                val isDismissed = dismissedVersion == cleanTag

                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        isUpdateAvailable = hasNewer,
                        latestVersion = cleanTag,
                        currentVersion = currentVersion,
                        releaseNotes = releaseNotes,
                        releaseUrl = releaseUrl,
                        downloadUrl = downloadUrl,
                        downloadFileName = downloadFileName,
                        isDismissed = isDismissed,
                        message = if (!isSilent) {
                            if (hasNewer) "New version $cleanTag available!" else "You're on the latest version ($currentVersion)"
                        } else null,
                    )
                }
            } catch (e: Exception) {
                _updateInfo.update {
                    it.copy(
                        isChecking = false,
                        message = if (!isSilent) "Check failed: ${e.message ?: "Network error"}" else null,
                    )
                }
            }
        }
    }

    fun dismissUpdate(version: String) {
        val clean = version.removePrefix("v").removePrefix("V")
        prefs.edit().putString("dismissed_version", clean).apply()
        _updateInfo.update { it.copy(isDismissed = true) }
    }

    private fun apiRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "Shelter-Android")
        .build()

    private suspend fun fetchLatestRelease(): JSONObject? = withContext(Dispatchers.IO) {
        okHttpClient.newCall(
            apiRequest("https://api.github.com/repos/szarie/Shelter/releases/latest")
        ).execute().use { response ->
            if (!response.isSuccessful) null
            else JSONObject(response.body?.string().orEmpty())
        }
    }

    private suspend fun fetchLatestTag(): String? = withContext(Dispatchers.IO) {
        okHttpClient.newCall(
            apiRequest("https://api.github.com/repos/szarie/Shelter/tags?per_page=100")
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            val tags = org.json.JSONArray(body)
            val names = buildList {
                for (i in 0 until tags.length()) {
                    val name = tags.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                    if (name.isNotBlank()) add(name)
                }
            }
            names.maxWithOrNull(Comparator { first, second ->
                compareVersions(first, second)
            })
        }
    }

    private suspend fun probeTagAsset(tagName: String): ReleaseAsset? = withContext(Dispatchers.IO) {
        val cleanTag = tagName.removePrefix("v").removePrefix("V")
        val variant = when {
            Build.VERSION.SDK_INT < 29 -> ApkVariant.ANDROID7
            isUncompressedBuild() -> ApkVariant.UNCOMPRESSED
            else -> ApkVariant.UNIVERSAL
        }
        val fileNames = when (variant) {
            ApkVariant.ANDROID7 -> listOf(
                "Shelter-v$cleanTag-android7.apk",
                "Shelter-$cleanTag-android7.apk",
            )
            ApkVariant.UNCOMPRESSED -> listOf(
                "Shelter-v$cleanTag-universal-uncompressed.apk",
                "Shelter-$cleanTag-universal-uncompressed.apk",
            )
            ApkVariant.UNIVERSAL -> listOf(
                "Shelter-v$cleanTag-universal.apk",
                "Shelter-$cleanTag-universal.apk",
            )
        }

        for (fileName in fileNames) {
            val url = "https://github.com/szarie/Shelter/releases/download/$tagName/$fileName"
            okHttpClient.newCall(
                Request.Builder().url(url).head()
                    .header("User-Agent", "Shelter-Android")
                    .build()
            ).execute().use { response ->
                if (response.isSuccessful) {
                    return@withContext ReleaseAsset(name = fileName, downloadUrl = url)
                }
            }
        }
        null
    }

    private fun parseReleaseAssets(release: JSONObject): List<ReleaseAsset> {
        val apkAssets = mutableListOf<ReleaseAsset>()
        val assets = release.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "").trim()
                val download = asset.optString("browser_download_url", "").trim()
                if (name.endsWith(".apk", ignoreCase = true) && download.isNotBlank()) {
                    apkAssets.add(ReleaseAsset(name = name, downloadUrl = download))
                }
            }
        }
        return apkAssets
    }

    fun openUpdate(context: Context) {
        val targetUrl = _updateInfo.value.downloadUrl ?: _updateInfo.value.releaseUrl.ifBlank {
            "https://github.com/szarie/Shelter/releases"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) { }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank() || local.isBlank()) return false
        return compareVersions(remote, local) > 0
    }

    companion object {
        fun compareVersions(first: String, second: String): Int {
            val cleanFirst = first.removePrefix("v").removePrefix("V")
                .substringBefore("-").substringBefore("+")
            val cleanSecond = second.removePrefix("v").removePrefix("V")
                .substringBefore("-").substringBefore("+")
            if (cleanFirst == cleanSecond) return 0

            val fParts = cleanFirst.split(".").mapNotNull { it.toIntOrNull() }
            val sParts = cleanSecond.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(fParts.size, sParts.size)
            for (i in 0 until maxLen) {
                val f = fParts.getOrElse(i) { 0 }
                val s = sParts.getOrElse(i) { 0 }
                if (f != s) return f.compareTo(s)
            }
            return 0
        }

        fun classifyAsset(name: String): ApkVariant {
            val lower = name.lowercase()
            return when {
                lower.contains("uncompressed") || lower.contains("-raw") || lower.contains("_raw") -> ApkVariant.UNCOMPRESSED
                lower.contains("android7") || lower.contains("legacy") -> ApkVariant.ANDROID7
                else -> ApkVariant.UNIVERSAL
            }
        }

        fun selectBestAsset(
            assets: List<ReleaseAsset>,
            isUncompressed: Boolean,
            sdkInt: Int,
        ): ReleaseAsset? {
            val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
            if (apkAssets.isEmpty()) return null

            val target = when {
                sdkInt < 29 -> ApkVariant.ANDROID7
                isUncompressed -> ApkVariant.UNCOMPRESSED
                else -> ApkVariant.UNIVERSAL
            }

            // Direct match for target variant, preferring "universal" naming if present
            val matches = apkAssets.filter { classifyAsset(it.name) == target }
            if (matches.isNotEmpty()) {
                return matches.maxByOrNull { asset ->
                    val lower = asset.name.lowercase()
                    when (target) {
                        ApkVariant.UNIVERSAL -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.UNCOMPRESSED -> if (lower.contains("universal")) 2 else 1
                        ApkVariant.ANDROID7 -> if (lower.contains("android7")) 2 else 1
                    }
                }
            }

            // Fallback hierarchy if target variant was not present in this release:
            return when (target) {
                ApkVariant.UNCOMPRESSED -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.UNIVERSAL -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNCOMPRESSED }
                        ?: apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.ANDROID7 }
                        ?: apkAssets.firstOrNull()
                }
                ApkVariant.ANDROID7 -> {
                    apkAssets.firstOrNull { classifyAsset(it.name) == ApkVariant.UNIVERSAL }
                        ?: apkAssets.firstOrNull()
                }
            }
        }
    }
}
