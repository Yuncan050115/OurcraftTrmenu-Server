package com.yuncan.ourcrafttrmenu.server

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

/**
 * /octs 命令处理器
 */
class OctsCommand(private val plugin: OurcraftTrmenuServer) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("ourcrafttrmenu.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令")
                    return true
                }
                plugin.reloadAll()
                sender.sendMessage("§a[OurcraftTrmenu-Server] 配置已重载")
            }
            "status" -> {
                if (!sender.hasPermission("ourcrafttrmenu.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令")
                    return true
                }
                val status = try {
                    val ws = plugin.wsServer
                    "§aWebSocket 端口: ${plugin.configManager.serverPort}\n§a认证: ${if (plugin.configManager.authToken.isNotEmpty()) "已启用" else "未启用"}\n§aTrMenu 目录: ${plugin.configManager.trmenuMenusFolder}"
                } catch (e: UninitializedPropertyAccessException) {
                    "§cWebSocket 服务器未启动（配置中已禁用）"
                }
                sender.sendMessage(status)
            }
            "broadcast" -> {
                if (!sender.hasPermission("ourcrafttrmenu.admin")) {
                    sender.sendMessage("§c你没有权限执行此命令")
                    return true
                }
                val msg = args.drop(1).joinToString(" ")
                if (msg.isEmpty()) {
                    sender.sendMessage("§c用法: /octs broadcast <msg>")
                    return true
                }
                try {
                    val ws = plugin.wsServer
                    val payload = com.google.gson.JsonObject().apply {
                        addProperty("message", msg)
                        addProperty("sender", sender.name)
                    }
                    ws.broadcastNotification("server_broadcast", payload)
                    sender.sendMessage("§a已向所有连接的客户端推送消息")
                } catch (e: UninitializedPropertyAccessException) {
                    sender.sendMessage("§cWebSocket 服务器未启动")
                }
            }
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§6=== OurcraftTrmenu-Server 命令 ===")
        sender.sendMessage("§e/octs reload §7- 重载配置")
        sender.sendMessage("§e/octs status §7- 查看服务器状态")
        sender.sendMessage("§e/octs broadcast <msg> §7- 向所有客户端推送消息")
    }
}
