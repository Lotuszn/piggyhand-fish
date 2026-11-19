# 「延迟时间配置」一级菜单可行性报告

> 日期：2026-11-19　目标版本：1.5.73（待定）　基线：1.5.72 (53)
> 需求原文：常用功能上方新增一级菜单「延迟时间配置」，展开后五个数值项 + 顶部保存按钮，无开关，默认按 1.5.72 原配置

## 一、结论

**可行，且是现有 UI 里最省事的一类改动。** 折叠卡片、数字输入、保存按钮、配置持久化、生效提示五套范式全部现成，照着现有实例复制即可。

但有 **3 个必须解决的坑**，不处理会让"填了不准"：

1. 设备系数 0.7~1.5 会乘在用户输入上（填 2000 实际 1400~3000）
2. `jitterMs()` 有 800ms 硬地板（填 300 实际 800）
3. 「领取前延迟」建议上限 11000 与默认值 11500 冲突

## 二、UI 结构与现有范式对照

### 2.1 要做的样子

```
┌─ ⏱ 延迟时间配置 ────────────── ▼ ─┐     ← 一级卡片（bg_glass），点击折叠
│  ┌──────────────────────────┐    │
│  │        保存配置            │    │     ← Button + bg_btn_refresh
│  └──────────────────────────┘    │
│  查询间隔                 [2000] ms│     ← 12.5sp bold + bg_input + 单位
│  两次查询类请求之间的等待  建议300~2000ms │
│  触发间隔                 [3000] ms│
│  做任务动作的提交上报间隔  建议300~3000ms │
│  领取间隔                 [5000] ms│
│  领奖请求前的等待         建议300~5000ms │
│  领取前延迟              [11500] ms│
│  做完任务到点领奖的间隔   建议300~20000ms │
│  抖动                      [40] %  │
│  各项等待的随机浮动比例   建议10~60%  │
└──────────────────────────────────┘
```

每行只有「标题 + 小字 + 输入框 + 单位」，**没有 LiquidSwitch**（按需求）。

### 2.2 现成范式对照

| 要素 | 现有实现 | 位置 |
|---|---|---|
| 一级折叠卡片 | `drawerHead` + `drawerContent` + `tvDrawerArrow`，默认 gone，`animateDrawer()` 驱动 | XML `299-355`，已有 5 套实例（drawer/2/3/4/Pond） |
| 数字输入框 | `etUiScale` 那套：`bg_input` + 失焦钳制回写 | XML `1916`，Java `3239-3264` |
| 单位后缀 | TextView `textSize=9.5sp` `textColor=@color/text_sub` | 现有小字同款 |
| 按钮 | `btnExportLog`：Button + `bg_btn_refresh`，44dip 高 | XML `2898` |
| 保存到配置 | `put(key, value)` → `persistSilent()` → SharedPreferences + sdcard 副本 | Java `3345` / `3365` |
| 生效提示 | `dirty=true` → `onPause()` toast「配置已保存 · 重启闲鱼生效」 | Java `3412-3421` |

**插入位置**：XML 第 `290` 行注释「抽屉组①卡片：常用功能」之前，即新卡片排在"常用功能"卡片上方。

## 三、改动清单（4 个文件，约 390 行）

### 3.1 `activity_main.xml`（约 260 行）

新增一套卡片：

```xml
<!-- 延迟时间配置（1.5.73）：节奏参数外置，默认值 = 1.5.72 现值 -->
<LinearLayout android:background="@drawable/bg_glass" ...>
    <LinearLayout android:id="@+id/drawerHeadPace" android:clickable="true" ...>
        <FrameLayout android:background="@drawable/bg_icon_o"> <TextView android:text="⏱"/> </FrameLayout>
        <TextView android:text="延迟时间配置" android:textSize="13.5sp" android:textStyle="bold" .../>
        <TextView android:id="@+id/tvPaceArrow" android:text="▼" android:rotation="-90.0" .../>
    </LinearLayout>
    <LinearLayout android:id="@+id/drawerContentPace" android:visibility="gone" ...>
        <!-- 保存按钮 -->
        <Button android:id="@+id/btnPaceSave" android:background="@drawable/bg_btn_refresh"
                android:text="保存配置" android:layout_height="44.0dip" .../>
        <!-- 5 行输入 -->
        ...（每行：标题 / 小字 / EditText @+id/etPaceQuery|etPaceSubmit|etPaceClaim|etPaceGap|etPaceAmp / 单位）
    </LinearLayout>
</LinearLayout>
```

### 3.2 `MainActivity.java`（约 90 行）

