# 日志分析 · 2026-11-19（闲鱼 7.29.20 / 小猪手 1.5.72）

## 环境

| 项 | 值 |
|---|---|
| 设备 | Xiaomi M2102K1AC，Android 14 (SDK 34) |
| 闲鱼 | **7.29.20 (versionCode 531)** ← 昨天还是 7.29.10/530 |
| 小猪手 | 1.5.72 (53) |
| 日志 | `zhushou_run_2026.11.19.log`，8706 行，16:37:58 ~ 17:09:37 |
| hook 状态 | constructor hook DEAD → method fallback 5 个方法（MtopResponse）已接管，配对正常 |

## 结论先行

**不是 7.29.20 的兼容问题。** 今天日志里出现的每一类错误，在昨天的 7.29.10 日志里都出现过，而且次数更多。7.29.20 没有引入任何新错误类型，接口结构、hook 落点、任务链全部照旧工作。

三份日志同口径对比（原始出现次数）：

| 错误 | 11-18 A（7.29.10，8:53 导出） | 11-18 B（7.29.10，9:26 导出） | 11-19（7.29.20） |
|---|---|---|---|
| `FAIL_BIZ_LOCATION_NOT_MATCH` | 135 | 36 | **13** |
| `FAIL_BIZ_SERVER_ERROR` | 60 | 11 | **11** |
| `FAIL_BIZ_COMMON_PARAM_ILLEGAL` | 109 | 29 | **24** |
| `FAIL_SYS_BIZPARAM_MISSED` | 38 | 9 | **6** |
| `FAIL_BIZ_request_auth_failed` | 22 | 7 | **6** |
| `FAIL_BIZ_RESULT_NULL` | 15 | 16 | **9** |
| `FAIL_BIZ_NOT_TARGET_USER` | 17 | 16 | **9** |

按任务量折算（今天只跑了一轮，昨天整天多轮），7.29.20 上的表现不差于 7.29.10。

## 今天的实际战果

| 模块 | 结果 |
|---|---|
| 游戏任务 | 上报 62 / 领奖 62，0 失败，**+6200 闲鱼币** |
| 游戏币·中央任务 | 上报 6 / 领奖 7，**+700 币** |
| 游戏币·渠道任务 | 27 项 ACCEPTED，领奖 25 项，0 失败，**+6500 游戏币** |
| 会员任务 | 4 个任务全部上报+领取，**+900 经验** |
| 鱼塘 | 触发 25 成功 / 0 失败，报告已出，标记已落 |
| 现金夺宝 | 领取 11-18 期成功（金额 0） |
| 签到 | playId=4 +50 / playId=110 +150，双档到账 |
| 广告任务 | 106538、106181 由 times=1/4 推进到 2/4，LVL 旧账已清 |
| 金币瓜分 | betNum 5/5 满额 |
| 地图掷骰 | 成功 4 次（5 步 + 0 币 / 20 步 +40 / 20 步 +56 / 20 步 +38），共 **+134 币、65 步** |

17:09 日志末尾引擎仍在跑（cube.task.trigger → oliver.show → forest.energy.list），属于正常波次。

## 真正的问题（模块自身缺陷，与闲鱼版本无关）

### P0：地图掷骰用缓存的旧位置重试，兜底形同虚设

时间线（同一段）：

```
16:53:41.991 [tasks] 地图状态 -> SUCCESS          ← 真实 init
16:53:45.295 [tasks] 前进掷骰 -> SUCCESS
16:53:45.298 [map-roll] rolls=1 steps=20 coins=40 remain=0   ← 位置 19733 → 19753

16:54:14.395 [tasks] 地图状态 -> (cache 60s) SUCCESS   ← 命中缓存，拿到的还是 19733
16:54:16.765 [tasks] 前进掷骰 -> FAIL_BIZ_LOCATION_NOT_MATCH   req locationBefore=19733
16:54:16.770 [tasks] 前进地图重试 -> (cache 60s) SUCCESS        ← 我加的重 init 又命中缓存
16:54:20.251 [tasks] 前进掷骰 -> FAIL_BIZ_LOCATION_NOT_MATCH   req locationBefore=19733
16:54:20.252 [tasks] 前进地图重试 -> (cache 60s) SUCCESS
...（同样参数再空转 4 次）
16:54:36 [前进钓鱼] FAIL_BIZ_LOCATION_NOT_MATCH::位置不匹配
```

