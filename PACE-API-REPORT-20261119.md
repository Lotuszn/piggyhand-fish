# 步频（请求节奏）现状分析与自定义接口可行性报告

> 日期：2026-11-19　版本：1.5.72 (53)　闲鱼：7.29.20 (531)
> 实测样本：`zhushou_run_2026.11.19.log`，8706 行，653 个模块自身请求

## 一、结论

| 问题 | 结论 |
|---|---|
| 现在的步频是多少 | **不是一个数，是四层**：① 单请求间隔 2/3/5 秒（查询/触发/领取）× 设备系数 0.7~1.5 × ±40% 抖动；② 领取前拟人延迟 11.5 秒；③ 引擎入口错峰 20/40/45/46/50/240 秒；④ 跑批周期 240 分钟 |
| 实测全局密度 | 653 个请求 / 约 31 分钟 ≈ **21 请求/分钟**，相邻请求间隔中位 **0.8 秒**（多引擎并行交错的结果） |
| 能否外置成用户接口 | **可行**。代码已有单点收口（`pace()` 在 `callApi` 唯一发送前）+ 现成配置通道（`config_json`）+ 现成数值输入 UI 范式（`etUiScale`） |
| 工作量 | 约 250 行改动、5 个文件，无架构变动 |
| 主要代价 | 节奏调快 = 用户自担风控；调慢 = 跑批时长可能超过引擎窗口，需与跑批周期联动钳制 |

## 二、当前步频的四层结构（代码事实）

### 第 1 层：单请求间隔（核心，已单点收口）

`FishTaskAuto.java:94-97`

```java
private static final long PACE_SUBMIT_MS = 3000L;   // 触发类
private static final long PACE_CLAIM_MS  = 5000L;   // 领取类
private static final long PACE_QUERY_MS  = 2000L;   // 查询类（默认）
private static final double PACE_AMP     = 0.4D;    // ±40% 抖动
```

`FishTaskAuto.java:1914-1924` 分类规则：

```java
private static void pace(String api) {
   long center;
   if (api.contains("benefit") || api.contains("claim") || api.contains("signin")) {
      center = PACE_CLAIM_MS;                       // 5s
   } else if (api.contains("trigger") || api.contains("polish")
           || api.contains("bet") || api.contains("lottery")) {
      center = PACE_SUBMIT_MS;                      // 3s
   } else {
      center = PACE_QUERY_MS;                       // 2s
   }
   sleep(jitterMs(center));
}
```

`FishTaskAuto.java:1906-1910` 抖动与下限：

```java
private static long jitterMs(long center) {
   long v = (long) (center * paceFactor());         // × 设备系数
   double amp = v * PACE_AMP;                        // ±40%
   return Math.max(800L, v + (long)((rnd()*2-1)*amp)); // 下限 800ms
}
```

设备系数 `FishTaskAuto.java:1878-1903`：首跑随机 `[0.7, 1.5]`，固化到 `rh_pace` / `device_factor`。

**实际落点**：QUERY 1400~3000ms（±40% → 840~4200ms）、SUBMIT 2100~4500ms（1260~6300ms）、CLAIM 3500~7500ms（2100~10500ms）。

**收口位置**：`FishTaskAuto.java:1711`，`callApi()` 里 `synchronized(RespChannel.lockFor(api))` 之后、`ApiDirect.send()` 之前。所有 mtop 直调只走这一处，任务执行器零改动——这是外置接口最大的有利条件。

**覆盖缺口**：`map.init` 60 秒缓存命中时（`FishTaskAuto.java:1688-1696`）在 `pace()` 之前就 return，不走节奏等待（因为是复用，不发请求，合理）。

### 第 2 层：领取前拟人延迟

`FishTaskAuto.java:1927-1929`

```java
private static void claimHumanGap() {
   sleep(jitterMs(11500L));   // 约 7~16 秒
}
```

调用点 `FishTaskAuto.java:1586`，模拟"人做完任务再回来领奖"的间隔。

### 第 3 层：引擎入口错峰 + 执行器内固定等待

| 位置 | 值 | 说明 |
|---|---|---|
| `RhPondTask.java:168` | 40000ms | 鱼塘 auto-run |
| `RhPondTask.java:267` | 46000ms | 投喂 auto-run |
| `RhOlivertask.java:100` | 45000ms | 会员 auto-run |
| `RhOlivertask.java:193` | 20000ms | 会员经验 |
| `RhGameTask.java:120` | 50000ms | 游戏 auto-run |
| `RhCoinTask.java:143` | 240000ms | 游戏币 auto-run（`AUTO_DELAY`） |
| `RhChGate.awaitChannel(base, 90000)` | +0~90s | 通道未就绪时顺延 |

执行器内另有 **20 处 `sleepJitter(...)`** 固定等待，数值 1000/1200/1500/2000/2500/3000/5000/6000/8000/30000ms：
`FishTaskAuto.java` 695/705/715/725/735/777/834/845/928/930/933/986/995/999/1268/1272/1348/1353/1488/1496，`MapTasks.java:602`。它们同样过 `jitterMs()`，所以自动吃到设备系数与 ±40% 抖动，但不分类（不区分查询/触发/领取）。