```java
// 字段
private View drawerHeadPace, drawerContentPace;
private TextView tvPaceArrow;
private EditText etPaceQuery, etPaceSubmit, etPaceClaim, etPaceGap, etPaceAmp;
private boolean paceDrawerOpen = false;
private final AtomicBoolean paceBusy = new AtomicBoolean(false);

// 绑定（照 drawerHead2 那套）
// 点击折叠：animateDrawer((ViewGroup) drawerContentPace, !paceDrawerOpen, paceBusy)
// 加载回填：从 currentConfig() 读 5 个键，缺省填 2000/3000/5000/11500/40
// 保存按钮：
btnPaceSave.setOnClickListener(v -> {
   int q = clampInt(etPaceQuery, 2000, 300, 15000);
   int s = clampInt(etPaceSubmit, 3000, 300, 15000);
   int c = clampInt(etPaceClaim, 5000, 300, 15000);
   int g = clampInt(etPaceGap, 11500, 300, 60000);
   int a = clampInt(etPaceAmp, 40, 0, 60);
   put("paceQueryMs", q); put("paceSubmitMs", s); put("paceClaimMs", c);
   put("paceClaimGapMs", g); put("paceAmpPercent", a);
   put("paceManual", true);      // 标记：用户接管节奏，deviceFactor 让位
   Toast.makeText(this, "已保存 · 重启闲鱼生效", Toast.LENGTH_SHORT).show();
});
```

### 3.3 `ConfigManager.java`（约 15 行）

```java
public int paceQueryMs = 2000;
public int paceSubmitMs = 3000;
public int paceClaimMs = 5000;
public int paceClaimGapMs = 11500;
public int paceAmpPercent = 40;
public boolean paceManual = false;   // 用户是否手动设定过（决定 deviceFactor 是否让位）

// parse() 内：
c.paceQueryMs    = clamp(o.optInt("paceQueryMs", 2000), 300, 15000);
c.paceSubmitMs   = clamp(o.optInt("paceSubmitMs", 3000), 300, 15000);
c.paceClaimMs    = clamp(o.optInt("paceClaimMs", 5000), 300, 15000);
c.paceClaimGapMs = clamp(o.optInt("paceClaimGapMs", 11500), 300, 60000);
c.paceAmpPercent = Math.max(0, Math.min(60, o.optInt("paceAmpPercent", 40)));
c.paceManual     = o.optBoolean("paceManual", false);
```

### 3.4 `FishTaskAuto.java`（约 25 行）

```java
// 常量改为可变（保留原值作默认）
private static volatile long PACE_SUBMIT_MS = 3000L;
private static volatile long PACE_CLAIM_MS  = 5000L;
private static volatile long PACE_QUERY_MS  = 2000L;
private static volatile long CLAIM_GAP_MS   = 11500L;
private static volatile double PACE_AMP     = 0.4D;
private static volatile boolean paceManual  = false;

// launch() 第 232 行 ConfigManager.load() 之后注入：
if (var2 != null) {
   PACE_QUERY_MS  = var2.paceQueryMs;
   PACE_SUBMIT_MS = var2.paceSubmitMs;
   PACE_CLAIM_MS  = var2.paceClaimMs;
   CLAIM_GAP_MS   = var2.paceClaimGapMs;
   PACE_AMP       = var2.paceAmpPercent / 100.0D;
   paceManual     = var2.paceManual;
}

// paceFactor()：用户手动设定时返回 1.0
private static double paceFactor() {
   if (paceManual) return 1.0D;      // 用户输入即最终值，不再乘随机系数
   ...原逻辑...
}

// claimHumanGap()：
private static void claimHumanGap() { sleep(jitterMs(CLAIM_GAP_MS)); }

// jitterMs()：地板改为随 center 缩放，否则填 300 会被 800 截断
private static long jitterMs(long center) {
   long v = (long)(center * paceFactor());
   double amp = v * PACE_AMP;
   long floor = Math.min(800L, v);    // 短间隔时地板自动降到 v，长间隔仍是 800
   return Math.max(floor, v + (long)((rnd()*2-1)*amp));
}
```

## 四、三个必须解决的坑

### 坑 1：设备系数会把用户输入乘走（最关键）

现状 `fish` 每台设备首跑随机固化 0.7~1.5，用户填 2000 实际跑 1400~3000。

**处理**：`paceManual = true` 时 `paceFactor()` 直接返回 1.0。UI 上填多少就是多少。
副作用：随机系数带来的"设备间轨迹差异化"消失。折中方案是让用户填的数仍然吃 ±40% 抖动——这个保留，差异化靠抖动维持。

