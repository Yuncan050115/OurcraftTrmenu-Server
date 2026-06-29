# OurcraftTrmenu-Server

一个为 OurcraftTrmenu 桌面编辑器提供 WebSocket 实时同步能力的服务器端辅助插件。

爱发电: [https://ifdian.net/item/8e3901e671fa11f19a1c52540025c377](https://ifdian.net/item/8e3901e671fa11f19a1c52540025c377)

**不安装本插件时，OurcraftTrmenu 仍可通过"直接文件读写模式"使用全部编辑功能。**

## 前置依赖

| 插件 | 版本[以26.1为例] | 必需 | 说明 |
|------|------------------|------|------|
| TrMenu | 3.0+ | ✅ | 菜单插件本体 |
| ItemsAdder | 3.6+ | ❌ | 贴图联动（仅 IA 模式生效） |
| Paper / Leaf/Folia | 1.21.4+ | ✅ | 服务端核心 |

> TrMenu 是必需前置，ItemsAdder 为可选（仅需要 IA 贴图联动时安装）。

## 兼容性

| Minecraft 服务端 | 状态 |
|------------------|------|
| Paper / Purpur / Leaf 1.19.x | ✅ |
| Paper / Purpur / Leaf 1.20.x | ✅ |
| Paper / Purpur / Leaf 1.21.x | ✅ |
| Paper / Purpur / Leaf 26.1.x | ✅ |

> 本插件支持 Folia（通过 RegionizedServer 类检测 + SchedulerUtil 抽象）。

## 功能

- **WebSocket 实时同步**：与桌面编辑器双向通信，修改菜单实时同步到服务器
- **菜单列表拉取**：客户端可获取服务器全部 TrMenu 菜单列表
- **菜单读写**：读取/写入指定菜单的 YAML 配置
- **ItemsAdder 联动**：列出 IA 自定义物品、获取 IA 物品贴图数据
- **文件变更监听**：菜单文件被修改时自动推送给所有连接的客户端
- **远程重载 TrMenu**：客户端可远程触发 `trmenu reload`，无需登录服务器
- **服务器信息查询**：获取服务器版本、在线人数、TrMenu 版本等
- **多客户端协作**：一个客户端修改，其他客户端自动同步
- **Token 认证**：基于 Token 的简单认证，防止未授权访问
- **IP 限频**：防止单 IP 大量连接

## 命令

| 命令 | 别名 | 说明 |
|------|------|------|
| `/octs reload` | — | 重载配置文件 |
| `/octs status` | — | 查看连接状态（在线客户端数、监听端口等） |
| `/octs broadcast <msg>` | — | 向所有连接的客户端推送消息 |

## 权限

| 权限节点 | 默认 | 说明 |
|----------|------|------|
| `ourcrafttrmenu.admin` | OP | 重载配置、查看连接状态、广播消息 |

LuckPerms 示例：

```
/lp group admin permission set ourcrafttrmenu.admin true
```

## 配置文件

`plugins/OurcraftTrmenu-Server/config.yml`：

```yaml
# WebSocket 服务器配置
server:
  enabled: true              # 是否启用 WebSocket 服务器
  port: 8765                 # 监听端口
  host: "0.0.0.0"            # 绑定地址（生产环境建议 127.0.0.1，仅本机访问）
  token: "your-secret-token" # 认证 Token，留空则不启用认证（仅本地调试建议）
  connect-timeout: 5000      # 连接超时（毫秒）
  idle-timeout: 90           # 空闲超时（秒），超时无消息将断开

# TrMenu 菜单目录（自动检测，无需手动配置）
trmenu:
  menus-folder: "plugins/TrMenu/menus"
  watch-changes: true        # 监听文件变更并推送给客户端

# 安全设置
security:
  localhost-only: false      # 仅允许本地连接（生产环境强烈建议 true）
  allowed-origins: ["*"]     # CORS 来源
  max-connections-per-ip: 5  # 单 IP 最大连接数

# 日志
logging:
  verbose: false             # 详细日志
  log-messages: false        # 记录所有消息（调试用，生产环境关闭）
```

## 协议示例

### 客户端请求

```json
{
  "type": "list_menus",
  "id": "req-1",
  "data": null
}
```

### 服务端响应

```json
{
  "type": "list_menus_response",
  "id": "req-1",
  "data": {
    "menus": ["商店菜单", "传送系统", "主菜单"]
  }
}
```

### 服务端通知（文件变更推送）

```json
{
  "type": "notification",
  "data": {
    "event": "menu_changed",
    "payload": { "name": "商店菜单", "changed_by": "Yuncan" }
  }
}
```

### 支持的命令类型

| 命令类型 | 说明 |
|----------|------|
| `list_menus` | 列出所有 TrMenu 菜单 |
| `read_menu` | 读取指定菜单配置 |
| `write_menu` | 写入/更新菜单配置 |
| `list_ia_items` | 列出 ItemsAdder 自定义物品 |
| `get_ia_item_texture` | 获取 IA 物品贴图数据 |
| `get_server_info` | 获取服务器信息（版本/在线人数等） |
| `reload_trmenu` | 远程触发 TrMenu 重载 |

## 构建

```bash
cd server-plugin
./gradlew shadowJar
# 输出: build/libs/OurcraftTrmenu-Server-1.0.0.jar
```

**要求**: JDK 21+, Kotlin 2.1, Gradle 9.x + Shadow

## bStats

本插件使用 bStats 收集匿名使用数据，帮助开发者了解插件使用情况。你可以在 `plugins/bStats/config.yml` 中关闭统计。

插件 ID: 31635

## 安全建议

### 生产环境部署

1. **绑定地址改为 127.0.0.1**：避免 WebSocket 端口直接暴露公网
2. **设置强 Token**：至少 32 字符随机字符串
3. **启用 localhost-only**：如果桌面编辑器在同一台机器
4. **防火墙限制端口**：仅允许信任的 IP 访问 8765 端口
5. **关闭 verbose 日志**：生产环境减少日志量

### 通过 SSH 隧道安全连接

如果桌面编辑器在远程，建议通过 SSH 隧道而非直接暴露端口：

```bash
# 在桌面端建立 SSH 隧道
ssh -L 8765:127.0.0.1:8765 user@yourserver.com

# 然后桌面编辑器连接 ws://127.0.0.1:8765
```

这样 WebSocket 流量通过 SSH 加密传输，无需暴露端口。

## 更新日志

### v1.0.5 (2026-06-26)
- 🎉 初始发布
- ✨ WebSocket 服务器（默认端口 8765）
- ✨ Token 认证
- ✨ 菜单列表/读写/重载
- ✨ 文件变更监听 + 推送
- ✨ Folia 兼容
- ✨ bStats 匿名统计

## 作者

- **云灿** (Yuncan) - 微信: Yuncan6683
- 主页: [yuncan.xyz](https://yuncan.xyz)
- 爱发电: [https://ifdian.net/item/8e3901e671fa11f19a1c52540025c377](https://ifdian.net/item/8e3901e671fa11f19a1c52540025c377)

## 许可证

本项目基于 GPL-3.0 许可证开源。

```
OurcraftTrmenu-Server — TrMenu 可视化编辑器服务端辅助插件
Copyright (C) 2026 云灿 (Yuncan)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```
