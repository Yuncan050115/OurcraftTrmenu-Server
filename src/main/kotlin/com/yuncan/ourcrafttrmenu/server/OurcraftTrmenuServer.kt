package com.yuncan.ourcrafttrmenu.server

import com.yuncan.ourcrafttrmenu.server.metrics.Metrics
import org.bukkit.plugin.java.JavaPlugin

/**
 * OurcraftTrmenu-Server 主插件类
 *
 * 提供 WebSocket 服务器，让 OurcraftTrmenu 桌面编辑器可以实时同步菜单配置。
 *
 * @author 云灿 (Yuncan)
 */
class OurcraftTrmenuServer : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set

    lateinit var wsServer: TrmenuWsServer
        private set

    lateinit var menuWatcher: MenuWatcher
        private set

    override fun onEnable() {
        printBanner()
        // bStats 统计（pluginId = 32221）
        Metrics(this, 32221)

        // 保存默认配置
        saveDefaultConfig()
        configManager = ConfigManager(this)
        configManager.load()

        if (!configManager.serverEnabled) {
            logger.info("WebSocket 服务器已在配置中禁用，跳过启动")
            return
        }

        // 启动 WebSocket 服务器（异步，避免阻塞主线程）
        wsServer = TrmenuWsServer(this, configManager)
        wsServer.startAsync()

        // 启动菜单文件监听器
        menuWatcher = MenuWatcher(this, configManager)
        menuWatcher.start()

        // 注册命令
        val command = getCommand("octs") ?: run {
            logger.severe("无法注册命令 octs，请检查 plugin.yml")
            return
        }
        command.setExecutor(OctsCommand(this))

        logger.info("OurcraftTrmenu-Server v${description.version} 已启用")
        logger.info("WebSocket 监听: ${configManager.serverHost}:${configManager.serverPort}")
    }

    override fun onDisable() {
        if (::wsServer.isInitialized) {
            try {
                wsServer.stopSync()
            } catch (e: Exception) {
                logger.warning("关闭 WebSocket 服务器时出错: ${e.message}")
            }
        }
        if (::menuWatcher.isInitialized) {
            menuWatcher.stop()
        }
        logger.info("OurcraftTrmenu-Server 已禁用")
    }

    fun reloadAll() {
        reloadConfig()
        configManager.load()
        if (::wsServer.isInitialized) {
            wsServer.stopSync()
        }
        if (configManager.serverEnabled) {
            wsServer = TrmenuWsServer(this, configManager)
            wsServer.startAsync()
        }
    }

    private fun printBanner() {
        listOf(
            "██╗   ██╗██╗   ██╗███╗   ██╗ ██████╗ █████╗ ███╗   ██╗",
            "╚██╗ ██╔╝██║   ██║████╗  ██║██╔════╝██╔══██╗████╗  ██║",
            " ╚████╔╝ ██║   ██║██╔██╗ ██║██║     ███████║██╔██╗ ██║",
            "  ╚██╔╝  ██║   ██║██║╚██╗██║██║     ██╔══██║██║╚██╗██║",
            "   ██║   ╚██████╔╝██║ ╚████║╚██████╗██║  ██║██║ ╚████║",
            "   ╚═╝    ╚═════╝ ╚═╝  ╚═══╝ ╚═════╝╚═╝  ╚═╝╚═╝  ╚═══╝",
            "        ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓",
            "      ▓▓██Yuncan-Ourcraft服务器出品████▓▓",
            "    ▓▓██                      ████▓▓",
            "      ▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀",
        ).forEach { logger.info(it) }
    }
}
