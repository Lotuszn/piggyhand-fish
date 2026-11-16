# 咸鱼小猪手 v1.5.7 更新日志

> **对比版本**：v1.5.6（tag `4f7e3e8`，2026-11-13）→ v1.5.7（tag `d5bfbed`，2026-11-16）
> **versionCode**：50 → 51 ｜ **提交数**：24 ｜ **文件变更**：56 个，+7601 / −5743 行
> **升级方式**：覆盖安装即可，签名未变，LSPosed 勾选状态保留

---

## 一、四类任务修复（本版核心，全部真机实证）

v1.5.6 中以下四类任务"原本可以通过协议完成，但实际不会自动做"，本版全部闭环：

| # | 任务 | v1.5.6 现象 | 根因 | v1.5.7 修复 | 真机验证 |
|---|------|------------|------|------------|---------|
| 1 | 赚更多·倒计时任务 | 完成后不自动领取 | `AWARD`（已完成待领奖）状态被满额判定 `quotaFull()` 误杀——AWARD 时 `progressDTO` 天然 `times=maxTimes`、`reachLimit=true` | 调用侧对 AWARD 状态豁免满额判定（`FishTaskAuto.java`），待领奖任务直接进领取队列 | ✓ 自动领取 |
| 2 | 赚更多·看视频 | 不自动完成、不领取，且越跑越少 | ① 推送事件把 `eventType` 改写为 `complete`，服务端不认；② 旧版毒化期任务进了 `rh_task_lvl.json` 台账（计数=2），`LvlLedger.skip()` 恒真，永久拦截 | ① 保留服务端原始事件描述，`adEvent` 默认开启；② ACCEPTED+showAds 类任务豁免台账，push 推进成功后清旧账；③ push 全败当日内存态拒绝，不再反复无效推送 | ✓ 领取成功 2 个（106538 / 106181） |
| 3 | 赚游戏币·点击签到 | 不自动签到 | `playId` 只跑单实例，实际存在 4 / 110 双实例 | `SIGN_PLAY_IDS = {4L, 110L}` 双实例都跑，支持 `rh_sign_playid.txt` 覆盖（`RhCoinTask.java`） | ✓ +100 币到账 |
| 4 | 赚游戏币·去闲鱼币频道逛一逛 | `channel.revisit.complete` 返回 SUCCESS 但任务永不推进；`task.award` 报 `TASK_NOT_COMPLETED` | `revisit.complete` 只推进 `channel_revisit` 类任务；`channel_click` 类依赖真实页面行为配对（EXP 曝光 + CLK 点击埋点 + 带参跳转），纯协议无法伪造 | 新增 `WebViewProvider.java`：hook 真实 WebView（WVUCWebView）构造器，在 `WebHybridActivity` 上下文注入 `goldlog` EXP/CLK 埋点 + `LZ()` 等价带参跳转，走服务端行为配对；CLK 已发后等复扫收口（约 3 分钟），不落误导性失败 | ✓ 任务翻 AWARD，领奖 +100 币 |

配套修复：**响应通道串台**——响应文件名由 api 尾段改为 api 全名 CRC32 短哈希，消除五组尾段撞车（如 `task.channel.query` 与 `task.center.query` 同名互踩），同 api 加互斥锁串行（`net/RespChannel.java`）。

---

## 二、新增：后台保活（虚拟屏）

- **问题**：HyperOS 墓碑机制（`CachedAppsFreezer`）在闲鱼退后台 ~15 秒后冻结进程，模块引擎随之停摆，定时任务、探针全部失效。
- **方案**：主进程创建 1×1 虚拟屏（`createVirtualDisplay(PRESENTATION|OWN_CONTENT_ONLY)`）+ `Presentation.show()`，把进程状态抬到前台级，冻结器不再生效。零组件依赖、零通知、无感运行（新增 `core/VdKeepAlive.java`）。
- **真机实测**：改前退后台 ~15 秒 `freeze=1`；改后退后台 20~60 秒持续 `freeze=0`，息屏 3 分钟后引擎命令仍通。
- **开关**：模块设置 → 常用功能 → **后台保活**，默认开启；可实时开关（VD_ON/VD_OFF），关闭异常的环境自动降级，不影响正常使用。
- 顺手遮蔽 `getDisplays()` 采集点（闲鱼 dex 内 3 处命中），虚拟屏对风控扫描面归零。

---

## 三、体验调整

