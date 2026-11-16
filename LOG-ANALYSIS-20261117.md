# 双用户日志分析：地图加载失败根因（2026-11-17）

> **日志**：`xiaozhushou2026.11.17_7.29.10_530 (1).log`（用户 A，1158 行）+ `(2).log`（用户 B，41644 行）
> **两台设备**：A = Redmi 23113RKC6C / **Android 16 (API 36)** / 模块 1.5.7(51) / **全新首跑**（`last run never`，台账文件不存在）
> B = Redmi 22041211AC / Android 14 / 模块 1.5.7(51) / 11-16 已升 1.5.7（昨晚分析过的同一用户）
> **结论先行**：两个用户的"地图加载失败"**不是同一个问题，且都不是地图功能本身的 bug** —— A 是引擎直调网络通道在该设备上整体不可用（地图只是受害者之一），B 是晚间时段网关断连潮下的偶发牺牲品。

---

## 用户 A：直调通道整体覆没（设备级问题）

### 失败画像

引擎从 00:49 启动到 00:58 导出，**所有任务 api 无一成功**：

```text
00:49:29 [tasks] 签到       -> GATEWAY_REJECT (200B)
00:49:33 [tasks] 玩一玩签到 -> GATEWAY_REJECT (200B)
00:49:39 [tasks] 地图状态   -> GATEWAY_REJECT (200B)   ← 用户看到的"地图加载失败"
00:49:42 [tasks] 圈数挑战   -> GATEWAY_REJECT (200B)
00:49:47 [tasks] 擦亮       -> GATEWAY_REJECT (200B)
00:49:51 [tasks] 任务列表   -> GATEWAY_REJECT (200B)
00:50:22 [tasks] 背包列表   -> GATEWAY_REJECT (200B)
00:51:36 [tasks] 币余额     -> GATEWAY_REJECT (200B)
00:52:56 [tasks] 探测       -> GATEWAY_REJECT (200B)
```

18 个 raw 取证档全部是同一形态：

```text
MtopResponse[ api=mtop.taobao.idle.treasure.hunt.map.init, ...
  retCode=ANDROID_SYS_NETWORK_ERROR, retMsg=网络错误,
  ret=null, data=null, responseCode=-205, headerFields=null, bytedata=null ]
```

（部分 api 为 -405，语义相同层级）

### 三个决定性判据

1. **`headerFields=null, bytedata=null`**：没有收到任何 HTTP 响应——不是服务端给了拒绝码（服务端拒绝会返回 JSON 带 `ret`），是**连接层直接失败**，mtop 客户端把 IOException 包装成 `ANDROID_SYS_NETWORK_ERROR`。"GATEWAY_REJECT (200B)" 是引擎对非 JSON 响应的归因显示，真身是网络层断连。
2. **同进程主 App 完全正常**：`[pair] MATCHED` 的 30+ 个 api（idlemessage.session.sync、home.nextfresh、idleadv 广告、ab.config 等）全是闲鱼主界面自己发的，秒回。设备网络、账号、cookie 都没问题——**只有模块直调通道（ApiDirect → MtopLauncher）出的请求全部失败**。
3. **失败时延恒定 ~3.2 秒**（00:49:35.9 发 → 00:49:39.1 报拒，00:50:13.6 → 00:50:16.9 同样 3.2s）：mtop 内部超时特征，请求在客户端网络栈里超时/被断，没到业务层。对照用户 B 同 api 成功时 elapsed 仅 130~320ms。

### 设备差异变量

| 变量 | 用户 A（全挂） | 用户 B（正常） |
|------|--------------|--------------|
| Android | **16 (API 36)** | 14 (API 34) |
| 引擎历史 | 全新首跑 | 已连续跑 4 天 |
| map.init 请求参数 | `{"fishAdCode":"421303","gps":"31.72…","version":4}`（齐全） | `{"version":4}`（照样成功） |

A 的 `[compat]` 段还有一条强佐证：

```text
00:49:36.021 [compat] self-check @25s: ctorHits=0 methodHits=0
00:49:36.027 [compat] constructor hook DEAD on this ROM, arming method-level fallback...
00:49:36.029 [compat] fallback armed on EventCenter: 0 method(s) hooked
```

兼容层已在 Android 16 上检出**构造器 hook 失效**并降级。ROM 行为差异实锤——同一份闲鱼 7.29.10，在 API 36 上构造器 hook 死了，直调链路的底层形态也与 API 34 不同。

### 定性

**A 的"地图加载失败"= 引擎直调通道在 Android 16 上整体不可用**。地图状态获取（`map.init`）是跑批里用户感知最直的一条（"前进钓鱼 地图状态获取失败，跳过"），但它和签到、任务列表、擦亮是同一个死法。复现变量最可能按序是：Android 16 (API 36) > 全新设备首跑 > 闲鱼 7.29.10 在 API 36 的 mtop 通道初始化差异。需要一台 Android 16 真机复现定位（当前兼容基线 floor=7.29.10 是按 API 34 设备验证的，**API 36 不在兼容矩阵已验证项里**）。

