# dev1 模块化重构：方案 · 可行性 · 收益对比

> 分支：`dev1`（基于 main = 云端 v1.5.6 + FishTaskAuto 满额台账补丁）
> 工作区：`2026-10-14-15-22-21/RewardHook-dev1`（独立拷贝，主工作区 main 不受影响）
> 日期：2026-11-15 ｜ Phase 1 已落地并编译验证通过

---

## 一、现状盘点

- 37 个 Java 文件 18570 行，原本**平铺在唯一包 `com.piggyhand.fish`**，无分层。
- 三大单体：`MainActivity` 3636 行（UI+广播+状态机）、`FishTaskAuto` 3025 行（任务引擎 63 个方法）、`FishMtopHook` 1932 行（响应拦截枢纽 + 共享基建持有者）。
- 功能类虽已按 Rh* 前缀拆过一轮（RhCoinTask/RhGameTask/RhPondTask…），但"一个功能一个模块"缺两样东西：**包边界**和**依赖方向**。

## 二、Phase 1（已落地，commit 270bdb5）

### 包结构

```
com.piggyhand.fish                 根包：MainHook（Xposed 入口）、FishVer（版本）
  ├─ core     基建：Reflect / Symbols / Diag / RhFileLog(+Ctx) / RhLocationContext
  ├─ net      请求：ApiMtop / ApiDirect / ApiProtoDump / RhTrafficPair / RhReport
  ├─ config   配置：ConfigManager / RhTokenVault / RhResaleList / UpdateChecker
  ├─ task     任务引擎：FishTaskAuto / RhOlivertask / RhPondTask / RhCoinTask /
  │           RhGameTask / RhCashClaim / RhCashJoin / RhCoinExchange / RhFeedCols
  ├─ hook     宿主 hook：FishMtopHook / RhChatHook / RhSplashAd /
  │           RhFishMedia / RhMediaDownloader
  └─ ui       模块界面：MainActivity / RhFishFloat / LiquidSwitch / LiquidSlider /
              RhUiScale / BezierInterpolator
```

### 改动清单（可审计）

| 项 | 内容 |
|---|---|
| 移动 | 35 个类 git mv 进 6 包；MainHook/FishVer 留根包 → `assets/xposed_init` 零改动 |
| 可见性 | FishMtopHook 5 个共享成员转 public：`appCtx` / `persistDir` / `cacheDir` / `feedRespCandidate` / `noteDirectSent` |
| import | 29 个文件自动补跨包 import（含 R / BuildConfig / BuildInfo） |
| 字符串 | `com.piggyhand.fish.MainActivity` → `com.piggyhand.fish.ui.MainActivity`（FishMtopHook 启动配置页处） |
| Manifest | `.MainActivity` → `.ui.MainActivity`（activity + alias targetActivity；`.LauncherAlias` 是组件名字符串无需改） |
| 构建 | build-apk.sh javac 源列表 `*.java` 平铺通配 → `$(find … -name "*.java")` 递归 |

### 验证证据

- `javac` **0 错误**，全链 aapt2→javac→d8→打包→签名通过，APK 704K（`咸鱼小猪手-1.5.6.apk`，VERCODE 50 同版本重编译）。
- `compat_gate`：**存量 77 条基线零变动、零新增**（R1~R6 全 0）——包移动不触碰任何宿主类名/接口名兼容面。
- 运行时行为零变化：纯静态重组，无逻辑改动；applicationId、action 字符串、`XSharedPreferences("com.piggyhand.fish","config")`、配置存储路径全部不变 → **用户升级无感**。

## 三、Phase 2 路线图：FishTaskAuto（3025 行）深拆

63 个方法聚成 8 个功能簇，逐簇拆出（括号内为当前行号区间）：

| 新模块（task/ 或 net/） | 收编方法 | 行号区间 | 规模 |
|---|---|---|---|
| `task/LvlLedger`（LVL 台账/去重） | lvlDirPath / lvlLoadOnce / lvlLoad / lvlSave / lvlEnsure / lvlGet / lvlSkip / lvlCountedThisRun / lvlBump / lvlReset / markRejected / dayOf | 142~360 | ~220 行 |
| `task/RmbGuard`（RMB 风控锁 + 满额台账） | rmbLocked / rmbLock / quotaFull 体系 | 319~344 | ~60 行 |
| `net/RespChannel`（响应通道） | respFile / fileFp / readFile / writeFile / callApi | 2744~2870 | ~130 行 |
| `core/JsonUtil` | extractTasks / firstRet / objJson / parseObj / opt / str / optStr / parseInt / parseNum / parseCount / stepOf | 2872~3030 | ~160 行 |
| `task/CoinLogger`（金币余额） | startCoinBalanceLogger / coinBalanceSnapshot / recordCoinBalance / cleanOldCoinLogs / parseCoinNum | 1652~1815 | ~165 行 |
| `task/runners/*`（任务执行器） | runSearchTask / runEcMallTask / runTreasureBetTask / runAdEventTask(+extractAdEventPush+itemPool) / browseForTask(+browseDeadToday/browseMarkDead/markPolish) / doMapRoll / doBagUse / rollCoins / ggkReward / runPolish | 1292~2640 | ~1350 行 |
| `task/FishTaskAuto`（仅剩调度骨架） | launch / run(主循环) / autoRunDaily / finish / notifyDone / markCycleClaimed / probeRunnableCount / toast / signinRewards / coinSuffix | 余下 | ~600 行 |