### 第 4 层：跑批周期与探针

| 参数 | 值 | 位置 |
|---|---|---|
| 自动跑批间隔 | 240 分钟（UI 可调 0~1440） | `autoRunIntervalMin` |
| 余额探针周期 | 900s / 突发 300s / 失败 180s | `FishTaskAuto.java:87-89` |
| 金币瓜分检查 | 30 分钟 | `[join] next check in 30m` |
| 通道就绪顺延上限 | 90s | `RhChGate` |

## 三、今日实测数据（653 个请求）

模块自身发起（`[direct] sent`）分类：

| 类别 | 次数 | 中心值 | 理论累计等待 |
|---|---|---|---|
| QUERY（2s） | 542 | 2000ms | 1084s |
| SUBMIT（3s） | 74 | 3000ms | 222s |
| CLAIM（5s） | 37 | 5000ms | 185s |
| 合计 | **653** | — | **1491s ≈ 25 分钟** |

日志区间 16:37:58 ~ 17:09:37（31 分钟），与理论累计等待基本吻合，差额来自引擎并行（多线程同时等待）。

相邻请求间隔分布（651 个样本）：

| 指标 | 值 |
|---|---|
| 最小值 | 0.0s |
| P25 | 0.3s |
| **中位** | **0.8s** |
| P75 | 2.4s |
| 最大值 | 59.5s |
| 均值 | 2.7s |

**中位数 0.8 秒低于任何一档中心值**，原因是五个引擎并行：`16:37:58` 同时排程，随后 pond(40s)、pondfeed(46s)、oliver(45s)、game(50s) 在 16:38:38~16:38:50 集中启动，16:41:58 coin 加入。单引擎守自己的 2/3/5 秒，日志是全局时间线，交错后表面间隔被压到 0.3~0.8 秒。

另外宿主自身的请求（478 个）不受模块节奏控制，混杂在同一条时间线上。

**这意味着"步频"对用户实际是"全局请求密度"，不是单个 pace 值。**

## 四、外置接口设计

### 4.1 配置层

`config_json` 新增键（全部可选，缺省即当前行为）：

```json
"paceQueryMs": 2000,         // 查询类中心值
"paceSubmitMs": 3000,        // 触发类中心值
"paceClaimMs": 5000,         // 领取类中心值
"paceClaimGapMs": 11500,     // 领取前拟人延迟
"paceAmpPercent": 40,        // 抖动幅度 0~60
"paceFactorPercent": 100     // 用户总倍率 30~300（100 = 不改）
```

`ConfigManager.parse()` 加 6 行读取 + 钳制；字段同步进 `DEFAULT_JSON` 便于用户直接改文件。

### 4.2 执行层（新增 `task/RhPace.java`，约 80 行）

```java
public final class RhPace {
    static volatile long queryMs = 2000L;
    static volatile long submitMs = 3000L;
    static volatile long claimMs = 5000L;
    static volatile long claimGapMs = 11500L;
    static volatile double amp = 0.4D;
    static volatile double userFactor = 1.0D;

    /** 一次跑批注入一次：跑批中途改配置不影响当前波次，符合"下次生效"直觉 */
    public static void apply(ConfigManager c) { ... }
    public static long centerFor(String api) { ... }
    public static long jitter(long center) { ... }
}
```

`FishTaskAuto` 改动 5 处：

1. `launch()` 第 232 行 `ConfigManager var2 = ConfigManager.load();` 之后加 `RhPace.apply(var2);`
2. `pace()` 改为 `sleep(RhPace.jitter(RhPace.centerFor(api)))`
3. `jitterMs()` 改为委托 `RhPace.jitter()`
4. `claimHumanGap()` 改为 `sleep(RhPace.jitter(RhPace.claimGapMs))`
5. `sleepJitter()` 改为委托（自动吃到倍率）

原 `PACE_*` 常量保留为默认值来源，`RhPace` 未初始化时行为与现在完全一致——**升级零感知**。

### 4.3 UI 层

沿用现有范式（`etUiScale` 那套：EditText + `put(key, value)` + 失焦钳制回写），在「常用功能」下加一行「执行节奏」二级页：

```
执行节奏
  查询间隔    [2000] ms   (500~15000)
  触发间隔    [3000] ms   (500~20000)
  领取间隔    [5000] ms   (500~30000)
  领奖延迟   [11500] ms   (2000~60000)
  抖动幅度     [ 40] %    (0~60)
  总倍率      [100] %    (30~300)
```

或简化为三档预设（保守 150% / 标准 100% / 激进 70%）+ 高级展开手动填。控件与配色直接复用 `bg_input`、`text_sub`、`bg_glass`，与现有页面一致。

### 4.4 边界钳制（写死，不给用户绕过）

