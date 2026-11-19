# 1.5.1 vs 1.5.72 执行速度对比：慢在哪一行

> 日期：2026-11-19　对比基准：`git tag v1.5.1`（`456a8ab`）vs 当前 `48e1086`（1.5.72/53）
> 实测样本：`zhushou_run_2026.11.19.log`，模块自身请求 653 个

## 一、一句话结论

**新版慢 8~10 倍，控制点是 `FishTaskAuto.pace()`。** 1.5.1 每个请求前固定等 300 毫秒，1.5.72 按请求类别等 2/3/5 秒，再乘设备系数 0.7~1.5，再抖 ±40%；另外每次领奖前多了一段 11.5 秒的拟人延迟，1.5.1 完全没有这个。

## 二、逐行对比

### 2.1 发送前等待（绝对主因）

**1.5.1** `app/src/main/java/com/piggyhand/fish/FishTaskAuto.java:2332-2336`

```java
private JSONObject callApi(String var1, String var2, String var3, String var4) {
   String var5 = this.respFile(var1);
   sleep(300L);                                  // ← 固定 300ms，无分类、无系数、无抖动
   String var6 = this.fileFp(var5);
   boolean var7 = ApiDirect.send(this.cl, var1, var2, var3);
```

**1.5.72** `app/src/main/java/com/piggyhand/fish/task/FishTaskAuto.java:1914-1924`

```java
private static void pace(String api) {
   long center;
   if (api.contains("benefit") || api.contains("claim") || api.contains("signin")) {
      center = PACE_CLAIM_MS;        // 5000ms
   } else if (api.contains("trigger") || api.contains("polish")
           || api.contains("bet") || api.contains("lottery")) {
      center = PACE_SUBMIT_MS;       // 3000ms
   } else {
      center = PACE_QUERY_MS;        // 2000ms
   }
   sleep(jitterMs(center));          // × 设备系数 × (1±40%)
}
```

调用点 `FishTaskAuto.java:1711`，与 1.5.1 的 `sleep(300L)` 是同一个位置——**同一行代码，从 300ms 变成 2000/3000/5000ms**。

### 2.2 两个新增乘数

`FishTaskAuto.java:1878-1910`

```java
private static double paceFactor() {          // 1.5.1 无此函数
   double v = 0.7D + rnd() * 0.8D;            // 首跑随机 0.7~1.5，固化到 rh_pace/device_factor
   ...
}

private static long jitterMs(long center) {
   long v = (long)(center * paceFactor());    // 先乘设备系数
   double amp = v * PACE_AMP;                 // 再 ±40%
   return Math.max(800L, v + (long)((rnd()*2-1)*amp));   // 硬下限 800ms
}
```

1.5.1 没有这两个东西。

### 2.3 领取前拟人延迟（第二主因）

**1.5.72** `FishTaskAuto.java:1927-1929` + 调用点 `1586`

```java
private static void claimHumanGap() {
   sleep(jitterMs(11500L));     // 约 7~16 秒
}
// claimOne() 开头：
claimHumanGap();                // 2026-11-15 加：触发→领取拟人延迟
```

**1.5.1** `FishTaskAuto.java:2275-2277`（当时的 `claimOne`）

```java
private void claimOne(String var1, String var2, List<String> var3, List<String> var4) {
   ... // 直接调 callApi，没有任何前置等待
   "mtop.taobao.idle.task.getolivertaskbenefit",
```

## 三、量化：同一批请求，两版各等多久

今天日志实测模块自身请求 653 个：查询 542 / 触发 74 / 领取 37。

| 口径 | 1.5.1 | 1.5.72（中心值） | 1.5.72（×设备系数 1.1 估算） |
|---|---|---|---|
| 查询 542 次 | 162s | 1084s | 1192s |
| 触发 74 次 | 22s | 222s | 244s |
| 领取 37 次 | 11s | 185s | 204s |
| **发送前等待小计** | **196s（3.3 分钟）** | **1491s（24.9 分钟）** | **1640s（27.3 分钟）** |
| 领取拟人延迟 | **0**（功能不存在） | 35 次 × 11.5s = 403s | 35 次 × 12.7s = **443s（7.4 分钟）** |
| **合计纯等待** | **3.3 分钟** | **28.3 分钟** | **34.7 分钟** |

