package com.iconforge.app.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * In-app updates from the project's GitHub releases. The only network access the app makes:
 * one request to api.github.com for the latest release, and the APK download when you accept.
 */
object Updater {
    const val REPO = "Abhi-DBZ/IconMixer"
    const val PAGE = "https://github.com/$REPO"

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val page: String)

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "IconMixer-Android")
    }

    /** Latest published release with an APK attached, or null. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = open("https://api.github.com/repos/$REPO/releases/latest")
        try {
            if (c.responseCode != 200) error("GitHub answered ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.optJSONArray("assets")
            var apk: JSONObject? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
            apk ?: return@withContext null
            Release(
                version = j.optString("tag_name").removePrefix("v"),
                notes = j.optString("body"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                page = j.optString("html_url", PAGE),
            )
        } finally { c.disconnect() }
    }

    /** True when [remote] (e.g. "1.10") is newer than [local] (e.g. "1.9.1"). */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the release APK into [dir]; [progress] gets 0..1. */
    suspend fun download(release: Release, dir: File, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "IconMixer-${release.version}.apk")
        val c = open(release.apkUrl)
        try {
            if (c.responseCode != 200) error("Download failed (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally { c.disconnect() }
        out
    }
}