三个叠加缺陷：

1. **掷骰成功后没有本地推进 `locationBefore`**：真实位置已 19753，本地仍持 19733。
2. **`map.init` 被 `callApi` 的 60 秒响应缓存吃掉**：1.5.72 加的"失败后重 init 刷新"调用走的是缓存路径，日志明确标 `(cache 60s)`，拿回的还是旧位置，6 次重试同一参数。
3. **`remain=0` 后仍继续发起 roll**：16:53:45 已 `remain=0`，16:54 这轮又掷 6 次，每次都被服务端拒绝——纯风控曝光，无收益。

服务端返回 `LOCATION_NOT_MATCH` 而不是"步数不足"，所以模块没识别出是步数耗尽，把一次正常的"没步数了"演成 6 次失败请求。

对照 11-18：同一问题在 7.29.10 上出现 135 次，说明这是长期存在的缺陷，不是更新带来。

### P1：GAME_CHANNEL 场景的两个任务取不到 appkey

```
16:42:24.847 [mtop-req] data = {"taskId":"1047278691","taskScene":"GAME_CHANNEL"}   ← 无 appkey
16:42:24.912 resp ret=["FAIL_SYS_BIZPARAM_MISSED::缺少业务参数appkey"]
16:42:38.400 同上（taskId 1047276220）
```

1.5.72 的 `RhAppkeyIndex` 对绝大多数任务有效（16:38:48 起大量 `"appkey":"35334609"` 正常带入），但这两个任务来自 GAME_CHANNEL 渠道，收割源里没有它们的 taskId→appkey 映射，于是省略字段直接上报。

实际影响有限：
- 1047276220：`channel.revisit.complete` 成功 → `award` 返回 `awarded=true, rewardAmount=100`，**币已到账**
- 1047278691：revisit 报告未配对 → `TASK_NOT_COMPLETED`，未领到

即 2 个任务中 1 个仍成功，另 1 个因完成链未配对失败，不是 appkey 单点造成。

### 非问题（设计内 / 服务端行为）

| 现象 | 判定 |
|---|---|
| `ecMall push 搜一搜推荐好物` FAIL_BIZ_SERVER_ERROR ×1 | 17:06:33 `#2` 重试成功并领奖，重试机制生效 |
| `去百度地图逛一逛` / `去菜鸟抽手机` / `薅羊毛赚话费` COMMON_PARAM_ILLEGAL | 云控要求真实行为，日志已标「协议跳过」 |
| `去支付宝农场领水果` FROM_APP_REJECT: invalid from app name | 服务端校验来源 App 名，协议链不可绕 |
| `play.behavior.relation.query` RESULT_NULL ×9 | 宿主自身查询空结果 |
| `interact.time.exchange.info` NOT_TARGET_USER ×9 | 宿主自身接口，非模块调用 |
| `play.sign.in` PLAY_SIGNED_IN | 当日已签到，正常 |
| constructor hook DEAD | 该 ROM 常态，11-18 同样 DEAD，fallback 5 方法工作正常 |

## 修复方案（未动手）

按影响面排序，全部只改失败路径，正常路径零感知：

1. **兜底重 init 绕缓存**：`MapTasks` 的 LOCATION_NOT_MATCH 重试分支改为强制真实请求（跳过 `callApi` 的 60s 缓存），确保拿到服务端当前账本位置。
2. **掷骰成功后本地推进位置**：解析 roll 响应里的落点/步数，更新本地 `locationBefore` 与 `mapVersion`，下一轮不再依赖缓存。
3. **`remain=0` 短路**：解析到剩余步数为 0 时不再发起 roll，直接收口，避免无谓请求。
4. **（可选）GAME_CHANNEL 补 appkey**：从渠道任务列表响应里收割 GAME_CHANNEL 场景的 taskId→appkey，代价小、只补字段。

## 附：数据来源

- 设备日志：`/storage/emulated/0/Android/data/com.taobao.idlefish/files/zhushou_run_2026.11.19.log`
- 本地副本：`_dev/_logs/run_20261119.log`、`lvl_20261119.json`、`coin_20261119.txt`、`cash_20261119.txt`、`game_20261119.txt`
- 对照：`C:\Users\Administrator\Downloads\xiaozhushou2026.11.18_7.29.10_530.log` 及 `(1)`
