# ADR-LIFECYCLE-01：受控停产与未决交割门禁

日期：2026-10-21。状态：任务 E 的安全实施合同；**不批准尚未决定的出售和工资清算政策**。

依据：[DDD 草案](../../design/VE-Production-Labor-DDD-重构设计草案-2026-10-20.md)已确认规则、任务 A～D 报告及任务 E 的明确授权。取代旧文档中以删除 Registry/方块代表经济拆除、以 locationId 代表资产产权的任何推断。

## E0 决策矩阵

| 问题 | 已确认或可严格推导 | 尚未决定 / 源码事实 | 本轮门禁 |
|---|---|---|---|
| 出售的 Pending PM | 旧 Batch 快照不可变，卖方 Pending Staffing 最终清除。 | 未决定 PM 继承、取消或买方复核。 | 保持有效及 Pending PM 原样；存在 Pending 时记录专门原因。 |
| 出售/拆除的工资余数 | 旧公司承担旧时段；余数不得消失或转嫁买方。 | 最终不足最小单位如何汇总/到期/付款未决定。 | 保留活段、余数及计薪；不关闭工资段、不宣告交割，余数为 0 也不假定已有最终清算政策。 |
| 库存随售及产权 | Reservation 与完整 Batch 结算属于库存；中止全退零产。 | 是否附带商品、返料产权及拆除库存处置未决定。 | 只执行原完整结算；不转移、不删除、不兑换资产。 |
| 出售的资金对价 | 真实现金只有 Finance 权威，不能造钱。 | 是否有价格、付款主体、币种、支付与交割条件未决定。 | SALE_CONSIDERATION_UNDECIDED；不假定零价，不生成虚假转账。 |
| 地点的真实资产所有者 | GoodsInventory 只保存地点、数量、容量、Reservation、回执与 CAS。 | inventory_locations 没有 owner 或产权凭据；地点 UUID 相同不是产权证明。 | 记录 INVENTORY_OWNERSHIP_UNDEFINED；不把 Building.owner 当作库存 owner。 |
| 最小操作与恢复存储 | 本次明确授权稳定 OperationId、独立持久操作、同建筑排他和可靠恢复。 | 这是实现选择，不是新增经济政策。 | 新表 building_lifecycle_operations；复用 WriteSession/IMMEDIATE，Schema/Codec v9。 |

已请求用户补充这些规则；未获决定时按上表继续完成安全基础。历史工资组内部分付款优先级仍采用任务 D 的 BLOCKED 策略，没有在此另立优先级。

## 当前状态机

`无操作 → QUIESCING → BLOCKED`。

- OperationId 和完整命令（建筑、原公司、买方或无买方、类型、预期身份 revision）不可变。同 ID 不同命令拒绝；每栋建筑唯一操作。
- 开始物理事务同时保存意图、automatic=false 和当前 Batch 的不可逆终止决定。已有 SETTLING_COMPLETED/COMPLETED 只能按原决定完成，不能覆盖成 ABORTED。
- 先恢复 StartIntent，再按原冻结完整命令执行库存结算。失败保留 QUIESCING；未知提交隔离连接，新可信连接先核验。
- 确认结算后，在同一物理事务把已结束 Batch 移入历史、用生命周期意图接替 RECONFIGURING 屏障，并保存 BLOCKED。不应用 Pending，不改变人数、所有权或工资归属。
- BLOCKED 是尚未完成的操作。它阻止开工、自动启用、PM 和 Staffing 修改，但不冻结经济时间或工资义务。普通商品处理、历史债务付款继续遵守各自原合同。
- 没有取消中止决定、删除操作、最终交割、正式拆除或绕过政策的 approve/boolean 入口。未来批准最终规则后必须补齐完成状态及原子交割合同；不得把当前 BLOCKED 改称成功。

## 墓碑与权威

Building 是身份唯一权威；Staffing 是人数唯一权威；地区 occupied 仍从持久人数求和。Payroll 段保留历史债务公司与地区；Finance 是真实现金权威；HouseholdBudget 只是用途额度。

v9 预检区分现存建筑和历史墓碑：现存建筑需要匹配活跃 Payroll；墓碑没有活跃 Payroll、员工、Pending、未结算 Batch、StartIntent、商品余额或 Reservation，保留历史根、工资段与债务。独立墓碑格式夹具不表示正式拆除已实现。本轮没有不可逆经济交割服务，也不靠移除锚点或 Registry 元素制造墓碑。

## 数据安全与验收边界

旧 v1～v8 严格拒绝，不迁移、不清空；主文件和 WAL 拒绝前后不变。沿用原事务、库存、账本、工资付款与 Tick 检查点保护，不降低 SQLite 耐久性。

JUnit/SQLite、隔离 NeoForge 与性能验证的实际结果见[任务 E 验收报告](../../design/VE-Task-E-M5.1-M5.2-M5.3-建筑生命周期与整体验收.md)。最终交割及跨所有权工资验收在政策未定时保持未完成，不能声称整个重构已验收或可无条件合并 main。
