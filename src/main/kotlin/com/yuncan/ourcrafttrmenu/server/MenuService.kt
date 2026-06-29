package com.yuncan.ourcrafttrmenu.server

import com.google.gson.JsonObject
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

private fun resolveConfiguredMenusDir(plugin: JavaPlugin, config: ConfigManager): File {
    val configuredPath = normalizePluginRelativePath(config.trmenuMenusFolder)
    val configured = File(configuredPath)
    if (configured.isAbsolute) return configured

    val pluginsDir = plugin.dataFolder.parentFile
    val candidates = listOf(
        File(pluginsDir, configuredPath),
        File(pluginsDir, "TrMenu/menus"),
        File(pluginsDir, "TrMenu/menu"),
    ).distinctBy { it.absolutePath }

    return candidates.firstOrNull { it.exists() && it.isDirectory } ?: candidates.first()
}

private fun normalizePluginRelativePath(raw: String): String {
    return raw.replace('\\', '/')
        .trim('/')
        .removePrefix("plugins/")
        .removePrefix("Plugins/")
}

/**
 * 菜单服务：处理菜单文件读写、ItemsAdder 集成、TrMenu 重载
 */
class MenuService(
    private val plugin: JavaPlugin,
    private val config: ConfigManager,
) {

    private val menusDir: File by lazy {
        resolveMenusDir()
    }

    private fun resolveMenusDir(): File {
        val configuredPath = normalizePluginRelativePath(config.trmenuMenusFolder)
        val configured = File(configuredPath)
        if (configured.isAbsolute) return configured

        val pluginsDir = plugin.dataFolder.parentFile
        val candidates = listOf(
            File(pluginsDir, configuredPath),
            File(pluginsDir, "TrMenu/menus"),
            File(pluginsDir, "TrMenu/menu"),
        ).distinctBy { it.absolutePath }

        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: candidates.first()
    }

    /** 列出所有菜单名 */
    fun listMenus(): List<String> {
        if (!menusDir.exists()) return emptyList()
        return menusDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            .mapNotNull { file ->
                file.relativeToOrNull(menusDir)
                    ?.invariantSeparatorsPath
                    ?.removeSuffix(".yml")
                    ?.removeSuffix(".yaml")
            }
            .sorted()
            .toList()
    }

    /** 读取菜单内容 */
    fun readMenu(name: String): String {
        val file = findMenuFile(name) ?: throw IllegalStateException("菜单 $name 不存在")
        return file.readText(Charsets.UTF_8)
    }

    /** 写入菜单内容（服务器模式自动备份） */
    fun writeMenu(name: String, content: String) {
        if (!menusDir.exists()) menusDir.mkdirs()
        val file = File(menusDir, "$name.yml")
        file.parentFile?.mkdirs()
        // 服务器模式：写入前自动备份原文件
        if (config.serverMode && file.exists()) {
            backupMenu(name)
        }
        file.writeText(content, Charsets.UTF_8)
        plugin.logger.info("[OurcraftTrmenu-Server] 菜单 $name 已通过 WebSocket 更新")
    }

    /** 备份菜单文件到 backups 目录 */
    fun backupMenu(name: String): File? {
        val src = findMenuFile(name) ?: return null
        val backupDir = File(menusDir.parentFile, "backups").apply { if (!exists()) mkdirs() }
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(java.util.Date())
        val backupFile = File(backupDir, "${name.replace('/', '_')}_${timestamp}.yml")
        src.copyTo(backupFile, overwrite = true)
        // 清理旧备份：只保留最近 20 个
        val backups = backupDir.listFiles { f -> f.name.startsWith("${name}_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (backups.size > 20) {
            backups.drop(20).forEach { it.delete() }
        }
        plugin.logger.info("[OurcraftTrmenu-Server] 菜单 $name 已备份到 ${backupFile.name}")
        return backupFile
    }

    /** 列出菜单的备份列表 */
    fun listBackups(name: String): List<JsonObject> {
        val backupDir = File(menusDir.parentFile, "backups")
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { f -> f.name.startsWith("${name}_") && f.name.endsWith(".yml") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                JsonObject().apply {
                    addProperty("name", f.name)
                    addProperty("size", f.length())
                    addProperty("lastModified", f.lastModified())
                }
            } ?: emptyList()
    }

    /** 从备份恢复菜单 */
    fun restoreBackup(name: String, backupName: String): Boolean {
        val backupDir = File(menusDir.parentFile, "backups")
        val backupFile = File(backupDir, backupName)
        if (!backupFile.exists()) return false
        val target = File(menusDir, "$name.yml")
        // 恢复前先备份当前版本
        if (target.exists()) backupMenu(name)
        backupFile.copyTo(target, overwrite = true)
        plugin.logger.info("[OurcraftTrmenu-Server] 菜单 $name 已从备份 $backupName 恢复")
        return true
    }

    /** 删除菜单文件 */
    fun deleteMenu(name: String): Boolean {
        val file = findMenuFile(name) ?: return false
        // 删除前备份
        if (config.serverMode) backupMenu(name)
        return file.delete()
    }

    /** 复制菜单 */
    fun copyMenu(srcName: String, destName: String): Boolean {
        val src = findMenuFile(srcName) ?: return false
        if (!menusDir.exists()) menusDir.mkdirs()
        val dest = File(menusDir, "$destName.yml")
        if (dest.exists()) return false
        src.copyTo(dest)
        plugin.logger.info("[OurcraftTrmenu-Server] 菜单 $srcName 已复制为 $destName")
        return true
    }

    /** 导出所有菜单为 ZIP */
    fun exportAllMenus(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            val files = menusDir.listFiles { f -> f.isFile && (f.name.endsWith(".yml") || f.name.endsWith(".yaml")) }
                ?: return ByteArray(0)
            for (file in files.sortedBy { it.name }) {
                zos.putNextEntry(java.util.zip.ZipEntry(file.name))
                zos.write(file.readBytes())
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /** 列出 ItemsAdder contents 下的自定义物品与 font_images */
    fun listIaItems(): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        val contentsDir = File(plugin.dataFolder.parentFile, "ItemsAdder/contents")
        if (!contentsDir.exists()) return emptyList()
        try {
            contentsDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
                .filter {
                    val path = it.invariantSeparatorsPath.lowercase()
                    path.contains("/items_packs/") || path.contains("/font_images/") || path.contains("/configs/")
                }
                .forEach { file ->
                    try {
                        val cfg = org.bukkit.configuration.file.YamlConfiguration()
                        cfg.load(file)
                        val namespace = cfg.getString("info.namespace") ?: inferIaNamespace(contentsDir, file)

                        cfg.getConfigurationSection("items")?.getKeys(false)?.forEach { itemId ->
                            val prefix = "items.$itemId"
                            val textures = cfg.getStringList("$prefix.resource.textures")
                                .ifEmpty { cfg.getString("$prefix.resource.textures")?.let { listOf(it) } ?: emptyList() }
                            val texturePath = textures.firstOrNull()?.let { resolveIaTexture(contentsDir, namespace, it, file) }
                            items.add(JsonObject().apply {
                                addProperty("id", "$namespace:$itemId")
                                addProperty("idOnly", itemId)
                                addProperty("namespace", namespace)
                                addProperty("displayName", cfg.getString("$prefix.display_name") ?: itemId)
                                addProperty("type", "item")
                                if (texturePath != null) addProperty("texturePath", texturePath.absolutePath)
                            })
                        }

                        cfg.getConfigurationSection("font_images")?.getKeys(false)?.forEach { imageId ->
                            val path = cfg.getString("font_images.$imageId.path") ?: "$imageId.png"
                            val texturePath = resolveIaTexture(contentsDir, namespace, path, file)
                            items.add(JsonObject().apply {
                                addProperty("id", "$namespace:$imageId")
                                addProperty("idOnly", imageId)
                                addProperty("namespace", namespace)
                                addProperty("displayName", imageId)
                                addProperty("type", "font_image")
                                addProperty("texturePath", texturePath.absolutePath)
                            })
                        }
                    } catch (e: Exception) {
                        plugin.logger.warning("[OurcraftTrmenu-Server] 跳过 IA 配置 ${file.name}: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            plugin.logger.warning("[OurcraftTrmenu-Server] 列出 IA 资源失败: ${e.message}")
        }
        return items.distinctBy { it.get("id")?.asString ?: it.toString() }
    }

    private fun inferIaNamespace(contentsDir: File, file: File): String {
        val rel = file.relativeTo(contentsDir).invariantSeparatorsPath
        return rel.substringBefore('/').ifBlank { "default" }
    }

    private fun resolveIaTexture(contentsDir: File, namespace: String, texture: String, configFile: File): File {
        val cleaned = texture.replace('\\', '/')
            .removePrefix("minecraft:")
            .removePrefix("$namespace:")
            .removePrefix("textures/")
            .removePrefix("$namespace/textures/")
            .let { if (it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".webp")) it else "$it.png" }
        val namespaceDir = File(contentsDir, namespace)
        val candidates = listOf(
            File(namespaceDir, "resource_pack/$namespace/textures/$cleaned"),
            File(namespaceDir, "resourcepack/$namespace/textures/$cleaned"),
            File(namespaceDir, "resourcepack/assets/$namespace/textures/$cleaned"),
            File(namespaceDir, "assets/$namespace/textures/$cleaned"),
            File(namespaceDir, "textures/$cleaned"),
            File(configFile.parentFile, cleaned),
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    /** 获取 IA 物品贴图（Base64） */
    fun getIaItemTexture(itemId: String): String {
        val stack = CustomStack.getInstance(itemId)
            ?: throw IllegalStateException("IA 物品 $itemId 不存在")
        // 通过纹理路径返回
        // ItemsAdder 内部使用 Font Image，这里返回物品 display name 作为占位
        // 实际生产可调用更底层 API 获取像素贴图数据
        return stack.itemStack.type.name
    }

    /** 获取服务器信息 */
    fun getServerInfo(): JsonObject {
        return JsonObject().apply {
            addProperty("serverName", Bukkit.getName())
            addProperty("serverVersion", Bukkit.getVersion())
            addProperty("trmenuVersion", Bukkit.getPluginManager().getPlugin("TrMenu")?.description?.version ?: "未安装")
            addProperty("hasItemsAdder", Bukkit.getPluginManager().getPlugin("ItemsAdder") != null)
            val players = Bukkit.getOnlinePlayers().map { it.name }
            add("onlinePlayers", com.google.gson.Gson().toJsonTree(players))
        }
    }

    /** 重载 TrMenu */
    fun reloadTrmenu() {
        val trmenu = Bukkit.getPluginManager().getPlugin("TrMenu")
            ?: throw IllegalStateException("TrMenu 未安装")
        // 通过反射调用 TrMenu 的 reload 命令
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "trmenu reload")
        plugin.logger.info("[OurcraftTrmenu-Server] TrMenu 已重载")
    }

    private fun findMenuFile(name: String): File? {
        if (!isSafeMenuName(name)) return null
        val yml = File(menusDir, "$name.yml")
        if (yml.exists()) return yml
        val yaml = File(menusDir, "$name.yaml")
        if (yaml.exists()) return yaml
        return null
    }

    /**
     * 校验菜单名安全性：禁止路径分隔符与父目录引用，避免路径穿越逃出 plugins 目录
     */
    private fun isSafeMenuName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.contains('\\') || name.contains("..")) return false
        if (name.startsWith('/') || name.endsWith('/')) return false
        return true
    }
}

/**
 * 菜单文件变更监听器
 *
 * 监听 TrMenu 菜单目录文件变化，自动推送通知给 WebSocket 客户端
 */
class MenuWatcher(
    private val plugin: JavaPlugin,
    private val config: ConfigManager,
) {
    private var watchThread: Thread? = null
    @Volatile private var running = false
    private val lastModifiedMap = ConcurrentHashMap<String, Long>()

    fun start() {
        val dir = resolveConfiguredMenusDir(plugin, config)
        if (!dir.exists()) {
            plugin.logger.warning("[OurcraftTrmenu-Server] 菜单目录不存在: ${dir.absolutePath}")
            return
        }
        running = true
        watchThread = Thread {
            while (running) {
                try {
                    scanDirectory(dir)
                    Thread.sleep(2000) // 2 秒扫描间隔
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    plugin.logger.warning("[OurcraftTrmenu-Server] 菜单监听异常: ${e.message}")
                }
            }
        }.apply {
            name = "OurcraftTrmenu-Watcher"
            isDaemon = true
        }
        watchThread?.start()
        plugin.logger.info("[OurcraftTrmenu-Server] 菜单文件监听器已启动，监听目录: ${dir.absolutePath}")
    }

    fun stop() {
        running = false
        watchThread?.interrupt()
        watchThread = null
    }

    private fun scanDirectory(dir: File) {
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".yml") || f.name.endsWith(".yaml")) }
            ?: return
        for (file in files) {
            val key = file.absolutePath
            val mtime = file.lastModified()
            val prev = lastModifiedMap[key]
            if (prev != null && prev != mtime) {
                // 文件被修改
                plugin.logger.info("[OurcraftTrmenu-Server] 检测到菜单文件变更: ${file.name}")
                // 通知由 WsServer 广播，这里通过 plugin 启动逻辑查找
                val server = (plugin as? com.yuncan.ourcrafttrmenu.server.OurcraftTrmenuServer)?.wsServer
                server?.broadcastNotification("menu_changed", com.google.gson.JsonObject().apply {
                    addProperty("name", file.nameWithoutExtension)
                    addProperty("changed_by", "file_watcher")
                    addProperty("timestamp", mtime)
                })
            }
            lastModifiedMap[key] = mtime
        }
    }
}
