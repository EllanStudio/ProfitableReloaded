# ProfitableReloaded

[![Build](https://github.com/EllanServer/ProfitableReloaded/actions/workflows/build.yml/badge.svg)](https://github.com/EllanServer/ProfitableReloaded/actions/workflows/build.yml)
[![Paper](https://img.shields.io/badge/Paper-26.3-blue)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-GPL--3.0-green.svg)](LICENSE)

[English](README.md) | **简体中文**

![wideboy ProfitableReloaded](https://github.com/user-attachments/assets/ba556248-c80e-4241-91cd-cc5accb431d5)


## 简介
ProfitableReloaded 是一款 Minecraft 经济插件，通过一个**交易所**为游戏带来**真实**的供需机制！

价格不是预设的，也不是有人买东西就简单 +1，
而是由**玩家通过挂单自主定价**——当两笔订单在某个价格区间达成一致时即刻成交，成交价便成为该资产的最新市值。


![Sin título](https://github.com/user-attachments/assets/1b4a3f2a-2f9b-4d6a-85b5-fdbfee64bdce)

# 🔖 插件功能

## ⭐️ 亮点
- **玩家驱动的价格**
- **市价单即时成交，自动撮合最优价格**
- **部分成交**
- **多资产钱包**
- **离线交易**
- **实体交易**
- **物品交易**
- **货币交易（外汇）**
- **高级交易**（限价单与止损限价单）
- **异步地图 K 线图**：校验异常 OHLC、按 128 像素列聚合长历史，并使用有界的 5 秒快照缓存
- **手续费（税收）系统**
- **数据库自动迁移**
- **SQLite 与 MySQL 双支持**（MySQL 使用 HikariCP 连接池）
- **可崩溃恢复的结算**：持久化、恰好一次的钱包入账 outbox，以及请求/成交相关性记录
- **可审计的行情调整**：可复用请求 UUID、共享市场锁，重试不会重复增加 K 线成交量
- **每市场单调时间**：某个后端的世界时钟较旧时，也不会让 K 线或止损处理倒退
- **可审计的管理员钱包设置**：请求 UUID 可安全重放；同账户/资产仍有待交割入账时会拒绝绝对改值
- **基于共享 MySQL 的多后端交易**；Redis 仅负责会话协调、缓存失效与通知
- **同价位严格跨后端 FIFO**：使用数据库生成的单调 `sequence_id`；止损单触发后以新序列重新入簿
- **完整支持 Folia**
- **快速行情查询**：`/price <资产>`（别名 `/quote`，也可用 `/price hand` 查询手持物品）
- **一键撤单**：`/orders cancelall`
- **完整管理员工具**（`/admin`）：管理资产、订单、账户与交易所状态

## 集成
- **Vault**：任何兼容 Vault 的经济插件都能与 ProfitableReloaded 配合使用
- **PlayerPoints**
- **Redis**（可选）：协调会话和实例租约、使资产缓存失效并发送通知。Redis 不会重放任何资金变更；余额、订单、成交与 K 线均以共享 MySQL 为准。

## 运行要求
- **Paper 26.3** 或更高版本（支持 Folia）
- **Java 25**
- 可选依赖：Vault、PlayerPoints、Redis（多服务器场景）
- **SQLite** 仅用于单后端。多后端部署必须使用共享的 **MySQL 8.0+ 兼容**数据库；当前真实数据库集成测试也已在 **MariaDB 11.4** 上执行。

# ⌨️ 命令一览

| 命令 | 说明 | 权限 |
|---------|-------------|------------|
| `/assets` | 打开资产浏览 GUI | - |
| `/trade` | 打开完整交易界面 | `profitable.market.trade.gui` |
| `/buy` `/sell` | 通过聊天栏挂单 | `profitable.market.trade.*` |
| `/price <资产>` 或 `/price hand` | 查看最新价格、日涨跌、日内区间与成交量 | `profitable.asset.price` |
| `/orders` | 查看自己的活跃订单 | `profitable.account.info.orders` |
| `/orders cancelall` | 一键取消自己的全部订单 | `profitable.account.manage.orders.cancelall` |
| `/account` | 管理交易账户 | `profitable.account.*` |
| `/wallet` | 查看余额并执行显式存入/取出 | `profitable.account.info.wallet` |
| `/claimtag` | 领取实体认领标签 | `profitable.account.claim` |
| `/top` | 查看最热门的资产 | `profitable.asset.tops` |
| `/admin status` | 交易所与结算健康状态（数据库、Redis、相关请求、成交和 outbox） | `profitable.admin.info.status` |
| `/admin account <账户> wallet <资产> <数量> [request-uuid]` | 幂等、可审计的钱包绝对值调整 | `profitable.admin.accounts.manage.wallet` |
| `/admin outbox dead` / `/admin outbox retry <delivery-uuid>` | 查看并安全重试 DEAD 钱包入账 | `profitable.admin.outbox.*` |
| `/admin ...` | 资产、订单、账户的完整管理功能 | `profitable.admin.*` |
| `/help` | 命令用法与帮助页 | - |

## 钱包优先结算模型

所有订单都使用交易所钱包。挂单前，请先通过 `/wallet deposit` 把物品、实体或货币存入钱包作为抵押；撮合器不会直接从玩家背包或世界中移除抵押物。

撮合会在同一数据库事务中写入请求相关性、成交、fill、K 线更新和持久化钱包入账 outbox。可恢复 worker 会在重启后继续处理，并保证每条钱包入账恰好生效一次，因此买卖双方即使离线也能完成钱包结算。worker 的租约与重试截止时间使用数据库时钟，过期的处理中任务会优先于新任务恢复。Redis 消息仅用于通知，绝不会重放这些资金变更。

成交后如需生成实际物品或实体，请使用 `/wallet withdraw <资产> [数量]`。物品会进入在线玩家背包；空间不足时溢出部分会掉落在玩家位置。实体会生成在在线玩家位置并写入账户 claim 标识。“离线交易”表示玩家离线时仍可完成钱包结算，不表示会为离线玩家自动生成世界物品或实体。

<details>

<summary>配置文件</summary>

```
# 此分支发布到 Modrinth 前默认关闭更新检查
update-check:
  enabled: false
  modrinth-project: ""
  download-url: "https://github.com/EllanServer/ProfitableReloaded/releases"

# 允许 Vault 货币作为资产加入交易所，并可存取到你的账户
vault-support: true

# 允许 PlayerPoints 货币作为资产加入交易所，并可存取到你的账户
player-points-support: true

colors: # 可自定义命令输出与图表的颜色
  # 上涨颜色（十六进制）
  bullish: "#8CD740"

  # 下跌颜色（十六进制）
  bearish: "#FA413B"

database:
  # 设为 false 时订单可以跨世界成交
  data-per-world: false

  # (0)-SQLite (1)-MySQL
  database-type: 0

  pool:
    maximum-size: 10
    minimum-idle: 2
    connection-timeout-ms: 10000

  mysql:
    host:
    port:
    database:
    username:
    password:
    options: "?useSSL=true&requireSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true"

# Redis 是跨后端协调和通知总线。共享 MySQL 才是权威数据源；
# Redis 不复制余额、订单、成交或 K 线。
redis:
  # 启用跨后端协调与通知
  enabled: false
  # 启用且设为 required 时，Redis 不可用会导致启动失败；运行中断线则
  # 暂停接受新交易，直到协调恢复，现有订单仍可安全取消。
  required: true
  # 每个后端必须唯一；存活实例重名会被 Redis 租约拒绝
  server-id: "server-1"
  host: localhost
  port: 6379
  password:
  channel-prefix: "profitable"
  # 断线自动重连（指数退避并加入抖动）
  reconnect-attempts: 5
  reconnect-delay-ms: 2000

exchange:
  fees: # 固定值: 23    百分比: 23%
    # /wallet 命令金额为毛额；目标端实际收到毛额减手续费
    # 从钱包取款时的手续费
    withdrawal-fees: 0
    # 向钱包存款时的手续费
    deposit-fees: 0

  commodities:
    fees: # 固定值: 23    百分比: 23%
      # 立即与已有订单成交时的手续费（吃单）
      taker-fees: 0
      # 挂单进入订单簿时的手续费（挂单）
      maker-fees: 0

      # 使用认领标签认领实体时的手续费（仅固定值）
      entity-claiming-fees: 0

    # 交易始终从交易所钱包预留抵押，并把成交所得结算到钱包。
    # 与世界的物品/实体交换请使用 /wallet deposit 和 /wallet withdraw。
    generation:
      # 允许通过白名单/黑名单在每个世界自动生成商品资产
      active: true

      # 启用物品白名单（同时停用黑名单）
      item-whitelisting: true
      commodity-item-whitelist:
        - COAL
        - IRON_INGOT
        - DIAMOND
        - QUARTZ
        # ... 完整列表见插件自带的 config.yml

      commodity-item-blacklist:
        - BARRIER
        - COMMAND_BLOCK
        - STRUCTURE_BLOCK
        - STRUCTURE_VOID

      entity-whitelisting: true
      commodity-entity-whitelist:
        - PIG
        - SHEEP
        - COW
        - CHICKEN
        - MOOSHROOM
        - VILLAGER

      commodity-entity-blacklist:
        - ENDER_DRAGON
        - WARDEN

  forex:
    fees: # 固定值: 23    百分比: 23%
      # 立即与已有订单成交时的手续费（吃单）
      taker-fees: 0
      # 挂单进入订单簿时的手续费（挂单）
      maker-fees: 0

main-currency: # 交易所主货币的设置（可在游戏内更改）

  # 将主货币设置为代码匹配的货币
  # 若未找到匹配，则使用下方的值自动创建
  currency: EMD_Villager Emerald_#00ff00
  #
  #           <代码>_<名称>_<十六进制颜色>
  #
  # 示例: EMD_Villager Emerald_#21ff59   （即使未找到也能显示得好看）
  # 示例: USD_US dollars                 （想要随机颜色时用这个）
  # 示例: EUR                            （非常确定该货币已存在时才用）
  #
  # 创建规则:
  # 仅限 3 个字母的代码，例如 USD ---> you have: 89.32 USD !!
  # 名称、颜色和描述均为可选。（颜色默认随机）（不填名称则使用代码）


  # 设为 true 时，若未找到匹配的货币，则优先使用经济插件挂钩的货币，都没有时才创建
  # 挂钩: Vault (VLT), Player Points (PTS)
  create-last-resort-only: true

  # 新玩家钱包中默认的主货币初始数量（非 0 可能让你的货币贬值，仅用于初始供应量）
  initial-balance: 0

  # 各经济插件挂钩对应的存取货币
  vault-currency: VLT_Vault Currency_#ffbb15
  playerpoints-currency: PTS_Player Points_#ff6d92
```

</details>

# 📑 订单
本插件使用**订单**进行交易。
订单是在指定条件下卖出或买入某种资产（物品、实体、货币等）的指令。

## 📗 市价单（Market Order）

**市价单**让你立即以订单簿中最优的可用价格成交。


![Untitled video - Made with Clipchamp (1)](https://github.com/user-attachments/assets/79305223-eb12-4910-af62-429dc131a6dd)

## 📘 限价单（Limit Order）

**限价单**让你自由选择期望价格，但不一定立即成交——需要有人认可你的价格。玩家可以按自己认为合理的价格挂单，真正影响市场。

![Untitled video - Made with Clipchamp](https://github.com/user-attachments/assets/c091b8f5-9f20-44d2-bd6f-17b3ca0171b3)

## 📕 止损限价单（Stop-Limit Order）

**止损限价单**会在市场价格触及你设定的触发价时，转变为一笔**限价单**。

当你判断价格突破某个点位后还会继续上涨/下跌时，它非常有用。

![bii](https://github.com/user-attachments/assets/79c4bc07-290e-42e7-a194-05c332c7d328)




# 行情数据与分析

ProfitableReloaded 旨在模拟真实的交易场景，包括投机行为与市场数据追踪，
玩家可以随时关注**价格**、**价格走势**、**流动性**与**供应量**。

地图图表会异步加载，修复不合法的 OHLC 边界，并安全处理空数据、单值、恒定价格与极端数值。历史超过地图宽度时会聚合为最多 128 个 OHLC 列，不会简单丢失区间高低点。K 线快照最多缓存 5 秒且总量限制为 128 项，因此刚完成的成交可能需要几秒才会出现在新请求的地图上。

![2025-04-20_20 30 33](https://github.com/user-attachments/assets/7a7d318c-c17d-4f68-b403-386a3527d711)


# 为什么需要它？

**打个比方**，

> 想象你的服务器里有两拨玩家打了起来，他们大量收购钻石和下界合金来打造装备，
> 随着资源越挖越远，卖家开始一点点提价，于是**价格开始上涨**。
>
> 这时我们的朋友 **johnny** 发现了商机，用全部身家买入 100 颗钻石，期待价格继续走高，
>
> 可惜其中一拨人的老大第二天就被封禁了，战争结束，大家手里囤了一堆钻石，
> **价格高到没人想买**。想买的人也**只想捡便宜**，于是每有一笔钻石卖出，都会与更便宜的订单成交，**价格开始下跌**。
>
> johnny 现在只剩一堆没用的钻石和空空的钱袋，
> 不过，如果**价格真如他所料上涨**，**他就发财了**。

ProfitableReloaded 让这类场景成为可能，让每个人都能体验真实市场的深度。它不是一个简单的商店——
许多线性调价系统因为刷物农场和日益增多的掠夺，早已导致了**崩坏的经济**。

**ProfitableReloaded** 不仅让这一切变得有趣，价格还会**自我调节**。
因为你不再只是买和卖，而是在参与一个**真实的经济体**——每一个价格背后都是玩家与事件。

希望这段话成功说服你下载这款出色的插件，祝你愉快。


# 🔨 从源码构建

ProfitableReloaded 使用 Maven 构建，需要 **JDK 25**：

```bash
mvn clean verify
```

可部署的 shaded jar 会生成在 `target/profitablereloaded-<版本号>.jar`。

默认验证套件覆盖数据库迁移、持久化结算/outbox 恢复、请求幂等、FIFO 序列、钱包守恒、管理员审计与地图渲染。可选的真实数据库集成测试也已在 MariaDB 11.4 上完成并发吃单、单调行情调整和钱包调整串行化。另一次端到端验收使用 Bots4Velo 3.0.2、Velocity、Java 25 上的两个 Paper 26.2 后端、共享 MariaDB 与 Redis：两个真实 Bot 的命令分别到达不同后端，只有一个消费 maker，恰好两条 outbox 入账完成，day/week/month 三档 K 线成交量都只增加一次；关闭代理后，两端本地会话均回到零。MySQL 8.0+ 仍是生产兼容目标；这里不声称同一次运行已经覆盖每一个 MySQL 小版本。

## 升级已有 MySQL 数据库

安装 0.0.1 前，请停止所有 ProfitableReloaded 后端，并制作一份已经确认可恢复的数据库备份。先只启动一个后端，等待直到 V7 的全部 Flyway 迁移完成，再启动其余后端。V5 对订单与 outbox 的表交换已经保证 live 表名原子切换，但 MySQL DDL 本身不具备整段事务性：进程或数据库在迁移中途停止时，仍可能留下失败的 Flyway 记录，或 `orders_legacy_v5`、`delivery_outbox_legacy_v5` 等诊断表。

如果迁移失败，应继续保持所有后端停止，并同时保留备份、live 表与 legacy 表。不要直接删除 legacy 表，也不要盲目运行 `flyway repair`。应先确认迁移执行到了哪条语句，对比记录数与约束；无法确认哪份数据权威时，应恢复升级前备份。只有在 schema 和数据已经核对、修复后，才能修复 Flyway 元数据。正常升级完成后不存在待判定的数据副本；中断后出现的 legacy 表是恢复线索，不是可随意删除的临时文件。

每次推送到 `main`、Pull Request 以及手动触发 workflow，都会由 **GitHub Actions** 自动构建并测试（见 [`.github/workflows/build.yml`](.github/workflows/build.yml)）。shaded 插件 JAR 与其 SHA-256 校验文件会作为 workflow artifact 保留 30 天。推送与 Maven 版本严格一致的语义化版本标签（例如 POM 为 `0.0.1` 时推送 `v0.0.1`），还会把这两个已验证文件自动发布到 [GitHub Releases](https://github.com/EllanServer/ProfitableReloaded/releases)。


# 最后的话

这是插件的早期版本，未来的更新会带来更完善的功能与改进。

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/V7V110GP3T)