| 项 | 下限 | 上限 | 理由 |
|---|---|---|---|
| 查询间隔 | 500ms | 15000ms | 低于 500ms 会连发 |
| 触发间隔 | 500ms | 20000ms | 同上 |
| 领取间隔 | 500ms | 30000ms | 同上 |
| 领奖延迟 | 2000ms | 60000ms | 太短失去拟人意义 |
| 抖动幅度 | 0% | 60% | 超过 60% 会抖出负数被下限截断 |
| 总倍率 | 30% | 300% | 30% 以下等于连发；300% 以上跑批超时 |

`jitterMs` 现有的 800ms 硬下限保留，作为最后一道防连发闸——即使配置写 0 也发不出连发脉冲。

## 五、可行性判定

### 可做的依据

1. **有单点收口**：所有模块请求经 `callApi` → `pace()`，改一处覆盖全部（653 个请求里的 653 个）。
2. **有现成配置通道**：`config_json` 单键存整段 JSON，加键不改存储结构；`ConfigManager.parse()` 是唯一读取入口。
3. **有现成注入点**：`launch()` 已 `ConfigManager.load()` 并设置 `toastOn`/`polishOn`，节奏参数照抄这个模式即可。
4. **有现成 UI 范式**：11 个 EditText 数值输入（`etUiScale`/`etFeedCols`/`etBag*`），钳制回写逻辑可复用。
5. **默认值即当前行为**：不填 = 1.5.72 现状，不存在升级回归风险。

### 需要决策的三个点

**决策 1：用户总倍率与设备系数是否叠乘？**
现状 `paceFactor()` = 0.7~1.5 随机固化。若用户设 100%，倍率 1.0 × 设备系数 1.3 = 实际 130%，用户会疑惑"设了 100 怎么还慢"。
建议：**用户显式设置过倍率（≠100%）时，设备系数强制为 1.0**，即用户意志覆盖随机性；未设置时保持现状。这样 UI 上填的数就是真实秒数。

**决策 2：20 处 `sleepJitter` 固定值是否纳入倍率？**
纳入（改 `sleepJitter` 委托 `RhPace.jitter`）才能整体调快慢，否则用户把查询压到 500ms，仍会被 2000/6000/30000ms 的固定等待拖住。
建议：**纳入**，且这 20 处跟随总倍率，不单独暴露给用户（暴露 26 个数字没人填得明白）。

**决策 3：引擎入口延迟是否纳入？**
五个引擎的 40/45/46/50/240 秒错峰不属于"步频"，属于"跑批编排"。调快它们会让多引擎同时轰击，反而提高风控暴露，且与 P0-C 通道时序门耦合。
建议：**不纳入本次接口**，保持现状；只开放第 1、2 层（真正意义上的请求节奏）+ 第 3 层的 `sleepJitter` 固定等待。

### 风险清单

| 风险 | 影响 | 处置 |
|---|---|---|
| 用户设极快（30% 倍率） | 风控暴露上升，可能触发限流 | 钳制下限 + UI 文案标注档位含义，选择权归用户 |
| 用户设极慢（300% 倍率） | 跑批时长翻 3 倍，可能跨过 240 分钟跑批周期，两次跑批重叠 | `RUNNING` 原子标记已防重叠（`engine already running, trigger ignored`），无需额外处理 |
| 配置写错（负数/超大） | 解析异常 | `parse()` 内钳制 + 异常兜底回默认值 |
| 旧版本配置文件无新键 | 读取为默认值 | `optInt(..., 默认)` 天然兼容 |
| 跑批中途改配置 | 当前波次不生效 | 设计为 `launch()` 注入，下次跑批生效，符合直觉 |

## 六、落地步骤（未实施）

| 步 | 文件 | 改动 | 量 |
|---|---|---|---|
| 1 | `task/RhPace.java` | 新建：常量 + clamp + centerFor + jitter | ~80 行 |
| 2 | `config/ConfigManager.java` | 6 个字段 + `parse()` 6 行 + `DEFAULT_JSON` 补键 | ~12 行 |
| 3 | `task/FishTaskAuto.java` | `launch()` 注入；`pace`/`jitterMs`/`claimHumanGap`/`sleepJitter` 四处委托 | ~20 行 |
| 4 | `res/layout/activity_main.xml` | 「执行节奏」二级页 6 行输入 | ~140 行 |
| 5 | `ui/MainActivity.java` | 6 个 EditText 绑定 + 钳制回写（抄 `etUiScale`） | ~50 行 |
| 6 | 验证 | 装机后对比跑批耗时与请求间隔分布，确认默认值下与 1.5.72 一致 | — |

总计约 300 行，5 个文件，不触碰 hook 层、不触碰响应通道、不改协议。

## 七、验证方法（落地后）

1. **默认值回归**：不填任何新键，跑一轮，相邻 `[direct]` 间隔分布应与本报告第三节一致（中位 0.8s、均值 2.7s）。
2. **倍率生效**：设 50%，同样任务量下总耗时约减半，`[mtop-req]` 时间戳密度翻倍。
3. **下限保护**：查询间隔填 0，实测间隔不得低于 800ms。
4. **下次生效**：跑批中途改配置，当前波次时间戳不变，重启闲鱼后新值生效。
