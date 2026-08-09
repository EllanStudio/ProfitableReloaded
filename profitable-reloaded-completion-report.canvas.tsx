import { Stack, H1, H2, H3, Text, Table, Stat, Grid, Tag, Divider, Callout } from 'qoder/canvas';

export default function ProfitableReloadedCompletionReport() {
  return (
    <Stack gap={24}>
      <Stack gap={8}>
        <H1>ProfitableReloaded 0.0.1 工程状态报告</H1>
        <Text tone="secondary">由 2026-08-07 的旧五阶段报告校正；这是自动化与代码状态快照，不是“全部真人验收完成”声明</Text>
        <Stack direction="row" gap={12}>
          <Tag tone="success">自动化验证通过</Tag>
          <Tag tone="info">版本 0.0.1</Tag>
          <Tag>Java 25</Tag>
          <Tag>Paper 26.2</Tag>
          <Tag>MariaDB 11.4 实测</Tag>
        </Stack>
      </Stack>

      <Divider />

      <Grid columns={4} gap={16}>
        <Stat value="V7" label="数据库结构" tone="success" />
        <Stat value="65" label="默认测试用例" tone="success" />
        <Stat value="5 秒" label="地图快照上限" />
        <Stat value="10" label="outbox 最大尝试次数" />
      </Grid>

      <Divider />

      <Stack gap={12}>
        <H2>运行时与数据库基线</H2>
        <Stack gap={8}>
          <Text>✓ 名称与版本：ProfitableReloaded 0.0.1</Text>
          <Text>✓ Java 25、Paper API 26.2、FoliaLib 0.4.4</Text>
          <Text>✓ SQLite 用于单后端；多后端要求共享的 MySQL 8.0+ 兼容数据库</Text>
          <Text>✓ MariaDB 11.4 已执行真实 JDBC 迁移与并发撮合集成测试</Text>
          <Text>✓ 发布构建入口统一为 mvn clean verify</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>共享数据库权威与 Redis 边界</H2>
        <Stack gap={8}>
          <Text>✓ 共享 MySQL 是余额、订单、成交、K 线、请求相关性与 outbox 的唯一权威来源</Text>
          <Text>✓ Redis 仅负责会话协调、后端实例租约、资产缓存失效与通知事件</Text>
          <Text>✓ Redis 通知不会重放任何订单、余额或 K 线资金效果</Text>
          <Text>✓ 已移除旧报告中不存在的 Redis 订单/余额复制开关</Text>
          <Text>✓ 重连使用指数退避与抖动；required 模式下断线会拒绝新风险，撤单仍可安全退款</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>V5–V7 持久化结算、相关性与 FIFO</H2>
        <Stack gap={8}>
          <Text>✓ exchange_requests 将客户端请求关联到成交或剩余挂单，并阻止重复扣款</Text>
          <Text>✓ trade_executions / trade_fills 保存可审计的成交与 maker fill 关联</Text>
          <Text>✓ delivery_outbox + processed_events 在同一数据库事务中实现恰好一次的钱包入账</Text>
          <Text>✓ worker 使用数据库时钟租约、过期任务优先恢复、指数重试与 DEAD 状态；进程重启后可继续处理</Text>
          <Text>✓ 同价订单按数据库生成的单调 sequence_id 严格跨后端 FIFO；触发后的 stop 单获得新序列</Text>
          <Text>✓ 成交 sweep 的 open/high/low/close/volume 与订单认领在同一事务中写入</Text>
          <Text>✓ 管理员 synthetic candle 使用 V6 审计记录与请求 UUID，共用市场锁且重试不重复 volume</Text>
          <Text>✓ V7 维护每市场单调时间水位，跨后端世界 tick 倒退不会倒写 K 线或 stop 参考时间</Text>
          <Text>✓ 管理员钱包绝对设置写入 V7 审计，可按 UUID 幂等重放，并拒绝覆盖待交割 credit</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>钱包优先结算与地图图表</H2>
        <Stack gap={8}>
          <Text>✓ 交易只从 exchange wallet 预留抵押，成交所得也先进入 wallet-credit outbox</Text>
          <Text>✓ 世界物品/实体通过显式 /wallet deposit 与 /wallet withdraw 进出，不把旧 delivery location 宣称为成交交割</Text>
          <Text>✓ 地图 K 线异步加载；旧慢请求不会覆盖玩家较新的图表请求</Text>
          <Text>✓ 异常 OHLC 会清洗，超长历史聚合为最多 128 个保留高低点的 OHLC 桶</Text>
          <Text>✓ 图表快照 TTL 为 5 秒、最多 128 项；刚成交的数据允许有不超过该边界的短暂显示延迟</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>当前验证边界</H2>
        <Stack gap={8}>
          <Text>✓ SQLite 迁移、并发撮合、崩溃恢复、幂等、费用守恒、FIFO、K 线与地图边界已有自动化覆盖</Text>
          <Text>✓ MariaDB 11.4 集成测试验证两并发 taker 争抢同一 maker 时只成交一次、只产生一次 K 线和对应 outbox</Text>
          <Text>✓ Bots4Velo 3.0.2 + Velocity + 两个 Paper 26.2 后端已实测：双玩家分居后端、争抢同一 maker、K 线唯一、会话登录与退出清理</Text>
          <Text>△ 尚不能把真人 GUI 点击链、箱子/实体世界交互和地图像素观感标为完成</Text>
          <Text>△ Bots4Velo transport 可用于命令与切服，但不能代替普通容器点击、右键实体/方块或接收地图像素</Text>
          <Text>△ MySQL 8.0+ 是生产兼容目标；本快照只明确声称 MariaDB 11.4 的这次真实引擎集成运行</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>0.0.1 关键实现清单</H2>
        <Table
          headers={['组件', '当前职责', '状态']}
          rows={[
            ['V5 migrations', 'sequence_id、exchange_requests、executions、fills、durable outbox', '✓'],
            ['V6 migration', '带请求 UUID 的可审计、幂等 synthetic candle 调整', '✓'],
            ['V7 migration', '市场单调时间水位与可审计、幂等管理员钱包调整', '✓'],
            ['TradeSettlementRepository', '单事务撮合、escrow、相关性、K 线与 stop 激活', '✓'],
            ['DeliveryOutboxWorker', '租约恢复、重试、恰好一次钱包 credit', '✓'],
            ['Exchange / Orders', '钱包优先主流程、安全撤单与退款 outbox', '✓'],
            ['RedisManager', '会话/实例协调、缓存失效和通知，不承载资金真相', '✓'],
            ['MapGraphRenderer', 'OHLC 清洗、128 列聚合、安全坐标与一次渲染', '✓'],
            ['TemporalItems', '异步请求令牌、5 秒/128 项图表快照缓存', '✓'],
            ['README / README_CN', '记录权威边界、钱包模型和未完成真人验收', '✓'],
          ]}
        />
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>验证证据</H2>
        <Callout tone="success">
          <Stack gap={8}>
            <Text weight="semibold">✓ 当前自动化证据</Text>
            <Text>发布验收命令：mvn clean verify</Text>
            <Text>默认 surefire 报告：65 个测试，0 failure，0 error</Text>
            <Text>MariaDbSettlementIT：MariaDB 11.4，1 个真实数据库并发测试，0 failure，0 error</Text>
            <Text>MariaDbMarketAdjustmentIT：MariaDB 11.4，1 个并发幂等行情调整测试，0 failure，0 error</Text>
            <Text>MariaDbWalletAdjustmentIT：MariaDB 11.4，1 个并发幂等、缺失 holding 与死锁重试测试，0 failure，0 error</Text>
            <Text>发布产物路径：target/profitablereloaded-0.0.1.jar（shaded JAR）</Text>
          </Stack>
        </Callout>

        <Stack gap={8}>
          <Text>✓ SQLite V1→V7 与全新 V1→V7 迁移均有约束/数据保留测试</Text>
          <Text>✓ 两 taker 并发、request replay/collision、outbox 重启恢复、DEAD 重试与 maker dust 退款守恒有测试</Text>
          <Text>✓ 地图空/单值/恒定/极值/超 128 K 线和缓存淘汰有测试</Text>
          <Text>说明：Velocity 双后端会话与命令撮合已完成实测；自动化仍不能替代真人 GUI、地图观感或世界实体交互验收。</Text>
        </Stack>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>架构边界变化</H2>
        <Grid columns={2} gap={16}>
          <Stack gap={8}>
            <H3>旧报告中的模型</H3>
            <Text>• 同价优先级依赖应用层时间戳与稳定排序，不能提供严格分布式 FIFO</Text>
            <Text>• Redis 被描述为订单/余额同步层</Text>
            <Text>• 成交后尝试直接交付世界资产</Text>
            <Text>• 不存在的 Redis 订单/余额复制开关被宣称可用</Text>
            <Text>• 只以跳过测试的打包作为证据</Text>
          </Stack>
          <Stack gap={8}>
            <H3>0.0.1 当前模型</H3>
            <Text>• DB sequence_id 实现同价严格 FIFO</Text>
            <Text>• 共享 MySQL 承载全部经济真相</Text>
            <Text>• 事务 outbox 先安全结算到钱包</Text>
            <Text>• Redis 仅协调、失效和发送通知</Text>
            <Text>• 自动化与真实引擎测试分开记录</Text>
          </Stack>
        </Grid>
      </Stack>

      <Divider />

      <Stack gap={12}>
        <H2>关键结果与仍需真人验收的部分</H2>
        <Grid columns={3} gap={16}>
          <Stat value="sequence_id" label="同价 FIFO 键" />
          <Stat value="exactly-once" label="钱包 credit 语义" />
          <Stat value="128" label="地图 OHLC 最大列数" />
        </Grid>
        <Stack gap={8}>
          <Text>• 已自动验证：持久化恢复、重复请求、并发争抢、K 线唯一性、严格序列和地图边界</Text>
          <Text>• 已真实数据库验证：MariaDB 11.4 上两并发连接争抢同一 maker</Text>
          <Text>• 已实机：Velocity 双后端、两个真实 Bot、共享 MariaDB/Redis、登录/退出会话与同 maker 争抢</Text>
          <Text>• 仍需真人：Trade GUI 全点击链、物品/实体提现交互与地图观感</Text>
          <Text>• 本报告不再把外部 secret 阻塞、无法执行的 bot 点击或未做的世界验收写成完成</Text>
        </Stack>
      </Stack>

      <Divider />

      <Text tone="secondary" size="small">
        快照校正时间：2026-08-10 | ProfitableReloaded v0.0.1 | 自动化状态已记录，真人全链路验收仍按上方边界保留
      </Text>
    </Stack>
  );
}
