# 三份用户日志修复方案（2026-11-17）

> 依据：用户 A（Android 16 全灭）、用户 B（晚间断连潮）、用户 C（鱼塘全败存标记 + 日志页弹窗）三份日志分析。
> 按优先级排列，每条含：证据 → 改动文件:行 → 改法 → 验证方法。

---

## P0-A · auto-run 当日标记结果化

**问题**（用户 C 鱼塘没做根因）：`RhPondTask.java:114-130` 跑完 `RhPondTask.run(ctx)` 后**无条件**写当日标记 `rh_pond_date.txt`，全败也存 → 全天 `already done today, skip`（C 实录：00:35 全败存标记，09:46/14:32/14:43/18:05 四次 skip）。同模式还有 pondfeed(:213)、game(RhGameTask.java:104)、oliver(RhOlivertask.java:82)。

**改法**：
1. `RhPondTask.run(ctx)` 返回本轮成功计数（int，报告已有数据，补个返回值/静态槽）；
2. auto 线程按结果分档：`成功数 > 0` → 落当日标记（现状）；`成功数 == 0` → **不落完成标记**，改写"已尝试"标记（新文件 `rh_pond_try.txt`，内容 = 日期+次数），同日全败重试上限 3 次，超限后照旧落完成标记（防死循环刷请求）；
3. `LvlLedger.bumpShared`（:116，注释写明"无论成败都计一次"）改为**仅有成功时计数**——全败不该白耗每日 2 次额度；
4. 四条链（pond/pondfeed/game/oliver）同改。

**验证**：真机断网/防火墙拦 9090 端口模拟全败 → 标记文件不出现 `rh_pond_date.txt` 当日值、出现 try 计数 → 重启闲鱼后重跑；恢复网络 → 成功 → 落标记不再重跑。

---

## P0-B · 跑批前直调通道探活

**问题**（用户 A）：直调通道全灭时引擎照常把全部任务请求挨个打进死通道——9 分钟几十发请求全部 -205，零产出纯曝光。

**改法**（`FishTaskAuto` 跑批入口 / `TaskRegistry.onCommand` 的 RUN 类命令前）：
1. 每轮跑批先发 1 发轻量探测（`mtop.common.getTimestamp`，主 App 自用 api、零风险）；
2. 失败（非 JSON / responseCode<0）→ 间隔 30s 重试 1 次；
3. 仍失败 → **整轮 skip**，报告首行明示 `直调通道未就绪（ANDROID_SYS_NETWORK_ERROR -205），本轮 N 项任务跳过，下轮自动重试`；
4. 探活通过 → 照常跑批（无额外开销，1 发请求）。

**验证**：A 场景日志从"几十发全败"变为"1 发探活失败 + 整轮 skip + 明示原因"；正常设备每轮多 1 发探测请求（可 60s 缓存复用）。

---

## P0-C · Android 16 请求侧兼容定位（需 API 36 真机）

**问题**（用户 A 全灭 vs 用户 C 部分可用）：`constructor hook DEAD on this ROM` 在 Android 16 上 100% 复现（A×1、C×8）；compat 层只对**响应捕获**做了 method-level fallback（MtopResponse 5 方法），**请求发出侧（MtopLauncher 反射直调）没有任何降级路径**。A 全灭（9 分钟 0 成功）、C 老账号白天可通——差异变量为账号/设备冷启动状态。

**改法**（三步，前两步可先行）：
1. **时序门**（无需真机也能做）：引擎跑批延迟至"主 App 首个成功 mtop 响应被捕获之后"（RespChannel 已有 MATCHED 流，取首个作为就绪信号），替代现在的固定 40s sleep——新设备冷启动时 mtop 栈就绪更晚，固定 40s 不够；
2. **真机复现**：找 API 36 设备（用户 A 的 23113RKC6C / 用户 C 的 23116PN5BC 同型可求日志），对比 `MtopLauncher` 直调在 API 34/36 的调用差异（混淆名漂移用 Symbols 候选表扩候选）；
3. 若确认 API 36 直调死路，增加请求侧 fallback（ApiMtop 旁路），并更新 `compat/matrix.json`：floor 不动，新增 caps 检查项 `api36_direct`。

**验证**：A 型设备（API 36 + 全新账号）跑批出现成功响应；matrix.json 补 API 36 档位。

---

## P1-A · 报告弹窗按 tab 门控（用户明示禁止）

**问题**（用户 C 截图）：`showReportCard`（`MainActivity.java:2835`）弹模态 Dialog **不判断当前 tab**——用户主页点"鱼塘任务"后切到日志页等结果，Dialog 直接叠在日志页上（时间线实测：POND 01:19:56 + 执行 118s ≈ 弹窗 01:21:32）。