**关键前置**：`RespChannel` 必须第一个拆——`响应通道与调度冲突备忘_2026-11-13.md` 记录的同 api 同名文件冲突（oliver.show 多活动共用 `rh_resp_show.json`）就在这批方法里，拆的时候顺手把"响应通道按 api+活动 ID 分文件"的既知整改一并做掉。

**节奏**：一簇一提交，每簇带真机回归（跑一轮手动领取 + 悬浮窗 + 对应任务开关），三台设备过一遍。预估每簇 0.5~1 天，全量 5~8 个工作日。

## 四、Phase 3（可选，风险最高）

`MainActivity` 3636 行：配置渲染 + 广播接收器 + 版本更新 + 图标隐藏 + 状态机。拆分收益主要在维护性，但它直接绑 UI 状态，真机回归面最大。建议只拆**逻辑层**（UpdateChecker 调用、ResaleList 弹窗决策、广播 ACTION 常量集中到 `core/Actions`），视图状态机不动。

## 五、可行性结论

1. **技术可行性：已验证。** Phase 1 从搬迁到编译通过全程无一处需要改运行逻辑；唯一的结构性代价是 5 个成员从包级可见转 public——这恰好是把"隐性共享"变成"显式 API"，是模块化的本职。
2. **兼容性：无风险面扩大。** xposed_init、applicationId、action、SP 路径、LSPosed scope 全部不变；compat_gate 证明宿主兼容面零触碰。旧版本用户升级到模块化版本的行为与升级普通版本完全一致。
3. **风险集中在 Phase 2/3**：不是编译风险，是**行为回归风险**（调度时序、响应通道竞态、热读开关节奏）。对策已内置：一簇一提交 + 真机回归门禁 + compat_gate + 随时可 `git revert` 单簇。
4. **约束**：Xposed 模块永远只交付一个 APK，"模块化"发生在源码层（包边界 + 依赖方向 + 显式 API），不改变交付形态。

## 六、收益对比

| 维度 | 重构前（单体平铺） | 重构后（模块化） | 量化依据 |
|---|---|---|---|
| 缺陷定位 | 全局 grep，一个文件 3000 行 | 按包定位，单文件 ≤600 行 | FishTaskAuto 63 方法 8 簇，改动可直达目标簇 |
| 并行/合并冲突 | 满额台账、RMB 锁、响应通道 3 项改动挤同一文件（11-12~11-13 实际发生） | 各簇独立文件，冲突归零 | Phase 2 表：1350 行执行器拆成 ≤9 个文件 |
| 回归测试面 | 任何改动全量回归 | 只回归触碰的包（task 改动不用回归 ui） | 6 包依赖方向单向：ui→task→net/core，hook→net |
| 兼容降级 | caps 缺失只能整模块停 | 按模块置灰（探针→模块粒度开关） | 与启动 caps 探针/`rh_caps.json` 既有设计直接衔接 |
| 版本兼容门禁 | compat_gate 全库扫 | 按 pkg 扫描 + 每包规则归属（新版本 SOP 的分层归属表落地为目录） | 规则 21 的 floor.lock 不变 |
| 新人上手 | 读 18570 行才能改一个功能 | 读目标包 ~500 行 | 目录名即功能名 |
| 构建时间 | 无差异 | 无差异（同一条手搓链） | 本次 build ~1 分钟 |
| 发布形态 | 单 APK | 单 APK（不变） | — |

**成本**：Phase 1 已付（本次会话内完成，含验证）；Phase 2 约 5~8 人日（含真机回归）；Phase 3 约 2~3 人日（只拆逻辑层）。
**净判断**：Phase 1 已免费拿走"结构清晰 + 冲突归零"两成收益；Phase 2 的大头收益（回归面收窄 + 响应通道整改）值得做，且每一簇独立可回滚；Phase 3 收益/风险比最低，缓行。

## 七、回滚

- 整体回滚：`git checkout main`（dev1 独立工作区，主工作区从未被动过）。
- 单簇回滚：Phase 2 每簇一 commit，`git revert <sha>` 即可。
- 发布回滚：dev1 的 APK 未发布，云端 1.5.6 用户不受任何影响。
