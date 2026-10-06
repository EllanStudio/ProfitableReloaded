# ProfitableReloaded

[![Build](https://github.com/EllanServer/ProfitableReloaded/actions/workflows/build.yml/badge.svg)](https://github.com/EllanServer/ProfitableReloaded/actions/workflows/build.yml)
[![Paper](https://img.shields.io/badge/Paper-26.3-blue)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://adoptium.net)
[![License](https://img.shields.io/badge/License-GPL--3.0-green.svg)](LICENSE)

**English** | [简体中文](README_CN.md)

![wideboy ProfitableReloaded](https://github.com/user-attachments/assets/ba556248-c80e-4241-91cd-cc5accb431d5)


## About
ProfitableReloaded is a Minecraft Economy plugin that adds **real** supply and demand to the game using an exchange!

This means that prices arent pre-set, nor just go up +1 when someone buys something,
Prices are **placed by the players** by using orders, which transact whenever two orders agree on certain price range, and thus turning into the current value of the asset.


![Sin título](https://github.com/user-attachments/assets/1b4a3f2a-2f9b-4d6a-85b5-fdbfee64bdce)

# 🔖 Plugin Features

## ⭐️ Highlights
- **Player-driven prices**
- **Instant transactions at the best price using orders**
- **Partial fills**
- **Multi-asset wallets**
- **Offline transactions**
- **Entity trading**
- **Item trading**
- **Currency trading (Forex)**
- **Advanced trading** (Limit and Stop-Limit orders)
- **Asynchronous map candle charts** with validated OHLC data, 128-column downsampling and a bounded 5-second snapshot cache
- **Fees (Taxes)**
- **Automatic database migration**
- **SQLite & MySQL support** (MySQL uses a HikariCP connection pool)
- **Crash-recoverable settlement** with a durable, exactly-once wallet-credit outbox and request/execution correlation
- **Audited market adjustments** with reusable request UUIDs, shared market locking and retry-safe candle volume
- **Monotonic per-market time** so a backend with an older world clock cannot move candle/stop processing backwards
- **Audited administrator wallet sets** with request UUID replay protection and rejection while a matching durable credit is pending
- **Shared-MySQL multi-server trading**, with Redis used only for session coordination, cache invalidation and notifications
- **Strict cross-backend FIFO within each price level**, using a database-generated monotonic `sequence_id`; activated stop orders receive a fresh sequence
- **Full Folia support**
- **Quick market quotes** with `/price <Asset>` (alias `/quote`, or `/price hand` for your held item)
- **One-click order management** with `/orders cancelall`
- **Full admin toolkit** (`/admin`) for assets, orders, accounts and exchange status

## Integration
- **Vault**: Anything compatible with Vault is compatible with ProfitableReloaded
- **PlayerPoints**
- **Redis** (optional): session coordination, instance leases, asset-cache invalidation and notifications. Redis never replays financial changes; shared MySQL is authoritative for balances, orders, executions and candles.

## Requirements
- **Paper 26.3** or newer (Folia supported)
- **Java 25**
- Optional: Vault, PlayerPoints, Redis (for multi-server)
- **SQLite** is intended for a single backend. Multi-backend deployments require a shared **MySQL 8.0+ compatible** database; the live integration suite has also been exercised against **MariaDB 11.4**.

# ⌨️ Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/assets` | Opens the asset explorer GUI | - |
| `/trade` | Opens the complete trading interface | `profitable.market.trade.gui` |
| `/buy` `/sell` | Places orders from the chat | `profitable.market.trade.*` |
| `/price <Asset>` or `/price hand` | Shows latest price, day change, range and volume | `profitable.asset.price` |
| `/orders` | Lists your active orders | `profitable.account.info.orders` |
| `/orders cancelall` | Cancels all of your orders at once | `profitable.account.manage.orders.cancelall` |
| `/account` | Manages your trading account | `profitable.account.*` |
| `/wallet` | Shows balances and provides explicit deposit/withdrawal flows | `profitable.account.info.wallet` |
| `/claimtag` | Get a tag to claim entities | `profitable.account.claim` |
| `/top` | Shows the most popular assets | `profitable.asset.tops` |
| `/admin status` | Exchange and settlement health (DB, Redis, correlated requests, executions and outbox) | `profitable.admin.info.status` |
| `/admin account <account> wallet <asset> <amount> [request-uuid]` | Idempotent, audited absolute wallet adjustment | `profitable.admin.accounts.manage.wallet` |
| `/admin outbox dead` / `/admin outbox retry <delivery-uuid>` | Inspects and safely redrives DEAD wallet credits | `profitable.admin.outbox.*` |
| `/admin ...` | Full administration of assets, orders and accounts | `profitable.admin.*` |
| `/help` | Command usage and help pages | - |

## Wallet-first settlement

Trading always uses the exchange wallet. Deposit item, entity or currency collateral with `/wallet deposit` before placing an order. The matcher never removes collateral directly from an inventory or from the world.

A match atomically records the request correlation, execution, fills, candle updates and durable wallet-credit outbox in the authoritative database. A restart-safe worker applies each credit exactly once, so either player may be offline while wallet settlement completes. Worker leases and retry deadlines use database time, and expired in-progress deliveries are recovered before new pending work. Redis messages are notifications only and never repeat these financial effects.

Use `/wallet withdraw <asset> [amount]` to materialize an item or entity after settlement. Items are inserted into the online player's inventory and overflow is dropped at the player's location. Entities are spawned at the online player's location and receive the account claim identifier. “Offline transactions” means that wallet settlement can complete while a player is offline; it does not mean that physical items or entities are spawned for an offline player.

<details>

<summary>Config file</summary>

```
# Disabled until this fork has a published Modrinth project
update-check:
  enabled: false
  modrinth-project: ""
  download-url: "https://github.com/EllanServer/ProfitableReloaded/releases"

# allows Vault currency be added as an asset, withdrawn or deposited to your account
vault-support: true

# allows PlayerPoints currency be added as an asset, withdrawn or deposited to your account
player-points-support: true

colors: # Allows customization of command outputs and graphs
  # Color for prices going up (hex)
  bullish: "#8CD740"

  # Color for prices going down (hex)
  bearish: "#FA413B"

database:
  # orders are able to transact across worlds if false
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

# Redis is a cross-backend coordination and notification bus. Shared MySQL is
# authoritative; Redis does not replicate balances, orders, executions or candles.
redis:
  # Enables cross-backend coordination and notifications
  enabled: false
  # If enabled and required, startup fails when Redis is unavailable. A later
  # disconnect blocks new trades until coordination recovers; cancellation remains safe.
  required: true
  # Unique per backend; duplicate live IDs are rejected by a Redis lease
  server-id: "server-1"
  host: localhost
  port: 6379
  password:
  channel-prefix: "profitable"
  # Automatic reconnection with exponential backoff and jitter
  reconnect-attempts: 5
  reconnect-delay-ms: 2000

exchange:
  fees: # Fixed: 23    Percentage: 23%
    # /wallet amounts are gross; the destination receives gross minus fee
    # Fees when you take money from your wallet
    withdrawal-fees: 0
    # Fees when you add money to your wallet
    deposit-fees: 0

  commodities:
    fees: # Fixed: 23    Percentage: 23%
      # Fees when you transact immediately with another order
      taker-fees: 0
      # Fees when you add an order to the order book
      maker-fees: 0

      # Fees when you use the claiming tag on an entity (fixed only)
      entity-claiming-fees: 0

    # Trades always reserve collateral from, and settle proceeds into, the exchange
    # wallet. Use /wallet deposit and /wallet withdraw for world-facing transfers.
    generation:
      # Allows auto-generation of assets for commodities in each world using whitelisting/blacklisting
      active: true

      # Enables whitelist and disables blacklist for items
      item-whitelisting: true
      commodity-item-whitelist:
        - COAL
        - IRON_INGOT
        - DIAMOND
        - QUARTZ
        # ... see the shipped config.yml for the full list

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
    fees: # Fixed: 23    Percentage: 23%
      # Fees when you transact immediately with another order
      taker-fees: 0
      # Fees when you add an order to the order book
      maker-fees: 0

main-currency: # setting for the currency used to trade on the exchange (This can be changed in game)

  # Sets Main currency to a currency with matching code
  # If no match is found, the asset will be created using values below
  currency: EMD_Villager Emerald_#00ff00
  #
  #           <code>_<name>_<hex color>
  #
  # Example: EMD_Villager Emerald_#21ff59   (use this if you want it to look good even if it's not found)
  # Example: USD_US dollars                 (use if you want a random color)
  # Example: EUR                            (use this is you are really sure currency already exists)
  #
  # creation:
  # 3-letter codes only, example: USD ---> you have: 89.32 USD !!
  # Name, color and description are optional. (color is selected at random) (name uses code)


  # When true, if no match is found a hook currency will take its place, or create if no hooks are found
  # hooks: Vault (VLT), Player Points (PTS)
  create-last-resort-only: true

  # Initial amount of Main Currency that new players will have on their wallets by default (other than 0 may devalue your currency, use only for initial supply)
  initial-balance: 0

  # currency that will be deposited or withdrawn for every hook
  vault-currency: VLT_Vault Currency_#ffbb15
  playerpoints-currency: PTS_Player Points_#ff6d92
```

</details>

# 📑 Orders
This plugin uses **Orders** to trade.
Orders are instructions to sell or buy an asset (item, entity, currency, etc...) under specified conditions

## 📗 Market Order

A **Market order** lets you transact immediately with the best available price in the order book.


![Untitled video - Made with Clipchamp (1)](https://github.com/user-attachments/assets/79305223-eb12-4910-af62-429dc131a6dd)

## 📘 Limit Order

**Limit Orders** lets you choose the price you want, but may not execute right away as it needs someone else to agree on your price, Players can place an order to trade stuff at the price they think fair, actually influencing the market.

![Untitled video - Made with Clipchamp](https://github.com/user-attachments/assets/c091b8f5-9f20-44d2-bd6f-17b3ca0171b3)

## 📕 Stop-Limit Order

A **Stop-Limit Order** becomes a **Limit Order** once the market reaches the specified trigger price

Useful if you think Prices will keep going up after a certain price

![bii](https://github.com/user-attachments/assets/79c4bc07-290e-42e7-a194-05c332c7d328)




# Market Data & Analysis

Since ProfitableReloaded is a plugin designed to simulate real trading, which includes speculation and market data tracking,
Players can monitor **prices**, **price movements**, **liquidity**, **supply**.

The map chart loader runs asynchronously. It repairs invalid OHLC bounds, safely handles empty, single-value and constant-price histories, and aggregates long histories into at most 128 OHLC columns so highs and lows are not lost. Candle snapshots are cached for at most 5 seconds (with a 128-entry bound), so a just-completed trade may take a few seconds to appear on a newly requested map.

![2025-04-20_20 30 33](https://github.com/user-attachments/assets/7a7d318c-c17d-4f68-b403-386a3527d711)


# Why?

**Put it like this**,

> Imagine you have a server where two groups of players start fighting, they start buying a lot of diamonds and netherite for gear so people start selling it a bit > more expensive every time because they have to go farther and farther to find them, so **prices start rising**.
>
> now our friend **johnny** here, notices this and buys 100 diamonds with all his balance expecting prices to go even higher,
>
> Sadly the leader of one of these groups gets banned a day later so they stop fighting, now everyone has so many and the **price is so high** people don't want to buy anymore.
> And those who do, **want it cheaper**, so every time someone sells a diamond, it transacts with a cheaper order, so **prices start to fall**.
>
> johnny has now a bunch of useless diamonds and no money,
> however, if **prices had gone up** as he expected **he'd be rich**

ProfitableReloaded makes this kind of scenarios possible, it lets everyone experience the actual depth of a real market. It's not just a store like many linear price adjustment systems that have led to **broken economies** due to farms and the rising number of raids,

**ProfitableReloaded** not only makes that add to the fun, but prices adjust **themselves**.
Because you’re not just buying and selling; you’re participating in an **actual economy** where people and events are behind prices.

I hope with that I have convinced you to download this wonderful plugin, have a great day.


# 🔨 Building from source

ProfitableReloaded builds with Maven and requires **JDK 25**:

```bash
mvn clean verify
```

The deployable shaded jar is generated at `target/profitablereloaded-<version>.jar`.

The default verification suite covers migrations, durable settlement/outbox recovery, request idempotency, FIFO sequencing, wallet conservation, audited administration and map rendering. The opt-in live database integration tests have also passed against MariaDB 11.4 for concurrent takers, monotonic market adjustments and wallet-adjustment serialization. A separate end-to-end acceptance run used Bots4Velo 3.0.2, Velocity, two Paper 26.2 backends on Java 25, shared MariaDB and Redis: one real bot command arrived on each backend, only one consumed the maker, exactly two outbox credits completed, and day/week/month candle volume increased once. Both local session registries returned to zero after the proxy shutdown. MySQL 8.0+ remains the supported production compatibility target; this statement does not claim that every MySQL minor release was exercised in the same live run.

## Upgrading an existing MySQL database

Stop every ProfitableReloaded backend and take a restorable database backup before installing 0.0.1. Start exactly one backend first and wait for all Flyway migrations through V7 to finish before starting the remaining backends. The V5 order/outbox table swaps keep the live table name present atomically, but MySQL DDL is not transactional: a process or database interruption can still leave a failed Flyway entry or diagnostic tables such as `orders_legacy_v5` and `delivery_outbox_legacy_v5`.

If migration fails, keep all backends stopped and preserve both the backup and every live/legacy table. Do not delete a legacy table or run `flyway repair` blindly. First identify which migration statement completed, compare row counts and constraints, and restore the backup if the authoritative copy is uncertain. Only repair Flyway metadata after the schema and data have been reconciled. A normal successful upgrade retains no pending ambiguity; legacy-named tables after an interrupted upgrade are a recovery signal, not disposable scratch data.

Every push to `main`, pull request and manual workflow run is built and tested automatically by **GitHub Actions** (see [`.github/workflows/build.yml`](.github/workflows/build.yml)). The shaded plugin jar and its SHA-256 checksum are retained as workflow artifacts for 30 days. Pushing a semantic version tag that exactly matches the Maven version (for example, `v0.0.1` for POM version `0.0.1`) also publishes those verified files to [GitHub Releases](https://github.com/EllanServer/ProfitableReloaded/releases).


# Final notes

This is an early version of the plugin. Future updates will bring more refined features and improvements.

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/V7V110GP3T)
