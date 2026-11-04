# CraftDrawers Test 版压测说明

Test 版是独立产物。它保留正式版功能，并额外启用 OP 专用的 `/drawers stress` 命令；正式 JAR 不包含 `stress-test.yml`，因此不注册这些命令。

## 安全准备

1. 建立一个一次性测试服务器和专用世界，默认世界名为 `craftdrawers-test`。
2. 备份该测试服世界与 CraftDrawers 数据目录。
3. 将正式版 JAR 移出 `plugins/`，再放入 `build/libs/CraftDrawers-Test-<版本>.jar`。同一服务器不要同时加载两个 CraftDrawers JAR。
4. 安装匹配的 CraftEngine 26.9.2 与 CraftDrawers 内容包；客户端需要加载 CraftEngine 生成的资源包。
5. 首次启动后检查 `plugins/CraftDrawers/stress-test.yml`，确认 `world-name` 指向一次性测试世界。

测试命令要求 OP，且放置操作只允许在配置指定的世界内执行。清理只针对 Test 版自己登记的坐标；若坐标上的抽屉类型和登记值不一致，会跳过该项。

## 命令

先在指定测试世界里执行 setup。抽屉会以每 tick 限定批量放置在玩家位置附近的网格中；目标方块必须为空，网格范围受 `max-area-radius` 限制。

```text
/drawers stress status
/drawers stress setup <count> <drawer_type> <empty|filled>
/drawers stress run <operations_per_tick> <seconds>
/drawers stress clear
```

示例：生成 64 个四仓橡木全深抽屉，并填满每个仓位：

```text
/drawers stress setup 64 drawer_oak_full_4 filled
```

`filled` 会为每个仓位存入 `seed-material` 指定物品的测试库存，以创建图标与数量展示实体；`empty` 只测抽屉方块或家具和空仓负载。生成完成后，运行定时的 SQLite 写入与显示刷新循环：

```text
/drawers stress run 4 300
```

参数表示每 tick 执行 4 次合成仓位更新，持续 300 秒。结束时命令会报告总操作数、实际操作频率、平均/最大单次耗时和最大批次耗时。

此合成循环直接进入 SQLite 仓位提交、库存缓存、展示实体刷新和已打开 GUI 的刷新路径；它**不模拟客户端交互包、网络延迟或背包空间计算**。需要测真实玩家点击时，另用协议客户端或机器人执行真实右键存入和左键取出，并与本合成压测分别记录。

测试完成后执行：

```text
/drawers stress clear
```

清理采用批处理；它会移除仍匹配登记类型的测试抽屉、展示实体和 SQLite 行。被玩家替换成其他 CraftEngine 抽屉的坐标会保留并报告为 skipped。若测试世界出现异常，最稳妥的清理方式是恢复测试前的世界与数据库备份。

## 性能采样

先记录空载和仅有测试夹具时的基线；运行写入循环时同时采样 Paper / Spark：

```text
/spark tps
/spark profiler start --timeout 300
```

可逐档提高 `count` 和 `operations_per_tick`，每档使用相同世界、数据库、Java、Paper、CraftEngine、玩家数和运行时长。记录 MSPT/TPS、Spark 报告、抽屉数、已填充仓位数、操作频率、CPU、内存、GC 和磁盘负载。推荐先测 16、32、64、128 个抽屉，确认服务器仍可操作后再提高。

常规压测建议先降低 `max-drawers`、`placement-batch-per-tick` 和 `max-operations-per-tick`，逐级增加负载。测试循环为真实同步 SQLite 提交，极高操作速率可能明显拖慢 tick，这是测试目标之一；不要直接在正式服执行。