| 项目 | v1.5.6 | v1.5.7 |
|------|--------|--------|
| LSPosed 激活检测 | 必须先手动打开一次闲鱼，回来才显示"已激活" | 静态判定：模块启用 + 作用域勾选闲鱼即通过（root 直读 lspd 配置库，兼容新旧 schema），无需启动闲鱼 |
| 任务完成通知栏 | 显示"闲鱼小猪手·钓鱼完成"通知 | 移除（报告落盘、页面查看、toast 汇总不受影响） |
| 主页按钮文案 | 开始钓鱼 | **开始钓鱼·没事儿别点我！** |
| 启动稳定性 | — | 修复重构期引入的 MainActivity 必闪退（布局内 56 处自定义 View 旧全限定名，`aapt2` 编译期不校验类名导致构建零报错、运行必崩） |

---

## 四、风控节奏整形（v1.5.6 是固定间隔/直发，v1.5.7 全链拟人化）

所有 mtop 调用统一收口到 `pace()`（`task/FishTaskAuto.java` callApi 发送前），参数如下：

| 动作类型 | v1.5.6 | v1.5.7 |
|---------|--------|--------|
| 任务触发/提交 | 固定间隔 | ~3000ms ± 40% 抖动 |
| 领取 | 固定间隔 | ~5000ms ± 40% 抖动 |
| 查询类 | 固定间隔 | ~2000ms ± 40% 抖动 |
| 领取动作前 | 无 | 额外拟人延迟 11.5s ± 40% |
| 余额探针 | 固定周期整点跑 | 90s ± 30% 自重排，与跑批互斥错峰 |
| 浏览流步进 | 固定 `BROWSE_GAP_MS` | `sleepJitter` 统一（18 处固定 sleep 收编） |
| 设备系数 | 无 | 首跑固化 [0.7, 1.5] 区间随机，每设备稳定 |

并发语义：探针与跑批互斥，不再高频整点齐发。

---

## 五、架构重构（开发者视角）

v1.5.6 全部 35 个类平铺在根包 `com.piggyhand.fish`，实测依赖 30 边 / **11 对循环依赖**。v1.5.7 完成包级模块化 + 依赖倒置，依赖环 **11 → 0**：

```
com.piggyhand.fish
├── core/    RhRuntime(上下文单一来源) EngineHook(注册接口) Diag Reflect RhFileLog …
├── config/  ConfigManager UpdateChecker RhTokenVault RhResaleList
├── net/     ApiDirect ApiMtop RespChannel(新) RespSink(新) RhReport RhTrafficPair
├── task/    TaskRegistry(新) FishTaskAuto RhCoinTask LvlLedger(新) MapTasks(新)
│            CoinLogger(新) JsonUtil(新) WebViewProvider(新) …
├── hook/    FishMtopHook ReportServer(新) RespStore(新) RhChatHook …
└── ui/      MainActivity LiquidSwitch RhFishFloat …
```

关键改造：

- **注册制**：`FishMtopHook` 不再直接依赖 task 包，经 `EngineHook` 接口由 `TaskRegistry` 分发，hook↔task 依赖环砍除（`hook/FishMtopHook.java`、`task/TaskRegistry.java`）。
- **依赖倒置**：`core.RhRuntime` 下沉 appCtx/目录单一来源（core→hook 环砍除）；`net.RespSink/RespChannel` 接口化响应路由（net↔hook 环砍除）。
- **单体拆分**：`FishMtopHook`（报告/命令 → `ReportServer`，响应落盘 → `RespStore`）、`FishTaskAuto`（台账 → `LvlLedger`、纯函数 → `JsonUtil`、币探针 → `CoinLogger`、地图域 → `MapTasks`），拆分遵循"请求体/报告文案/日志格式逐字未动"。
- **新增 14 文件**：`REFACTOR_DEV1.md`、`core/EngineHook`、`core/RhRuntime`、`core/VdKeepAlive`、`hook/ReportServer`、`hook/RespStore`、`net/RespChannel`、`net/RespSink`、`task/CoinLogger`、`task/JsonUtil`、`task/LvlLedger`、`task/MapTasks`、`task/TaskRegistry`、`task/WebViewProvider`。
- **兼容保障**：`rh_task_lvl.json` 台账格式、响应文件新旧命名双读、命令协议逐字兼容——旧版数据无需清理。

---

## 六、门禁状态

| 检查项 | 结果 |
|--------|------|
| `compat_gate` 兼容基线 | 通过（存量 77 条零变动，无新增违规） |
| `release_audit` 源码扫描 | CLEAN（关键字集 0 命中） |
| `release_audit` APK 产物扫描 | CLEAN（classes.dex / resources.arsc / assets 0 命中） |
| 真机覆盖安装（192.168.123.41） | Success，versionName=1.5.7 / versionCode=51 实读确认 |
| 全量任务真机回归 | 四类任务 + 领取 + 台账 + 保活全通过 |

---

*发布页：https://github.com/Lotuszn/piggyhand-fish/releases/tag/v1.5.7*