### 坑 2：800ms 地板会让 300~800 区间失效

`jitterMs()` 现在 `Math.max(800L, ...)`，用户填 300 得到的是 800。

**处理**：地板改为 `Math.min(800L, v)`——间隔越短地板越低，800 只对 ≥800 的间隔生效。
注意：完全取消地板会让 300ms × 0.6（±40% 下沿）= 180ms 变成准连发；`min(800, v)` 保持下限等于中心值，不会低于用户设定。

### 坑 3：「领取前延迟」默认 11500 高于建议上限 11000

需求写「建议 300~11000」，但又要求「默认按 1.5.72 原本的配置」= 11500。

**建议**：默认保持 11500，小字改成「建议 300~20000」。
理由：默认值零变化是你明确要求的；如果小字坚持写 11000，用户一看默认值 11500 就超建议范围，反而困惑。
若你坚持 11000 上限，那默认值应改成 11000（等于默认就比现在快 0.5 秒），两者只能选一个。

## 五、范围钳制（写死，不给绕过）

| 项 | 下限 | 上限 | 默认 | 依据 |
|---|---|---|---|---|
| 查询间隔 | 300 | 15000 | 2000 | 300 = 1.5.1 手感下限 |
| 触发间隔 | 300 | 15000 | 3000 | 同上 |
| 领取间隔 | 300 | 15000 | 5000 | 同上 |
| 领取前延迟 | 300 | 60000 | 11500 | 300 = 基本取消拟人延迟 |
| 抖动 | 0 | 60 | 40 | 0 = 关闭抖动；>60 会抖出负值 |

超界时：输入框自动回写为边界值（沿用 `etUiScale` 的 `clampPct` 行为），并 toast 提示。

## 六、生效时机

配置在 `FishTaskAuto.launch()` 注入 → **保存后需重启闲鱼**（或等下一轮引擎启动）。

现有 `dirty` 机制已经会弹「配置已保存 · 重启闲鱼生效」。保存按钮自带 toast，两处提示一致，不会冲突。

也可以在保存按钮的 toast 里写得更明确：「已保存 · 结束闲鱼进程后重进生效」。

## 七、风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 用户全设 300ms | 回到 1.5.1 等间隔脉冲，风控特征最强 | 用户自选，钳制拦住更低值 |
| 用户设 15000ms | 一轮跑批 3 小时，超过 240 分钟跑批周期 | `RUNNING` 原子标记已防重叠，仅表现为"很久没动静" |
| 抖动设 0 | 完全等间隔，机器特征最明显 | 用户自选 |
| 用户填非数字/空 | 解析失败 | `clampInt()` 兜底回默认值 + 回写输入框 |
| 旧配置无新键 | 读取为默认 | `optInt(..., 默认)` 天然兼容，升级零感知 |

## 八、验证方法

1. **默认回归**：不点保存，跑一轮，请求间隔分布与 1.5.72 一致（中位 0.8s / 均值 2.7s）。
2. **输入生效**：查询填 300 → 实测单引擎查询间隔 300×(1±0.4) = 180~420ms，不再是 800ms 地板。
3. **系数让位**：查询填 2000 → 实测 1200~2800ms（只有 ±40% 抖动），不再有 0.7~1.5 的额外放大。
4. **抖动归零**：抖动填 0 → 连续三次查询间隔完全相同（秒级精度）。
5. **钳制**：查询填 50 → 保存后输入框自动变 300；填 99999 → 变 15000。
6. **生效时机**：保存后不重启闲鱼，当前跑批节奏不变；杀进程重进后新值生效。

## 九、工作量合计

| 文件 | 行数 | 内容 |
|---|---|---|
| `activity_main.xml` | ~260 | 一套折叠卡片 + 5 行输入 + 按钮 |
| `MainActivity.java` | ~90 | 字段、绑定、折叠、回填、保存、钳制 |
| `ConfigManager.java` | ~15 | 6 个字段 + parse + DEFAULT_JSON |
| `FishTaskAuto.java` | ~25 | 常量可变化 + launch 注入 + 系数让位 + 地板修正 |
| **合计** | **~390 行 / 4 文件** | 不触碰 hook、协议、响应通道 |

## 十、待你确认的三件事

1. **设备系数是否让位**：用户保存后按 1.0 计（填多少是多少），我建议是。
2. **「领取前延迟」建议区间**：默认 11500 保持不变 + 小字写 300~20000；还是默认改 11000 对齐 300~11000？建议前者。
3. **是否需要「恢复默认」**：本次需求没提。加一个文字链「恢复默认值」成本 5 行，可选。