---

## 用户 B：晚间时段网关断连潮（账号级问题）

### 与 A 完全不同的画像

B 的地图链**白天全通**：`[map-roll]` 18 次全部成功（steps 3~20、单次 0~176 币），昨天全灭的背包骰子今天也正常用了（火箭骰子/双倍骰子/八面骰 `used=1/1`）。map.init 仅零星失败（00~13 时每小时 0~2 次，60s cache + 重试兜住）。

**恶化从 19:00 开始**：

| 小时 | map.init 失败 | 同时段其他失败 |
|------|--------------|---------------|
| 00~13 | 各 1~2 次（偶发） | 无 |
| 19 | **6 次** | task.queryolivertasklist×3、ad.event.push×3、币余额/探测开始连拒 |
| 20 | **7 次** | queryolivertasklist×11、cycle.challenge×2 |
| 21 | **9 次** | queryolivertasklist×7 |
| 22 | **7 次** | queryolivertasklist×4 |
| 23 | **5 次** | queryolivertasklist×4 |

同时 `[tasks] 币余额 -> GATEWAY_REJECT` 和 `探测 -> GATEWAY_REJECT` 从 19:22 起几乎**每 2~3 分钟一次，持续到 23:53 深夜**（约 70+ 次无效曝光）。

### 但引擎主链还在赚钱

19:20~20:40 拒绝潮最猛的窗口里，map.init 仍有 17 次 MATCHED 成功、map-roll 持续到账（18:59 +134 币、19:06 +38、19:11 +86、19:20 +16）。**写操作（掷骰/领奖）和查询（列表）部分存活**，失败集中在高频探针（币余额、探测）和部分查询 api。

### 定性

**B 的"地图加载失败"= 晚间时段（19 点后）网关对该账号/设备的直调请求间歇性断连**（连接层 RST，无 HTTP 响应 → -205）。三点佐证：

1. **时段性**：白天 13 小时几乎零失败，19 点后恶化且持续到深夜——闲鱼晚间在线高峰，网关风控策略收紧的典型形态；
2. **探针是放大器**：币余额 + 探测两个探针整晚 2~3 分钟/次的频率在拒绝潮里反复撞墙（70+ 次曝光），进一步固化"该设备可疑"判定；
3. **非全灭**：主任务链（掷骰/领奖/背包）大部分存活，只有查询类间歇被断——与 A 的 100% 覆没完全不同级别。

另注：B 的任务列表里有一条 `去百度地图逛一逛 -> FAIL_BIZ_COMMON_PARAM_ILLEGAL::参数不合法（云控判定需真实行为）`——名字带"地图"但这是外部跳转任务（去百度地图 App 逛一逛），被云控拒属正常业务判定，与 treasure.hunt 地图无关，若用户把它也报成"地图问题"属误认。

---

## 共性结论与方向建议（仅分析，不动手）

**"地图加载失败"在这两份日志里都不是 map.init 功能缺陷**：

- A：直调通道在 Android 16 上**全灭**（连接层失败，9 分钟 0 成功）——**设备/ROM 级兼容问题**，Android 16 (API 36) 是最大嫌疑变量，兼容矩阵需补 API 36 验证项；
- B：晚间网关断连潮的**偶发受害者**——账号/设备级时段风控 + 高频探针曝光放大。

方向建议（按收益排序）：

1. **A 类修复（Android 16 兼容）**：找一台 API 36 设备复现；优先排查 `MtopLauncher` 反射直调在 API 36 的行为差异与 compat 层"constructor hook DEAD"后的请求侧降级路径；当前 compat 只对**响应捕获**做了 method-level fallback，**请求发出侧没有降级**——这是 A 全灭的嫌疑核心。
2. **首跑探活**：引擎跑批前先发一发轻量探测 api，连续 -205 时整轮跳过并在报告里明示"通道未就绪"，避免像 A 那样 9 分钟刷几十发注定失败的请求（徒增风控曝光）。
3. **B 类修复（拒绝潮退避）**：币余额/探测两个探针在连续 GATEWAY_REJECT 后应进入**小时级长退避**（当前 10min 熔断在整晚拒绝潮里等于没退），或按时段自动降频（19~24 点停探针）。
4. **报告文案**：`GATEWAY_REJECT (200B)` 对 -205 断连是一种误标——`headerFields=null` 时应显示 `NETWORK_LOST(直调连接失败)`，避免用户和开发都被"网关拒绝"四个字带偏方向（这次定位走了弯路，先怀疑了网关拒绝业务策略，raw 档才看清是连接层断）。