**改法**（四条报告链同修：鱼塘 :540→:2095、投喂 :583→:2099、游戏币 :2103、兑换 :2107）：
1. `showReportCard` 开头判断当前选中 tab：非主页（tabLog/tabAbout）→ **不弹 Dialog**，改 toast `✅ 报告已生成，回主页查看`；
2. Dialog 单例化：持有一个 `reportDialog` 引用，新报告先 dismiss 旧弹窗，防叠加；
3. 日志页已有报告内容展示，无需重复呈现。

**验证**：主页点鱼塘任务 → 立即切日志页 → 报告生成时刻只出 toast、无 Dialog；切回主页能看到报告卡片。

---

## P1-B · 拒绝潮阶梯退避

**问题**（用户 B）：币余额 + 探测两个探针在 19:22~23:53 拒绝潮里每 2~3 分钟撞一次，70+ 次无效曝光；现有熔断固定 10 分钟（`CoinLogger.java:105`）在整晚潮里等于没退。

**改法**：
1. `CoinLogger` 熔断阶梯化：本轮 3 连拒 → 暂停 10min（现状）→ 累计 3 次熔断 → 暂停 30min → 累计 5 次 → 暂停到下一时段（次日 7 点或 4 小时取短）；
2. 探针时段降频表：`00~01 时、19~23 时`（三份日志互证的高发窗）探针间隔 90s → 30min，可 ConfigManager 配置；
3. 探测（probe）与币余额共用退避状态，避免两个探针交替撞墙。

**验证**：模拟连续拒绝 → 日志出现 `熔断升级 10min→30min→60min`；高发窗内探针间隔日志 30min。

---

## P1-C · 失败归因分流（NETWORK_LOST ≠ GATEWAY_REJECT）

**问题**（三份日志排查全被误导）：`FishTaskAuto.java:1728` 把一切非 JSON 响应统一冒充 `FAIL_GATEWAY_REJECT::网关拒绝非JSON响应`。用户 A 的 -205 连接层断连（`headerFields=null, bytedata=null`，根本没到网关）也被标成"网关拒绝"，排查方向先错一次。

**改法**：
1. 非 JSON 归因按 `responseCode` 分流：`responseCode < 0 && headerFields == null`（-205/-405）→ 新档 `FAIL_NETWORK_LOST::直调连接失败(-205)`；有 200B 响应体 → 保留 `FAIL_GATEWAY_REJECT`；
2. `Diag.java` tag 表加 NETWORK_LOST 档（与 TIMEOUT/无响应 区分）；
3. 报告/日志/导出全链沿用新档。

**验证**：A 场景报告显示 `直调连接失败(-205)`；B 晚间潮显示同一档；真网关 200B 拒绝仍显示 GATEWAY_REJECT。

---

## P2-A · 日志 retCode 独立字段

**问题**：非 JSON 内联日志 `head=80` 截断把 retCode 切成 `ANDROID_SYS_`/`ANDROI`/`AND` 等残串（用户 C 日志出现 12 种变体），统计与排查都要猜。

**改法**：`RespStore`/`RhFileLog` 的 discard 行改为结构化短行：`[bad] rc=-205 api=xxx len=219`（retCode/responseCode/api/长度四字段独立，再附原文截断），raw 档机制不变。

**验证**：discard 行可直接按 rc 聚类，无残串。

---

## P2-B · 状态页显示宿主内引擎版本

**问题**（用户 C 版本混跑）：模块 App 升 1.5.7 后闲鱼进程里还是 1.5.6 引擎跑了一整天（`[wvp]` 00:47 才出现），用户以为升级生效了。

**改法**：状态页（引擎运行中区域）增加一行"宿主内引擎版本"，数据取自 Diag（`Diag.java:100` 已有 host ver 上报）或 REPORT 回读；与模块版本不一致时高亮提示"检测到闲鱼内引擎为旧版 X，请重启闲鱼"。

**验证**：装新模块不重启闲鱼 → 状态页显示版本不一致 + 提示；重启后一致。

---

## 实施顺序与依赖

```text
第一批（纯逻辑，无需真机，当周可发 1.5.8）：
  P1-A 弹窗门控  →  P0-A 标记结果化  →  P1-C 归因分流  →  P1-B 阶梯退避
  （四项互相独立，可并行改，compat_gate 77 条均不触碰）

第二批（有时序依赖）：
  P0-B 跑批探活（依赖 P1-C 的新归因档判别 -205）

第三批（需 API 36 真机）：
  P0-C 请求侧兼容定位  →  P2-A 日志字段  →  P2-B 版本显示
```

发布前照例：`build-apk.sh` 进位 → compat_gate → release_audit → 双仓推送。