**倍数：8.4 倍（发送等待）／约 10 倍（含拟人延迟，估算）。**

单请求倍数（按设备系数中位 1.1）：

| 类别 | 1.5.1 | 1.5.72 区间 | 中位倍数 |
|---|---|---|---|
| 查询 | 300ms | 840~4200ms | **7.3×** |
| 触发 | 300ms | 1260~6300ms | **11×** |
| 领取 | 300ms | 2100~10500ms | **18.3×** |
| 领取前 gap | 无 | 7~16s | 新增 |

## 四、哪些环节没变（排除项）

| 环节 | 1.5.1 | 1.5.72 | 判定 |
|---|---|---|---|
| 引擎入口错峰 | 鱼塘 40s / 投喂 46s / 会员 45s / 经验 20s / 游戏 50s / 游戏币 240s | 完全相同 | **不是原因** |
| 执行器固定等待数值 | 1200/2000/6000/5000/30000/3000/8000ms | 同值（`sleepJitter` 包装） | 数值没变，只多了 ×1.1 系数 |
| 响应轮询间隔 | 400ms | 400ms | 相同 |
| 跑批周期 | 240 分钟 | 240 分钟 | 相同 |

执行器那 20 处固定等待（`sleepJitter`）只贡献约 10% 增量，不是主因。

## 五、为什么"感觉"比数字更慢

1. **等待被摊到每个动作前**：1.5.1 是 300ms 一闪而过，用户几乎无感；1.5.72 每点一下要等 2~10 秒，主观差距远大于 8 倍。
2. **领奖前那段 11.5 秒最扎眼**：任务做完到钱到账之间干等 7~16 秒，这是新增的、1.5.1 完全没有的空白。
3. **功能变多**：1.5.1 没有渠道 25 项浏览、cube、forest 等，请求基数比现在小。按"同等任务量"算是 8 倍，按"整轮绝对时长"看差距更大。
4. **节奏被打散**：抖动 ±40% 让间隔忽长忽短，主观上比匀速更"顿"。

## 六、控制点的精确公式

```
单次请求等待 = center(api) × deviceFactor × (1 ± 0.4)，地板 800ms
              center = 5000  ← api 含 benefit / claim / signin
                     = 3000  ← api 含 trigger / polish / bet / lottery
                     = 2000  ← 其余全部
              deviceFactor = 首跑随机 0.7~1.5，固化在 rh_pace/device_factor

领取额外延迟 = 11500 × deviceFactor × (1 ± 0.4)
```

改动来源（可追溯）：`e5b3ae3`（2026-11-15 风控节奏整形，引入 `pace()` + `claimHumanGap()`）、`bff576b`（节奏整形补全，18 处固定 sleep 收进 `sleepJitter`）。

## 七、想恢复到 1.5.1 的手感，三档方案

| 方案 | center(查询/触发/领取) | 领取 gap | 效果 | 代价 |
|---|---|---|---|---|
| 完全还原 | 300 / 300 / 300 | 关（0） | 与 1.5.1 一致，整轮约 3~4 分钟 | 回到 300ms 等间隔脉冲，节奏整形归零 |
| 折中（推荐） | 1200 / 1500 / 2000 | 5000 | 提速约 3~4 倍，整轮约 8~10 分钟 | 保留非等间隔特征，风控特征比 1.5.1 弱 |
| 仅掐 gap | 2000 / 3000 / 5000（不动） | 关（0） | 提速约 1.3 倍 | 只去掉最扎眼的领奖空等 |

三档方案正好对上一份《步频外置接口可行性报告》：接口做出来后，这三个数交给用户填，不需要每次改代码重发版。

## 八、数据来源

- 1.5.1 源码：`git show v1.5.1:app/src/main/java/com/piggyhand/fish/FishTaskAuto.java`（2575 行，导出副本 `_dev/_v151_FishTaskAuto.java`）
- 1.5.72 源码：`app/src/main/java/com/piggyhand/fish/task/FishTaskAuto.java`（1952 行）
- 实测日志：`_dev/_logs/run_20261119.log`
- 引擎延迟对比：v1.5.1 的 `RhCoinTask/RhGameTask/RhPondTask/RhOlivertask` 对应 `scheduled in` 日志字面量
