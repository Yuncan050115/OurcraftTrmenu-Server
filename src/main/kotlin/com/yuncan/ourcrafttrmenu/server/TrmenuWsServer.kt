package com.yuncan.ourcrafttrmenu.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.bukkit.plugin.java.JavaPlugin
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * TrMenu WebSocket 服务器
 *
 * 协议规范：
 * - 客户端连接时通过 ?token=xxx 携带认证 Token
 * - 消息格式：JSON `{ type, id, data }`
 * - 服务端响应：`{ type: "<req>_response", id, data, error? }`
 * - 服务端通知：`{ type: "notification", data: { event, payload } }`
 * - 心跳：客户端 ping → 服务端 pong
 */
class TrmenuWsServer(
    private val plugin: JavaPlugin,
    private val config: ConfigManager,
) : WebSocketServer(InetSocketAddress(config.serverHost, config.serverPort)) {

    private val gson = Gson()
    private val connectedClients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val menuService = MenuService(plugin, config)

    override fun onStart() {
        plugin.logger.info("[OurcraftTrmenu-Server] WebSocket 服务器启动于 ${address}")
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        // Token 认证
        if (config.authToken.isNotEmpty()) {
            val token = extractToken(handshake.resourceDescriptor)
            if (token != config.authToken) {
                plugin.logger.warning("[OurcraftTrmenu-Server] 客户端 ${conn.remoteSocketAddress} 认证失败")
                sendError(conn, "auth_failed", "认证失败：Token 不正确")
                conn.close(4401, "Unauthorized")
                return
            }
        }
        // localhost 限制
        if (config.localhostOnly) {
            val addr = conn.remoteSocketAddress.address
            if (!addr.isLoopbackAddress) {
                plugin.logger.warning("[OurcraftTrmenu-Server] 拒绝非本地连接: ${conn.remoteSocketAddress}")
                conn.close(4403, "Forbidden")
                return
            }
        }
        connectedClients.add(conn)
        plugin.logger.info("[OurcraftTrmenu-Server] 客户端已连接: ${conn.remoteSocketAddress}（当前 ${connectedClients.size} 个）")

        // 发送欢迎消息
        val welcome = JsonObject().apply {
            addProperty("type", "welcome")
            addProperty("data", "Connected to OurcraftTrmenu-Server v${plugin.description.version}")
        }
        conn.send(gson.toJson(welcome))
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connectedClients.remove(conn)
        plugin.logger.info("[OurcraftTrmenu-Server] 客户端断开: ${conn.remoteSocketAddress} (code=$code, reason=$reason)")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val obj = JsonParser.parseString(message).asJsonObject
            val type = obj.get("type")?.asString ?: return
            val id = obj.get("id")?.asString ?: "unknown"
            val data = obj.get("data")

            when (type) {
                "ping" -> sendPong(conn, id)
                "list_menus" -> handleListMenus(conn, id)
                "read_menu" -> handleReadMenu(conn, id, data?.asString ?: "")
                "write_menu" -> handleWriteMenu(conn, id, data)
                "delete_menu" -> handleDeleteMenu(conn, id, data?.asString ?: "")
                "copy_menu" -> handleCopyMenu(conn, id, data)
                "list_backups" -> handleListBackups(conn, id, data?.asString ?: "")
                "restore_backup" -> handleRestoreBackup(conn, id, data)
                "export_all_menus" -> handleExportAllMenus(conn, id)
                "list_ia_items" -> handleListIaItems(conn, id)
                "get_ia_item_texture" -> handleGetIaTexture(conn, id, data?.asString ?: "")
                "get_server_info" -> handleGetServerInfo(conn, id)
                "reload_trmenu" -> handleReloadTrmenu(conn, id)
                else -> sendError(conn, id, "未知请求类型: $type")
            }
        } catch (e: Exception) {
            plugin.logger.warning("[OurcraftTrmenu-Server] 消息处理失败: ${e.message}")
            sendError(conn, "parse_error", "消息解析失败: ${e.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        plugin.logger.warning("[OurcraftTrmenu-Server] WebSocket 错误: ${ex.message}")
    }

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        // 二进制消息暂不处理
    }

    // ============ 协议处理 ============

    private fun handleListMenus(conn: WebSocket, id: String) {
        val menus = menuService.listMenus()
        val data = JsonObject().apply {
            add("menus", gson.toJsonTree(menus))
        }
        sendResponse(conn, "list_menus_response", id, data)
    }

    private fun handleReadMenu(conn: WebSocket, id: String, name: String) {
        try {
            val content = menuService.readMenu(name)
            val data = JsonObject().apply {
                addProperty("name", name)
                addProperty("content", content)
            }
            sendResponse(conn, "read_menu_response", id, data)
        } catch (e: Exception) {
            sendError(conn, id, "读取菜单失败: ${e.message}")
        }
    }

    private fun handleWriteMenu(conn: WebSocket, id: String, data: com.google.gson.JsonElement?) {
        try {
            val obj = data?.asJsonObject ?: throw IllegalStateException("data 不是对象")
            val name = obj.get("name").asString
            val content = obj.get("content").asString
            menuService.writeMenu(name, content)
            sendResponse(conn, "write_menu_response", id, JsonObject().apply {
                addProperty("name", name)
                addProperty("success", true)
            })
            // 广播给其他客户端
            broadcastNotification("menu_changed", JsonObject().apply {
                addProperty("name", name)
                addProperty("changed_by", "websocket")
            }, exclude = conn)
        } catch (e: Exception) {
            sendError(conn, id, "写入菜单失败: ${e.message}")
        }
    }

    private fun handleListIaItems(conn: WebSocket, id: String) {
        val items = menuService.listIaItems()
        val data = JsonObject().apply {
            add("items", gson.toJsonTree(items))
        }
        sendResponse(conn, "list_ia_items_response", id, data)
    }

    private fun handleDeleteMenu(conn: WebSocket, id: String, name: String) {
        try {
            val success = menuService.deleteMenu(name)
            sendResponse(conn, "delete_menu_response", id, JsonObject().apply {
                addProperty("name", name)
                addProperty("success", success)
            })
            if (success) {
                broadcastNotification("menu_changed", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("action", "deleted")
                }, exclude = conn)
            }
        } catch (e: Exception) {
            sendError(conn, id, "删除菜单失败: ${e.message}")
        }
    }

    private fun handleCopyMenu(conn: WebSocket, id: String, data: com.google.gson.JsonElement?) {
        try {
            val obj = data?.asJsonObject ?: throw IllegalStateException("data 不是对象")
            val src = obj.get("src").asString
            val dest = obj.get("dest").asString
            val success = menuService.copyMenu(src, dest)
            sendResponse(conn, "copy_menu_response", id, JsonObject().apply {
                addProperty("src", src)
                addProperty("dest", dest)
                addProperty("success", success)
            })
        } catch (e: Exception) {
            sendError(conn, id, "复制菜单失败: ${e.message}")
        }
    }

    private fun handleListBackups(conn: WebSocket, id: String, name: String) {
        val backups = menuService.listBackups(name)
        sendResponse(conn, "list_backups_response", id, JsonObject().apply {
            addProperty("name", name)
            add("backups", gson.toJsonTree(backups))
        })
    }

    private fun handleRestoreBackup(conn: WebSocket, id: String, data: com.google.gson.JsonElement?) {
        try {
            val obj = data?.asJsonObject ?: throw IllegalStateException("data 不是对象")
            val name = obj.get("name").asString
            val backupName = obj.get("backup").asString
            val success = menuService.restoreBackup(name, backupName)
            sendResponse(conn, "restore_backup_response", id, JsonObject().apply {
                addProperty("name", name)
                addProperty("backup", backupName)
                addProperty("success", success)
            })
            if (success) {
                broadcastNotification("menu_changed", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("action", "restored")
                }, exclude = conn)
            }
        } catch (e: Exception) {
            sendError(conn, id, "恢复备份失败: ${e.message}")
        }
    }

    private fun handleExportAllMenus(conn: WebSocket, id: String) {
        try {
            val zipBytes = menuService.exportAllMenus()
            val base64 = java.util.Base64.getEncoder().encodeToString(zipBytes)
            sendResponse(conn, "export_all_menus_response", id, JsonObject().apply {
                addProperty("zip", base64)
                addProperty("size", zipBytes.size)
            })
        } catch (e: Exception) {
            sendError(conn, id, "导出菜单失败: ${e.message}")
        }
    }

    private fun handleGetIaTexture(conn: WebSocket, id: String, itemId: String) {
        try {
            val texture = menuService.getIaItemTexture(itemId)
            val data = JsonObject().apply {
                addProperty("item_id", itemId)
                addProperty("texture", texture)
            }
            sendResponse(conn, "get_ia_item_texture_response", id, data)
        } catch (e: Exception) {
            sendError(conn, id, "获取 IA 贴图失败: ${e.message}")
        }
    }

    private fun handleGetServerInfo(conn: WebSocket, id: String) {
        val info = menuService.getServerInfo()
        sendResponse(conn, "get_server_info_response", id, info)
    }

    private fun handleReloadTrmenu(conn: WebSocket, id: String) {
        try {
            menuService.reloadTrmenu()
            sendResponse(conn, "reload_trmenu_response", id, JsonObject().apply {
                addProperty("success", true)
            })
            broadcastNotification("trmenu_reloaded", JsonObject())
        } catch (e: Exception) {
            sendError(conn, id, "重载 TrMenu 失败: ${e.message}")
        }
    }

    // ============ 工具方法 ============

    fun startAsync() {
        Thread {
            try {
                start()
            } catch (e: Exception) {
                plugin.logger.severe("[OurcraftTrmenu-Server] WebSocket 启动失败: ${e.message}")
            }
        }.apply {
            name = "OurcraftTrmenu-WS"
            isDaemon = true
        }.start()
    }

    fun stopSync() {
        try {
            stop(0)
        } catch (e: Exception) {
            plugin.logger.warning("[OurcraftTrmenu-Server] 停止 WebSocket 失败: ${e.message}")
        }
    }

    fun broadcastNotification(event: String, payload: JsonObject, exclude: WebSocket? = null) {
        val msg = JsonObject().apply {
            addProperty("type", "notification")
            add("data", JsonObject().apply {
                addProperty("event", event)
                add("payload", payload)
            })
        }
        val json = gson.toJson(msg)
        for (client in connectedClients) {
            if (client !== exclude && client.isOpen) {
                client.send(json)
            }
        }
    }

    private fun sendResponse(conn: WebSocket, type: String, id: String, data: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("type", type)
            addProperty("id", id)
            add("data", data)
        }
        conn.send(gson.toJson(msg))
    }

    private fun sendError(conn: WebSocket, id: String, message: String) {
        val msg = JsonObject().apply {
            addProperty("type", "error")
            addProperty("id", id)
            addProperty("error", message)
        }
        conn.send(gson.toJson(msg))
    }

    private fun sendPong(conn: WebSocket, id: String) {
        val msg = JsonObject().apply {
            addProperty("type", "pong")
            addProperty("id", id)
        }
        conn.send(gson.toJson(msg))
    }

    private fun extractToken(resourceDescriptor: String): String {
        val qIndex = resourceDescriptor.indexOf('?')
        if (qIndex < 0) return ""
        val query = resourceDescriptor.substring(qIndex + 1)
        for (pair in query.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "token") {
                return java.net.URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        return ""
    }
}
