# 用户日志分析 · 2026-11-18 第二份（Android 17）

**日志**：`xiaozhushou2026.11.18_7.29.10_530 (1).log`（1.7MB / 15734 行，单日分段 08:32-09:26）
**设备**：Xiaomi 2509FPN0BC / **Android 17(37)——项目首次记录 API 37** / 宿主 7.29.10(530)
**模块**：1.5.71(52)，无混跑。

---

## 一、总体：这台机器今天基本健康，五引擎全部完成

| 引擎 | 结果 |
|---|---|
| 会员任务 oliver | 08:33 `done mark valid` ✓ |
| 鱼塘 pond | 08:34 `mark saved` ✓ |
| 投喂 pondfeed | 08:36 `mark saved` ✓ |
| 游戏任务 game | try 1 复核剩待做 → try 2 → 08:41 `done mark valid` ✓（92 游戏/62 任务推进到可做 20→清完） |
| 游戏币 coin | try 3 → 08:51 完成：余额 36100=3.61 元、playId=4 连签 3 天 awarded=true、playId=110 连签 1 天 ✓ |

08:34:28-08:36:33 有一个约 **2 分钟的 -205/-405 断连窗**（83 条 NETWORK_LOST），窗口过后 08:39 起一切正常。09 时段零星 -205（36 条），success 268 次。

## 二、断连窗内的实际损失（很小）

1. **oliver 触发链 12 连灭**（08:35:52-08:36:09）：`触发任务 去淘宝签到领红包 / 去神奇鱼塘领能量 / ...` 全部 `FAIL_NETWORK_LOST`。但这批 uploadolivertasktrigger 类任务本来就是云控语义——08:51/08:52/09:08 网络恢复后重试全部回 `FAIL_BIZ_COMMON_PARAM_ILLEGAL（云控判定需真实行为，协议跳过）`，即**协议本来也拿不到**，断连只是提前挡掉了注定跳过的请求，实际零损失。
2. **鱼塘投喂第 2~8 发 -205**：okCnt>0 后续失败，`mark saved` 落了（余额已消耗一部分，符合设计语义）。
3. 探针 `backoff lvl 1`（09:16:36 paused 10min）正常工作。

## 三、跨设备再证的两个存量病

### A. `mini.game.task.detail.query` 缺 appkey（第三台设备复现）

`08:36:42 [coin] detail 1047278691 [PARAM] FAIL_SYS_BIZPARAM_MISSED::缺少业务参数appkey`——与 PJA110（Android 16）那份日志同款同 taskId（1047278691/1047276220/1047273460）。**三台设备 × 三个模块版本稳定复现**，确认是代码里 detail 分支漏带 appkey，非环境问题。

### B. 背包骰子 `FAIL_BIZ_LOCATION_NOT_MATCH` 全灭（11-16 用户日志同款）

```
08:33:27 [背包] 好运气骰  使用 0/2 · 前进 0 步 · 收益 0 · 中断:FAIL_BIZ_LOCATION_NOT_MATCH
08:33:29 [背包] 公面骰   使用 0/1 · 中断:FAIL_BIZ_LOCATION_NOT_MATCH
08:33:32 [背包] 双倍骰子 使用 0/3 · 中断:FAIL_BIZ_LOCATION_NOT_MATCH
08:39:19 [背包] 好运气骰 再试再灭
```

`map.roll` 请求 `locationBefore=10303/10320, mapVersion=11`——服务端判定本地位置与地图状态不符。业务级病，与网络无关，**至今未修**。11-18 第一份日志（PJA110）该用户没有骰子存量所以未现，这台把背包骰子全灭复现了。

## 四、Android 17(37) 首证：DEAD 必现但请求链存活

- `constructor hook DEAD on this ROM` 五次启动五次必现（08:32/08:38/08:45/09:05/09:21），与 Android 16 一致
- **但请求侧整体存活**：08:33 首轮跑批即成功（对比 Android 16 用户 A/C 的「启动首轮全灭」）——说明 constructor DEAD ≠ 请求侧死，Android 17 上方法级 fallback + 请求链都通
- 这为 P0-C 缩小了范围：问题不在「新 ROM 上 hook 全死」，而在 **Android 16 特定机型（PJA110/23113RKC6C/23116PN5BC）的请求时序**

## 五、小观察：coin 引擎无 done 标记，完成后仍调度

08:51 coin 已完成（签到+余额），09:04 启动时仍 `scheduled try 4 → give up`、09:21 `try 5 → give up`——靠 try 计数止血，不打请求，无实际损害，但日志会出现误导性的「give up」。可给 coin 补 game/oliver 式 done 标记（低优先级）。

## 六、结论与待办

**今天这份日志的用户没有实质损失**：五引擎全完成，断连窗只吞掉了 12 个本来就拿不到的云控触发 + 半轮投喂。骰子全灭是唯一真实丢失（好运气骰 2 + 公面骰 1 + 双倍骰 3）。

待办汇总（跨两份 11-18 日志）：
1. `detail.query` 补 appkey（三设备三版本实锤，小改动）——等指令
2. 背包骰子 `FAIL_BIZ_LOCATION_NOT_MATCH` 专项（11-16 与 11-18 两份实锤，位置校验绕不过，需查 mapVersion/location 语义）——等指令
3. P0-C 收窄到 Android 16 特定机型请求时序（Android 17 反证请求链可活）——等指令
4. coin 引擎补 done 标记（体验优化）——等指令

---
分析脚本：`_dev/analyze_user_log14~15.py`
