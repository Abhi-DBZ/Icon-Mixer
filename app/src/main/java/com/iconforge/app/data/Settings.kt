package com.iconforge.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small app preferences, exposed as StateFlows for Compose. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = MutableStateFlow(prefs.getInt(key, def))
    private fun bool(key: String, def: Boolean) = MutableStateFlow(prefs.getBoolean(key, def))

    private val _themeMode = int("themeMode", MODE_SYSTEM)
    private val _pureBlack = bool("pureBlack", false)
    private val _dynamicColor = bool("dynamicColor", true)
    private val _seedColor = int("seedColor", DEFAULT_SEED)
    private val _activeTheme = MutableStateFlow(prefs.getLong("activeTheme", 0L))
    private val _notifyNewApps = bool("notifyNewApps", true)
    private val _exportSize = int("exportSize", 192)
    private val _includeWholePack = bool("includeWholePack", false)
    private val _editorList = bool("editorList", false)
    private val _autoUpdatePack = bool("autoUpdatePack", true)
    private val _liveColours = bool("liveColours", false)
    private val _shapeAllIcons = bool("shapeAllIcons", false)

    val themeMode: StateFlow<Int> = _themeMode
    val pureBlack: StateFlow<Boolean> = _pureBlack
    val dynamicColor: StateFlow<Boolean> = _dynamicColor
    /** App colour (ARGB) used when [dynamicColor] is off or not available (Android < 12). */
    val seedColor: StateFlow<Int> = _seedColor
    val activeTheme: StateFlow<Long> = _activeTheme
    val notifyNewApps: StateFlow<Boolean> = _notifyNewApps
    val exportSize: StateFlow<Int> = _exportSize
    /** Also copy every icon of the base pack into exports (themes apps installed later). */
    val includeWholePack: StateFlow<Boolean> = _includeWholePack
    /** Theme editor shows a list instead of a grid. */
    val editorList: StateFlow<Boolean> = _editorList
    /** Rebuild + update the installed pack by itself when new apps get icons. */
    val autoUpdatePack: StateFlow<Boolean> = _autoUpdatePack
    /** Export Material You (XML) pack icons as live resources instead of images. */
    val liveColours: StateFlow<Boolean> = _liveColours
    /** Cut every icon to the theme's shape, not only adaptive ones. */
    val shapeAllIcons: StateFlow<Boolean> = _shapeAllIcons

    fun setThemeMode(v: Int) { _themeMode.value = v; prefs.edit().putInt("themeMode", v).apply() }
    fun setPureBlack(v: Boolean) { _pureBlack.value = v; prefs.edit().putBoolean("pureBlack", v).apply() }
    /** Picks a fixed app colour (turns wallpaper colours off). */
    fun setSeedColor(v: Int) {
        _seedColor.value = v; prefs.edit().putInt("seedColor", v).apply()
        setDynamicColor(false)
    }
    fun setDynamicColor(v: Boolean) { _dynamicColor.value = v; prefs.edit().putBoolean("dynamicColor", v).apply() }
    fun setActiveTheme(v: Long) { _activeTheme.value = v; prefs.edit().putLong("activeTheme", v).apply() }
    fun setNotifyNewApps(v: Boolean) { _notifyNewApps.value = v; prefs.edit().putBoolean("notifyNewApps", v).apply() }
    fun setExportSize(v: Int) { _exportSize.value = v; prefs.edit().putInt("exportSize", v).apply() }
    fun setAutoUpdatePack(v: Boolean) { _autoUpdatePack.value = v; prefs.edit().putBoolean("autoUpdatePack", v).apply() }
    fun setShapeAllIcons(v: Boolean) { _shapeAllIcons.value = v; prefs.edit().putBoolean("shapeAllIcons", v).apply() }
    fun setLiveColours(v: Boolean) { _liveColours.value = v; prefs.edit().putBoolean("liveColours", v).apply() }
    fun setEditorList(v: Boolean) { _editorList.value = v; prefs.edit().putBoolean("editorList", v).apply() }
    fun setIncludeWholePack(v: Boolean) { _includeWholePack.value = v; prefs.edit().putBoolean("includeWholePack", v).apply() }

    var changedPackagesSeq: Int
        get() = prefs.getInt("changedSeq", 0)
        set(v) { prefs.edit().putInt("changedSeq", v).apply() }

    companion object {
        /** The app icon's indigo. */
        const val DEFAULT_SEED = 0xFF3D4FC4.toInt()
        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2
    }
}
