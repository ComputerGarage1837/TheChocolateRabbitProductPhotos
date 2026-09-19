package com.chocolaterabbit.productphotos.update

import android.net.Uri
import com.chocolaterabbit.productphotos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the repository's `release/update.json` feed (see UPDATE_FORMAT.md) and returns the
 * newest release. The feed names the APK asset on the matching GitHub release (`v<versionName>`)
 * and carries its SHA-256 and size so the download can be verified before install.
 */
object UpdateChecker {

    data class Release(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val assetName: String,
        val sha256: String?,
        val sizeBytes: Long,
        val htmlUrl: String,
    )

    private const val SUPPORTED_SCHEMA = 1
    private val repo: String get() = BuildConfig.GITHUB_REPO

    // The Contents API is not behind GitHub's raw-file CDN cache, so a check right after a
    // release sees the new feed. raw.githubusercontent.com is the fallback.
    private val feedApiUrl get() = "https://api.github.com/repos/$repo/contents/release/update.json?ref=main"
    private val feedRawUrl get() = "https://raw.githubusercontent.com/$repo/main/release/update.json"

    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE
    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    /** Fetches and validates the feed. Throws with a user-readable message on failure. */
    suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val feed = runCatching { getJson(feedApiUrl, accept = "application/vnd.github.raw") }.getOrNull()
            ?: getJson(feedRawUrl, accept = "application/json")
            ?: throw IllegalStateException("Update feed not found")

        val schema = feed.optInt("schemaVersion", 0)
        if (schema != SUPPORTED_SCHEMA) throw IllegalStateException("Unsupported update feed (schema $schema)")
        val pkg = feed.optString("packageName")
        if (pkg != BuildConfig.APPLICATION_ID) throw IllegalStateException("Update feed is for a different app")

        val versionName = feed.optString("versionName").trim().ifEmpty {
            throw IllegalStateException("Update feed has no version")
        }
        val versionCode = feed.optInt("versionCode", -1).takeIf { it >= 0 }
            ?: throw IllegalStateException("Update feed has no version code")

        val apk = feed.optJSONObject("apk") ?: throw IllegalStateException("Update feed has no APK entry")
        val assetName = apk.optString("assetName").trim().ifEmpty {
            throw IllegalStateException("Update feed has no APK name")
        }
        val sha256 = apk.optString("sha256").trim().lowercase()
            .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val sizeBytes = apk.optLong("sizeBytes", 0L)

        val tag = "v$versionName"
        val apkUrl = "https://github.com/$repo/releases/download/$tag/${Uri.encode(assetName)}"
        val htmlUrl = "https://github.com/$repo/releases/tag/$tag"

        val notes = runCatching {
            getJson("https://api.github.com/repos/$repo/releases/tags/$tag", accept = "application/vnd.github+json")
                ?.optString("body") ?: ""
        }.getOrDefault("")

        Release(versionName, versionCode, plainTextFromMarkdown(notes), apkUrl, assetName, sha256, sizeBytes, htmlUrl)
    }

    /** GET a JSON document; returns null on 404. */
    private fun getJson(url: String, accept: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "ChocolateRabbitProductPhotos")
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IllegalStateException("GitHub returned HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun plainTextFromMarkdown(md: String): String =
        md.lines()
            .takeWhile { !it.trimStart().startsWith("---") } // drop the install footer
            .joinToString("\n") { line ->
                var l = line.trimEnd()
                l = l.replace(Regex("^#{1,6}\\s*"), "")
                l = l.replace(Regex("^\\s*[-*]\\s+"), "• ")
                l = l.replace("**", "").replace("__", "").replace("`", "")
                l
            }.trim()
}
