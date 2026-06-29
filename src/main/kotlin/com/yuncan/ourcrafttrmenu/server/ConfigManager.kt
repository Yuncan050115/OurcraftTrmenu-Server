package com.yuncan.ourcrafttrmenu.server

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * 配置管理器
 */
class ConfigManager(private val plugin: JavaPlugin) {

    private lateinit var cfg: FileConfiguration

    var serverEnabled: Boolean = true
        private set
    var serverPort: Int = 8765
        private set
    var serverHost: String = "0.0.0.0"
        private set
    var authToken: String = ""
        private set
    var localhostOnly: Boolean = false
        private set
    var allowedOrigins: List<String> = listOf("*")
        private set
    var trmenuMenusFolder: String = "TrMenu/menus"
        private set
    /** 服务器模式：启用自动备份 */
    var serverMode: Boolean = true
        private set
    /** 最大备份数量 */
    var maxBackups: Int = 20
        private set

    fun load() {
        plugin.reloadConfig()
        cfg = plugin.config

        serverEnabled = cfg.getBoolean("server.enabled", true)
        serverPort = cfg.getInt("server.port", 8765)
        serverHost = cfg.getString("server.host", "0.0.0.0") ?: "0.0.0.0"
        authToken = cfg.getString("server.token", "") ?: ""
        localhostOnly = cfg.getBoolean("security.localhost-only", false)
        allowedOrigins = cfg.getStringList("security.allowed-origins").ifEmpty { listOf("*") }
        trmenuMenusFolder = normalizePluginRelativePath(
            cfg.getString("trmenu.menus-folder", "TrMenu/menus") ?: "TrMenu/menus",
        )
        serverMode = cfg.getBoolean("backup.enabled", true)
        maxBackups = cfg.getInt("backup.max-count", 20)
    }

    private fun normalizePluginRelativePath(raw: String): String {
        val trimmed = raw.trim().replace('\\', '/').trim('/')
        return when {
            trimmed.equals("plugins", ignoreCase = true) -> ""
            trimmed.startsWith("plugins/", ignoreCase = true) -> trimmed.substringAfter('/')
            else -> trimmed
        }
    }
}
