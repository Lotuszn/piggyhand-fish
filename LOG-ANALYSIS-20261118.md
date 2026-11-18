# 用户日志分析 · 2026-11-18（「今天领取好像有问题」）

**日志**：`xiaozhushou2026.11.18_7.29.10_530.log`（5.4MB / 50235 行，三天合订本 11-16/17/18 + raw 旁档段）
**设备**：OnePlus PJA110 / **Android 16(36)** / 宿主 7.29.10(530)
**模块版本**：11-18 段全程 **1.5.71(52)**——无版本混跑，昨天凌晨发的修复版用户已用上。

---

## 一、「领取有问题」定性：连接层断连潮 + 一单服务端判定，不是 1.5.71 逻辑问题

### 1. 主因：-205 断连潮贯穿全天

- 11-18 06:50 启动，06:55 起进入 `ANDROID_SYS_NETWORK_ERROR responseCode=-205 headerFields=null bytedata=null`（包未到达服务端的连接层失败）
- 首发窗 06:55-08:10 密集，之后全天间歇：首次 06:55:30 → 末次 23:13:38，共 447 条 -205 档
- 领取链全部撞过：`joy.community.activity.task.award`、`task.getolivertaskbenefit`、`coin.signin`、`play.sign.in`、`cube.task.trigger`、`play.ad.event.push`、`forest.animal.feed`、`cash.join.list`
- 每小时分布：-205 集中在 06(71)/07(55)/08(142)/10(66) 与 19(21)/22(22)/23(31)，与前四份日志的「早间 + 19~23 时」高发窗规律一致
- 同期 success 并未归零（06=97 / 08=181 / 10=168 / 16=237 / 21=422）——断连是间歇性的，主链路大部分时段存活

### 2. 用户感知的具体丢失点

| 项 | 现象 | 定性 |
|---|---|---|
| 视频任务 106538「看5秒视频立即领奖」 | 06:57:51、08:06:25 两次领奖请求 -205；07:17:33 网络通畅那次服务端回 `FAIL_BIZ_TASK_IS_NOT_COMPLICATE::任务没有完成` | 两次是断连；07:17 那次**网络是通的**（elapsed 308ms），是服务端判定任务未完成——详见下文「待办」 |
| 游戏币签到 playId=4/110 | 06:55、08:08 两轮全 TIMEOUT；06:56:45 那轮 `签到 SUCCESS` + `玩一玩签到 SUCCESS` | 撞时段，非全灭 |
| coin 游戏任务领奖 | 06:55-07:01 轮：成功 8 / 失败 19 / **+2200 游戏币** | 撞断连，部分成功 |
| cash 提现 watch | `cash.join.list` 06:55-08:00 十轮全 timeout（5 分钟一轮），08:04 重启后恢复正常 | 撞断连 |
| 鱼塘/投喂 | 06:55 首轮 -205 全灭；08:04 重启 try 2/3 → **08:06 pond mark saved、08:08 pondfeed mark saved** | 断连致首轮失败，重试自愈 |

## 二、1.5.71 四项修复全部实证生效（用户真机首证）

1. **P0-A 标记结果化自愈成功**：06:55 首轮全败 → `[pond] auto-run incomplete, no mark, will retry on next launch`（不再落当日标记）→ 08:04 用户再开闲鱼 try 2/3 → 鱼塘/投喂/会员全部补跑成功落标记。**昨天用户 C「鱼塘任务没有做」的病灶在这台机器上被修复闭环了。**
2. **P1-C 归因分流**：`[tasks] 领奖 视频任务 -> NETWORK_LOST (200B)`、报告文案「连接层失败（弱网/断连，请求未到达），下轮重试」——不再误标「网关拒绝」，报告语义正确。
3. **P1-B 阶梯退避**：`network-lost x3, balance probe paused 10min (backoff lvl 1)` → 30min(lvl 2) → 60min(lvl 3)，按设计爬梯；对比 11-17 段（1.5.7）仍是旧文案 `gateway reject x3, paused 10min` 固定 10 分钟——两版行为差异清晰可见。
4. **try 计数**：`auto-run scheduled in 40s (try 2/3)`、`give up today` 机制正常。

## 三、新发现（非 1.5.71 回归，存量病）

### A. `mini.game.task.detail.query` 缺 appkey——服务端 11-17 起收紧校验

```
req={"taskId":"1047278691","taskScene":"GAME_CHANNEL"}
resp ret=["FAIL_SYS_BIZPARAM_MISSED::缺少业务参数appkey"]
```

- 跨版本对照：11-16(1.5.5/1.5.7) 11 次、11-17(1.5.7) 16 次、11-18(1.5.71) 3 次——**三个版本都有，不是新版的回归**
- 固定同一批 taskId（1047278691/1047276220/1047273460），请求体只有 `{taskId, taskScene}` 没有 `appkey`
- 关键佐证：主领奖链（`mini.game.task.award`）的请求都带 appkey 且成功（06:56:17-39 连续 +300 币 ×8）——只有 detail.query 这个分支裸奔
- 服务端 11-17 之前不校验、之后开始报 PARAM——**闲鱼在收紧**
- 连带：`[✗] 协议不通 去闲鱼币频道逛一逛 [PARAM] 缺少业务参数appkey`（channel_click 协议路同因挂）

### B. channel_click 的 WebView 路「无容器实例」

`06:57:04 [✗] WebView 链不可用（无容器实例）`——该轮没有 H5 容器可借，协议路又缺 appkey，双路全灭。1.5.7 真机验证时容器捕获 63 次成功，这台机器的容器出现时机需要更多样本。

### C. Android 16 请求侧（P0-C）证据再加一

`[compat] constructor hook DEAD on this ROM, arming method-level fallback` 三天 11 次、每次启动必现；06:50 启动首轮（40-50s 延迟后的跑批）恰好全灭在 06:55 的 -205 上——与用户 A「启动即全灭」形态一致。**P0-C（Android 16 请求侧兼容）优先级应该上调。**

## 四、待办观察：视频任务 106538 的 `TASK_IS_NOT_COMPLICATE`

07:17:11 状态 `可领:1 可触发:1`，07:17:14 getolivertaskbenefit 成功（任务在），07:17:32 award 报「任务没有完成」。可能路径：该任务类型（看5秒视频）服务端要求先 complete/上报播放行为才允许 award，当前引擎只查详情直接领。**只有一次样本，先不下结论**；若后续用户日志再现同一 ret，立专项。

## 五、建议（待指令，不自行开工）

1. `detail.query` 分支补 appkey（从 `RhCoinTask` 的 SIGN/AWARD 同源取）——小改动，跨版本通吃
2. P0-C Android 16 请求侧排到下一批首位（本机 + 用户 A/C 三份证据）
3. 106538 类视频任务的领奖前置行为链取证（被动观察，加一条日志即可）

---
分析脚本：`_dev/analyze_user_log10~13.py`
