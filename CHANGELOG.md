# 更新日志

> 对比基准：**v1.1.4（2026-10-21 13:26 发布）**，本文档记录 v1.1.5 及之后的所有变化。

---

## v1.5.6（2026-11-14）· 反倒卖声明弹窗

模块被二次倒卖，加 72 小时强制声明。

- 入口：`MainActivity.onCreate`，距上次展示 ≥72h（首次安装必弹）时弹；时间戳写独立 pref
  `rh_notice/anti_resale_at`，不混进 `config_json`（那是注入进程读的模块配置，不该带 UI 侧状态）。
- 形态：白卡 24dp 圆角 + 橙色标签点，宽 88% 屏宽，`setCancelable(false)` —— 点外部、返回键都关不掉，
  必须走两个按钮之一。
- 文案：「公益免费」「举报投诉+退款」两段加粗标红（#FF3B30），逐字插 U+2060 防被折行切成两截。
- 动作：「去「关于」」切到关于页并轻弹「加入我们」那行；「我知道了」关窗后再跑更新检查，两弹窗不叠。
- 真机验证（1 号 1440×3200/560dpi）：首次弹 → 点掉后 72h 内不弹 → 时间戳回拨到 73h 前重新弹，三条全过。

**名单区块（同日追加）**

- 弹窗内新增「已确认倒卖者 N 人」区块：序号灰 + 名字加粗标红（#FF3B30），超长省略号截断。
- 高度自适应：视窗 = min(条数, 3) 行 × 27dp。1 条就 1 行高、2 条 2 行高、0 条整块不占位；弹窗整体随之伸缩。
- >3 条自动轮播：每 1.8s 上滚一行，到底回头循环，右侧标「自动滚动」；手指按住暂停、松手 3s 续播；弹窗 dismiss 时 `removeCallbacksAndMessages` 停表。
- 数据源：常量 `RESALE_TEST_NAMES`（占位符 A/B/C/D 四条）只用于验证滚动；正式版已替换为 `RhResaleList.read(this)`，渲染逻辑未动。
- 真机验证：4 条 → 首行在 A/B 间交替（12 帧扑捉两种状态交替）；2 条 → 高度收缩为两行、无「自动滚动」、不滚动。

**名单远端热加载（`RhResaleList`）**

- 数据源 `https://gitee.com/lotuszn/piggyhand-fish/raw/main/resale/list.txt`，包里不带任何 token ——
  客户端一旦存凭据，等于把仓库写权限发给所有装机用户。
- 三条硬约束：① 本地缓存 `rh_resale.txt` 优先，网络失败**绝不清空缓存**，只保留上一份能用的；
  ② 响应必须以 `# rh-resale` 开头，否则整份丢弃（raw 跟随 302 后若文件不存在返回 HTML 错误页，
  不校验就会把 HTML 当名字渲染）；③ `MAX_BYTES 65536` / `MAX_ITEMS 500` / `MAX_LINE 80` 封顶，
  防有人往公开文件塞超长内容撑爆 UI。
- 节流 `MIN_GAP_MS = 21600000`（6h，pref 键 `rh_notice/resale_at`），别把 Gitee 匿名 raw 打限流。
- 渲染接 `renderResale(names, loading)`：首次先出缓存，`refreshAsync` 回来后原地重渲染；
  names 为空时看 loading —— true 显示「同步中」，false 整块隐藏不占位。

---

## v1.5.5（2026-11-12）

上一发行版 v1.5.4。本版全部来自批次 2 用户日志（3 份：7.28.10 ×1、7.29.10 ×2）的定性结论：
**网关概率性秒拒**是跨设备/跨版本的最大失败源，本版围绕它收敛。

**一、网关拒绝短路（改进 C）**

hook 收到非 JSON 拒绝页（219B/224B，60ms 秒回）丢弃时，同步落固定名旁档 `rh_resp_<api>.json.bad`；
`callApi` 轮询见 bad（mtime 晚于本次发送时刻）立即返回构造归因
`FAIL_GATEWAY_REJECT::网关拒绝非JSON响应: <原文头60字符>`，不再空等 30s。
正常响应到达即清 bad，防旧档误配。批次 2 实测收益：(2) 号 30 次 timeout × 30s ≈ **15 分钟纯等待归零**。

**二、探针降频 + 熔断（改进 B）**

币余额 logger 周期 30s → **90s**（map.init 是三台日志被拒最多的接口，高频直调是主要诱因）；
连续 3 次网关拒绝自动停探 10 分钟（`gwBlockedUntil`），期间日志静默跳过不再占用请求窗口。

**三、map.init 60s 结果复用（改进 D）**

静态缓存 SUCCESS 响应 60s（实例字段不行——logger 每 90s new 一个新实例）。与 B 叠加后
真实请求压到 ~90s 一次。真机验证：探针连续命中 `cache 60s`，不再发真实请求。

**四、冷启动就绪门（P0-3）**

报告服务 bind 成功置 `ENGINE_LISTENING`（≈hook 链尾部完成）；冷启动 auto-run 首探由固定 45s
改为「**就绪即走、45s 兜底**」。灭屏 Doze 场景宿主初始化挂起时，探针线程安静等待，
不再像 (2) 号那样白打 10 轮 probe。

**五、拒绝原文取证（改进 A）**

discard 日志行内联原文前 80 字符（`head=...`，换行折叠）；导出日志末尾新增 raw 段——
`rh_resp_*.json.raw/.bad` 各截 400B。219B 拒绝页从此可定性（限流页还是会话失效页）。

**六、领取归因细分（P1-2）**

「未生效（needBenefit 仍 true）」一句文案拆四类：人民币风控 / 当日次数耗尽 /
**任务已完成，奖励待云控发放（status=COMPLETE）** / 需真实行为或服务端延迟，
并附 `status=` 字段。真机立测：7 条未生效全部细分为 COMPLETE——批次 2 三台的 12/22/7 次
「未生效」原来多是服务端放奖延迟，不是请求缺陷，下轮自动复核即可。

**七、浏览任务当日封禁（P1-3）**

浏览上报 ILLEGAL 中止阈值 3 → **2 次**，中止即置当日标记（按日期键，进程重启自动失效），
当日后续轮次不再进浏览（每轮省 ~45s 退避）。三台日志实证该任务（deliveryId=3141）带 token 仍必拒。

**验证**

- 构建：1.5.5(49)，签名 SHA-1 不变；兼容闸存量 77 条未变、0 新增违规；发行审计源码 0 命中。
- 真机（7.29.10 + 本版）：CAPS/REPORT/RUN 全通；map.init 60s 缓存命中 ×2；
  7 条领取未生效全部输出新归因文案；预置 .bad 被发送前清理逻辑正确消化（防旧档误配语义符合设计）。
- 未实施（用户裁定丢弃）：端口让位自适应（P0-4）、ctor-hook 可见性（P1-1）、兼容闸规则扩充（P2）。

---

## v1.5.4（2026-11-12）

上一发行版 v1.5.3。本版不加业务功能，把「版本适配」从人肉试错改成机制。起因是三件事：
用户闲鱼版本各不相同、7.29.10 上又一次撞到 `moduleForProtocol -> null`、三份用户日志里的失败
全部只写「查询失败」无法定性。

**一、符号解析器（Symbols.java）**

`ApiDirect` 不再单点 `findClass("com.taobao.idlefish.xmc.XModuleCenter")`，改为候选表 + 形状校验：
四个候选依次试，拿到对象必须满足 `send(2 参)` 才算命中，否则继续下一个。判据是形状不是名字，
所以混淆重排但语义没变时仍能命中。命中后每个进程只打一行：

```
[sym] protocol_center -> 1/4 com.taobao.idlefish.xmc.XModuleCenter.moduleForProtocol
```

离线验证：7.28.86.1（11 dex）与 7.29.10（12 dex）两个包中，`XModuleCenter` / `moduleForProtocol` /
`PApiContext` / `ApiProtocol` / `ApiCallBack` / `MtopLauncher` 全部存在，一套候选表覆盖两端。
同时发现 `mtop.*` 接口名在两个版本的 dex 字符串池里都是 MISS——客户端不写死 api 名，
所以接口名只能运行时学，无法静态验证。

**二、失败归因（Diag.java）**

六个模块（coin / game / pond / cash claim / cash join / oliver）的 `briefRet` 统一套 `Diag.tag`，
报告行从「余额查询失败 无响应」变成「余额查询失败 [TIMEOUT] 无响应」。归因码五类：

| 码 | 含义 | 处置 |
|---|---|---|
| `RISK` | 服务端风控（RMB_CHECK / 服务器繁忙） | 降频熔断，改参数无用 |
| `PARAM` | 参数 schema 不匹配 | 换参数模板 |
| `NOT_FOUNDED` | 接口名/版本不被识别 | 换接口名族 |
| `NO_PATH` | 通路断了（符号漂移/进程不对/未注册） | 查符号与进程 |
| `TIMEOUT` | 无响应 | 查回调捕获与网络 |

配套两处修正：
- 渠道清单拉取失败不再写死 `TASK_PLATFORM_ERROR`（它把「进程不对」也冒充成平台错误，
  是用户日志无法定性的直接原因），改为输出真实归因。
- `callAndWaitOnce` 发送失败时返回伪响应体 `{"ret":["NO_PATH: ..."]}`，而不是 null，
  上层报告能直接显示通路原因，不再一律显示「无响应」。

**三、能力矩阵（CAPS 命令）**

report-server 新增 `CAPS`，输出 `rh_caps.json`：宿主版本、进程名、模块版本、五个符号的可达性、
以及结论 `engine_ok`。只做静态符号探测，不发任何网络请求（探测请求会进风控计数）。

同一台机器两个进程的实测对照，一眼看出该往哪个端口发命令：

```
主进程 27715   protocol_center ok#0        engine_ok=true   reason=ok
渲染进程 27717 protocol_center MISS all 4  engine_ok=false  reason=NO_PATH: not main process
```

**四、兼容门禁（_dev/compat_gate.py）**

新增 `COMPAT_RULES.md`（7 节 20 条）与门禁脚本，扫新增的宿主类名/接口名/手拼字段/全等文案/
坐标点按/版本分支。存量 77 条冻结为基线，只拦新增。`exit != 0` 禁止 push 与发布。

**五、日志导出改造（同版本追加）**

导出日志是用户机器出问题时唯一能拿到的现场，旧实现有三处硬伤：取闲鱼版本走
`su -c "dumpsys package ... | grep versionName"`（su 未授权/超时一律回落 `?`）、
只导当天一份、失败判定靠 grep 输出里有没有 `No such`（会被日志正文误伤）。

改动：

- 新增 `FishVer.java`：走 `PackageManager` 查 `Symbols.HOST_PKG`（模块 manifest 已声明
  `<queries>`），返回 `versionName(versionCode)`，60s 缓存。不依赖 root，闲鱼进程内查自己零成本。
- 导出文件名带版本：`xiaozhushou2026.11.12_7.29.10_530.log`，附件列表里直接可辨版本
  （不用括号：adb / 终端 / 部分文件管理器会把括号当 shell 元字符）。
- 头部逐字段成行，带 versionCode：`# 小猪手=1.5.4(48)` / `# 闲鱼=7.29.10(529)` /
  `# 安卓=14(34) 设备=Xiaomi MI 9` / `# 导出端包名=...`。
- 正文合并今天/昨天/前天三份（`RhFileLog` 本来就保留 3 天），一个文件覆盖 3 天现场。
- 末尾附 `rh_caps.json`（符号命中/进程归属/`engine_ok`），省掉再来一轮问版本。
- 成功判定改为脚本自报 `RHSTAT n=<天数> size=<字节>`，不再猜文本；写完发
  `MEDIA_SCANNER_SCAN_FILE`，MTP 立即可见。
- 无日志也出文件（只带环境头）；su 全挂则退回 App 私有外部目录写同一份头，Toast 直接给路径。
- `RhFileLog` 当天首行同步加 `fish=版本(版本号)`，三字段各管一件事：
  `xiaozhushou`=模块版本、`fish`=闲鱼版本、`pkgver`=当前进程宿主包版本。
- 报告页顶部加「闲鱼 版本(版本号)」，读不到报告时也显示，便于用户口头报版本。

**验证**

7.29.10 + 1.5.4：CAPS 主进程 `engine_ok=true`；COIN 全绿（余额 16250=1.62 元、签到已签 +100、
92 游戏 / 62 任务 / 可做 0、渠道 27 项）；归因映射离线跑过 7 组用例全部符合预期。

**五、触发请求两道闸（P0-1，来自 7 份用户日志定性）**

`uploadolivertasktrigger` 占了任务失败的大头（1895 条触发里 168 失败，其中 PARAM_ILLEGAL 113）。
逐条对齐请求体与响应后确认两类失败形状：

- **缺字段硬发**：列表没下发 `fromToken` / `groupId` 时旧代码照样发，云端固定回
  `PARAM_ILLEGAL`。现在 `triggerBody()` 统一组装，三个必填字段缺一个直接不发，
  报告写「跳过（列表未下发 fromToken/groupId）」。浏览上报同样带上列表同一行的
  token/groupId，不再只发 `sceneId+implId+deliveryId`。
- **字段齐但云端不认**：字段齐全仍回 `PARAM_ILLEGAL` / `FROM_APP_REJECT` 的
  外部 App 任务（去淘宝/支付宝/一淘……），发多少拒多少。加「同类连败 2 次停发」的
  毒化闸，按 `materialDTO.actionType` 聚合——按标题聚合永远只 1 次，闸门等于没装。

真机复测（7.29.10 + 本版，测试机）：可触发 11 项 → 实发 6 条（2 条 `FROM_APP_REJECT`、
4 条 `PARAM_ILLEGAL`），其余 5 条被闸停发并注明类型（`VISIT` ×3、`PLAINVISIT` ×2）。
失败红字从 10 降到 6，注定失败的请求少发 45%。

**六、游戏清单形状兜底（P0-2）**

用户日志 3 份出现「清单结构异常: No value for games」——`ret SUCCESS` 但 `data` 下没有
`games` 键，旧实现 `getJSONArray` 直接抛，整轮游戏任务判失败连领奖都不试。改为
`gamesArray()`：先按名取 `games`，取不到按形状找「元素带 task 子对象」的同构数组，
命中的键名写进报告供兼容表固化；真没有则明确记「清单为空」而不是异常。

**验证（本节新增部分）**

- 兼容闸：存量 77 条未变动，无新增违规（`implId` 透传走 `objJson`，不产生新的手拼字段）。
- 发行审计：源码关键字集 0 命中。
- 构建确定性：同源重编译 md5 一致（3ed8b10170ef5c7d92d3b14d50503366）。
- 真机：见「五」的复测数据。

---

## v1.5.3（2026-11-12 发行）

上一发行版 v1.5.2。本版把「做任务赚游戏币」从「能跑」推到「清零」，三项历史遗留全部收口。

**一、渠道游戏任务全程协议打通（本版核心）**

之前 27 项渠道任务里 22 项长期卡在 `ACCEPTED`，点「去完成」跳游戏、回来还是没进展。根因是
`mtop.taobao.idle.mini.game.task.complete` 少了 `taskScene`，服务端按默认场景找不到 taskId，
直接回 `TASK_NOT_FOUND::任务不存在或已失效`。正确链路是三步，且 `taskScene` / `appkey` 三层必须一致：

```
detail.query{taskId, appkey, taskScene}      → 登记本会话的 taskId（不只是只读探针）
complete{taskId, appkey, taskScene, progressValue|action+payload, reportId=UUID} → AWARD
award{taskId, appkey, taskScene}             → awarded=true + rewardAmount
```

现场实测 20 项一次性推完，余额 10050 → 16050。

**二、激励视频不再「跳过」**

`commercial` / CSJ `rewardVideo`（「看视频赚游戏币」）以前判「必须真人看」，本版打通：
任务 `jumpUrl` 自带 `adEventPush` 参数，那是服务端下发的观看完成上报描述，原样推回
`play.ad.event.push` 即认定看完——

```
adEventPush={"adPositionCode":"xyGameTask_bidding","eventType":"firstEvent","countdown":15}
→ push SUCCESS → 任务 ACCEPTED→AWARD（5s 内）→ award +100
```

同一 `adPositionCode` 当日额度一发即满，重复推返回 `FAIL_BIZ_reach_upper_limit_error::任务已达上限`，
按「已达成」处理，不再算失败。渠道任务至此 27/27 全清。

**三、「从桌面进入游戏频道」不再是假成功**

这条 `channel_revisit` 任务报 `revisit.complete{sourceId:TASK_RECOMMEND}` 会返回 `result:true`
但状态一动不动。改为按任务的 `taskAction` 选 `sourceId`——`desktop_shortcut` 型必须报
`desktop_shortcut`，换掉后立刻转 `AWARD`，再 award 得 +100。

**四、每日签到类补全**

- 神奇鱼塘「赚饲料中」的「领饲料」按钮 = `play.sign.in{playId=101}`（不是 forest.* 接口）。
  POND 报告首行直接给结论：`[0] 领饲料 playId=101 → 今日已领（连签 3 天）`。
- 游戏中心「点击签到」= `mini.game.sign.in{playId=4}` + `play.sign.in.home{playId=4}` 双打，
  阶梯档位与签到记录都落。

**五、抗平台限流**

渠道清单会随机回 `TASK_PLATFORM_ERROR::任务服务繁忙`（同一秒 PC 侧直调成功、引擎内失败，
属平台并发判重而非参数错）。`channelTasks()` 改 3 轮重试、间隔 2s，第 3 轮退化成空 body。

**本版收口基线（2026-11-12 实测）**

| 模块 | 状态 |
|---|---|
| 渠道游戏任务 | 27/27 COMPLETE，`claimableCount=0` |
| 中央游戏任务 | 62/62 COMPLETE |
| 神奇鱼塘 | 23/23 COMPLETE，AWARD=0 |
| 496 会员任务 | AWARD=0（余 13 项为支付宝/淘宝/一淘等三方外跳，非可领取） |
| 游戏币余额 | 10050 → 16250（+6200） |

覆盖安装即可，签名未变，LSPosed 勾选状态保留。

---

## v1.5.2 修订（同版本号，2026-11-11 夜间现场修复）

**2026-11-11**

- 「赚更多」两项未领奖（根因）：`RhGameTask` 只把 `taskStatus=ACCEPTED` 收进可做队列，
  已转 `AWARD`（可领奖）的任务在清单阶段就被丢掉，导致列表显示「无可做」、领奖永远不发。
  现在 AWARD 单独成表，用 `center.query` 自带的 `appkey + taskId` 直发
  `mini.game.task.award`，复核计数同步把 AWARD 算作待做。现场实测 3 项 +300 闲鱼币。
- 神奇鱼塘「看视频赚饲料」卡在 1/3（根因）：多轮任务的正确节奏是「推一轮 → 立刻领一轮」，
  推到 AWARD 后不领奖，状态卡死、下一发 `cube.task.trigger` 服务端直接拒绝。
  `RhPondTask` 触发循环改为每轮 trigger 后用响应里的 `awardInfoDTOMap` 挑未领档位立即领奖，
  循环结束时再把剩余 AWARD 统一收一遍。现场两个视频任务 1/3 → 3/3（+150g）。
- 鱼塘「投喂」起不来（根因）：`feedId` 只认 `rh_resp_feed.json` / 持久档，用户从未在页面
  投喂过就永远拿不到记录。新增三个来源：`forest.animal.list` 的 `feedInfo.feedId`（最权威）、
  全量 `rh_resp_*.json` + `rh_apiproto.jsonl` 扫描、持久档。现场 630g → 0g 全部投完。
- 投喂止损过严：服务端「服务开小差」是瞬时限流（连发 24 发后触发，冷却 15~20s 可继续），
  旧逻辑连失败 2 发就收工，余额永久留账。改为 15s 退避重试、连续 8 次失败才停。
- 「赚游戏币」页自动跳转（根因）：`RhCoinTask` 对 `jumpUrl` 含 `showAds` 的任务直接
  `startActivity`，`fleamarket://showAds` 在当前闲鱼版本落到「当前闲鱼app已经是最新版」兜底页。
  现在 `openTaskPage` 空实现（只记日志），逛一逛类改走纯协议
  `play.ad.query → 等浏览时长 → event.query → event.push`；激励视频明确跳过（需真人观看）。
- 新增游戏中心连续签到：[0.5] 段从旁档 `rh_apiproto.jsonl` 捞 App 自己发过的 `playId`
  （`play.sign.in*` 必需且无法硬编码），落 `rh_playid.txt` 后自动 `play.sign.in.home → play.sign.in`。
- `ConfigManager.load()` 不再可能返回 null：内置 JSON 解析失败时返回兜底对象，
  避免各引擎 `cfg != null && cfg.xxx` 写法把整批功能静默关掉（现场 pond/pondFeed 因此一个多月没跑）。
- `pondFeed` 默认改开（饲料留账会过期，用户口径：默认投喂到剩余 0）。

---

## v1.5.2 修订三（同版本号，2026-11-12 凌晨：激励视频协议打通）

**2026-11-12 02:5x / 设备 MI9 (192.168.123.37)**

- 「做任务赚游戏币」最后一项「看视频赚游戏币」(`commercial` / CSJ `rewardVideo`, taskId `1047271694`)
  协议打通，判定「必须真人播放」是误判。
  关键在任务 `jumpUrl` 自带的 `adEventPush` 参数——那是服务端下发给客户端的「观看完成上报描述」：
  ```
  adEventPush={"adPositionCode":"xyGameTask_bidding","eventType":"firstEvent","countdown":15}
  ```
  把这个 JSON 原样展开推回 `mtop.taobao.idle.play.ad.event.push` 即可：
  | 请求 | 结果 |
  |---|---|
  | `play.ad.event.push{adPositionCode,eventType:firstEvent,countdown:15}` | **SUCCESS** |
  | 再推一次（同 adPositionCode） | `FAIL_BIZ_reach_upper_limit_error::任务已达上限` |
  | 复扫 `channel.query` | 任务 `ACCEPTED → AWARD`，`claimableCount 0 → 1` |
  | `task.award{taskId,taskScene=GAME_CHANNEL}` | `awarded=true / rewardAmount=100` |
  服务端只认这次 push，不校验 CSJ 播放会话，15s 倒计时是展示层的事。
  现场 27/27 全 COMPLETE、`claimableCount=0`，余额 16150 → 16250。
- `RhCoinTask.runRewardVideoAd()` 落地：解析 `adEventPush` → push → 2s 后复扫状态 → AWARD 则领奖。
  `reach_upper_limit` 按「已达成」处理（不是失败），否则每日复扫会天天报红。
  原来 `adType=rewardVideo` 直接 `continue` 跳过的分支删掉。
- 顺带确认这条路对「赚更多」里的 CSJ 视频任务（`106538`/`106181` 那类）不通用：
  那些走 oliver 体系（`deliveryId + sceneId`），`getolivertaskbenefit` 返回 SUCCESS 但状态不动，
  不带 adPositionCode，无法套用本链路；服务端已下架，列表里不再出现。

---

**2026-11-11 夜间 / 设备 MI9 (192.168.123.37)**

- 「做任务赚游戏币」渠道游戏任务全程协议打通（本次最大修复）。
  之前 27 项渠道任务里 22 项卡在 `ACCEPTED` 永远推不动，根因是 **`complete` 少了 `taskScene`**：
  - 只发 `mini.game.task.complete{taskId,progressValue,reportId}` → `TASK_NOT_FOUND`
  - 先发 `detail.query{taskId,appkey}`（不带场景）→ 仍 `TASK_NOT_FOUND`
  - `detail.query{taskId,appkey,taskScene=GAME_CHANNEL}` → `SUCCESS / taskStatus=ACCEPTED`
  - 随后同参数 `complete` → `completed=true / taskStatus=AWARD`
  - 再同参数 `award` → `awarded=true / rewardAmount=300`
  即 `detail.query` 不只是只读探针，它是「本会话登记这个 taskId」的开关；`taskScene` 必须三层全程一致。
  现 `RhCoinTask` 渠道分支固定按 `detail.query → complete → award` 三步走，参数与任务自身
  `taskScene/appkey` 对齐（缺省 GAME_CHANNEL）。现场 22 项一次性推完 20 项，余额 10050 → 16050。
- 渠道清单 `TASK_PLATFORM_ERROR::任务服务繁忙` 不再直接判死：`channelTasks()` 最多 3 轮、
  间隔 2s，第 3 轮退化成空 body（实测空 body 命中率反而高）。实测同一秒 PC 侧直调成功、
  引擎内失败，属平台并发判重而非参数错，所以重试而不改参数。
- 「从桌面进入游戏频道」这条 `channel_revisit` 任务：`revisit.complete` 原来固定报
  `sourceId=TASK_RECOMMEND`，服务端返回 `result:true` 但任务停在 `ACCEPTED`（假成功）。
  改为按任务的 `taskAction` 选 `sourceId`——`desktop_shortcut` 型必须报 `desktop_shortcut`，
  换掉后立刻转 `AWARD`，再 `award` 得 +100。报告里会打印实际用的 sourceId，便于对表。
  同时 `revisit.complete` 只对 `channel_revisit` 型发，别类不再误打（会污染 lastVisit）。
- `RhPondTask.dailyFeedSign()` 上线：鱼塘「赚饲料中」的每日「领饲料」按钮＝
  `play.sign.in{playId=101}`（不是 forest.* 接口，也不是鱼塘签到 playId）。
  POND 报告首行 `[0] 领饲料 playId=101 → ...` 直接给结论。现场「今日已领（连签 3 天）」。
- 游戏中心「点击签到」：`mini.game.sign.in{playId=4}` + `play.sign.in.home{playId=4}` 双打，
  阶梯档位与签到记录都落。现场「连签 4 天 / 今日第 4 档 → 今日已签 +100」。
- 复核基线（本轮收口后）：渠道 26/27 `COMPLETE`、`claimableCount=0`；中央任务 62/62 `COMPLETE`；
  496 会员任务 `AWARD=0`（ACCEPTED 的 13 项是支付宝/淘宝/一淘等三方外跳任务，非可领取状态）；
  鱼塘 23/23 `COMPLETE`、`AWARD=0`。仅剩两类需真人的任务：`commercial/rewardVideo`
  （看 15s 激励视频，CSJ SDK 回传播放完成，协议无解）与三方外跳任务。

---

**2026-11-11**

- 修复「游戏任务不生效」：打开闲鱼自动跑游戏任务时任务清单拉取失败（直调响应丢失，等待方白等超时）。三重修复：
  - 直调失败自动重发一轮（间隔 1.5s，偶发挂起自愈）；
  - 网关拒绝响应（非 JSON）原文落旁档 `rh_resp_<尾段>.json.raw` 并带回报告，不再只显示「无响应」；
  - `ApiCallBack` 类级响应旁路 hook（构造器 + 全部方法喂统一捕获通道），直调与页面响应双通道。
- 修复圈数挑战请求体坏 JSON：城市码/定位拼接对 `jsonPair()` 尾逗号语义误用，`interact.cycle.challenge.info` 等三处直接拼 `}` 产生非法请求体，改用 `jsonPairObj()`。
- 修复部分构建环境编译失败：UC WebView 类改为运行时反射加载（编译 classpath 无 UC 依赖）。
- 新增「关于 → 通用 → 清除今日任务标记」按钮：确认后经 socket 清空当日 0/1/2 标识（LVL 内存计数 + `rh_task_lvl.json`）与 13 个 done/try/date 标记文件（覆盖 4 个候选目录），随后 root 重启闲鱼（`am force-stop` + monkey 拉起）重跑当日任务；引擎运行中点击会被拒绝（防计数写回），闲鱼未运行时提示失败不执行重启。

---

## v1.3.1 → v1.5.1 · 游戏币全套自动化 + 鱼塘全协议核销

**2026-11-11**

（上一发行版以来的连续构建迭代，按功能块归并记录。）

### 神奇鱼塘：全自动核销

- 新一级菜单抽屉，「自动赚饲料」引擎：拉任务列表（104042）→ 按任务自身档位逐轮领奖（多轮任务不写死档位）→ 能量/签到气泡逐个领取 → 复核出报告。
- 补触发步骤（原引擎只收割、从不推进待完成任务，是「开关已开但不自动完成」的根因）：`cube.task.trigger` v1.0（活动 ID + 任务实例 ID + 毫秒幂等键）对全部待完成任务协议直达——含 CSJ 广告位、跨端引流（美团/百度系/快手/菜鸟/点淘/支付宝/点点）、H5 小游戏、桌面访问，服务端对 104042 清单不做真实行为校验，实测 16/16 一发转可领、23/23 当日全核销（+330g 饲料）。
- 多轮任务按剩余轮次连发（单任务上限 5 发防呆），触发失败自动降级广告位事件推送兜底；触发后重拉任务列表再统一领奖。
- 「自动投喂」引擎：协议复用投喂活动记录，同请求重发即连投，固定语义投到饲料余额耗尽为止（防频控间隔、200 发硬上限、连续失败止损）；feedId 解析链 = 响应缓存 → 持久档，都取不到时报告引导先在鱼塘页手动投喂一次，绝不硬编码过期活动 ID。
- 每天打开闲鱼 40s 后自动执行（当日标记 + 次数计数门），报告 `rh_tasks_pond.txt` / `rh_tasks_pondfeed.txt` 可随时查看。

### 背包道具自动使用 + 至尊大转盘

- 道具使用接口链（列表 + 逐张使用），背包自动跑批新增至尊大转盘：每张独立 ID 逐张抽取闲鱼币等奖励，收益解析与刮刮卡共用；背包抽屉新增大转盘行（开关 + 数量），格式与既有道具对齐。

### 赚闲鱼币提现 → 赚游戏币任务

- 新引擎 `RhCoinTask`：余额查询（`point.withdraw.query`）→ 中央任务（复用游戏任务协议链）→ 渠道任务（27 项：协议可推的直接 complete、浏览类自动拉起任务页真实浏览 ≥20s 由 SDK 自报、可领的逐个收割）→ 复核落标记 → 拉起后 100s 自动复扫收尾。
- 每日自动跑（延迟 240s 避开引擎波次），手动触发不受每日标记限制；停止条件按复核待收数判定 0/1/2 档，跨天自动归零。
- UI：关于页抽屉新增卡片与开关；socket 通道补 `COIN` / `COIN_REPORT`。

### 自动兑换

- 三硬条件自动化：游戏积分余额 ≥100000（进度由接口直读）、设备时间 ≥10:00（商品十点上新）、每日仅 1 次（落盘防重试风暴，查询失败同样消耗名额）。
- 兑换链全程接口：商品列表 `shoppingmall` v2.0 动态匹配「支付宝」对应档位取 benefitId → `exchangebenefit` v2.0 下单；匹配失败回退实测兜底 ID 由服务端校验。
- 挂点：赚游戏币任务当日判定完成后延迟 60s 自动执行（等 mtop 通道就绪）；亦可在 UI 手动触发，手动不消耗每日名额。

### EVAL 真实 UI 通道

- H5 页面 title 桥 + UC WebView 全实例收集：任务页 JS 定点点击「去完成」→ 真实滑动浏览 → 广告 SDK 在真实会话内自报奖励 → 引擎自动收割。纯协议伪造过不了的「真实行为校验」类任务由此打通。

### 协议捕获设施

- mtop 请求/响应全量落盘（16MB 轮转 + 引用级去重），构建期版本号注入，任意进程可读真实版本——协议分析与问题定位的基础设施。

### 修复

- **ANR（兑换商店页连环无响应）**：根因是 WebView 构造器 hook 在主线程同步调 UC 调试开关，U4 内核锁阻塞数秒（/data/anr 五次 trace 实证）；已移除三处主线程调用，功能无损。
- **领奖假阳性**：接口返回 SUCCESS 但权益未生效（needBenefit 仍 true / 奖励为空）被计入成功并占用每日次数——改为 SUCCESS 后复查，未生效不计成功、不占次数、下轮重试。
- **7 天圈数挑战漏领**：可达档位状态为 REACHED 时代码只认 AWARD 导致漏领，已补齐。
- **每日次数门缺省值**：计数缺省 -1 导致首次写入 0、每日实际放行 3 次，改缺省 0 恢复设计上限。

### 测试账号数据清零 + 发行审计门禁

- 移除最后两处写死测试环境数据（城市区号、定位坐标），动态捕获失败即省略字段（服务端对缺字段请求照常服务），全包不再携带任何账号侧写死值。
- 发行前审计固化为关键字驱动规则（凭据/指纹/私有 ID 三级 BLOCK + 调试设施/日志落盘/资源残留/权限组件 WARN）与审计脚本：源码 + APK 产物（dex/arsc/assets 字符串反查）双扫，当前基线 BLOCK=0。

---

## v1.3.1 · 多用户隔离 + 关于页加群直达

**2026-11-10**

版本号说明：v1.2.10 / v1.3.0 曾构建未发行（v1.2.10 为停更段；规则改为每段最高 10 后向前进位），本版直接 v1.2.9 → v1.3.1，versionCode 34。

### 多用户数据隔离（测试账号数据清零）

按多用户审计要求，所有写死的账号侧数据清零、全部改动态：

- 三处 asac 写死移除（`RhCashClaim` 数据 JSON 与两条 header、`RhCashJoin`、`RhGameTask`），改 `RhLocationContext` 动态捕获：宿主原生流量里的 fishAdCode / gps / asac 落盘 `rh_loc.json`，取不到的字段直接省略不拼进请求（与 v1.1.2 的 gps 策略对齐）；
- `ConfigManager` 按 `myUserId()` 分账号：存储根目录（分身 999 → `/storage/emulated/999`）与报告端口（28714）随宿主多开环境自适应；
- 审计固化：`_dev/audit_hardcode.py` 扫 6 类硬编码模式，作为双仓同步/发版门禁（`AUDIT_RULES.md`；违规须通知负责人亲自审计，不得自动整改）。

### 关于页「加入我们🐧」

- `activity_main.xml` 常用功能组新增 `rowQQGroup`；`MainActivity` 点击后按 mqqapi 协议直拉 QQ 加群卡片，三级回退（mqqapi intent → QQ 主页 → 浏览器）；
- `AndroidManifest.xml` `<queries>` 补 `com.tencent.mobileqq`：Android 11+ 包可见性缺失会导致 intent 解析失败直接落浏览器回退；真机验证已直达群卡片。

### 发布链路收紧

- `gitee_sync.py` 发行版附件名前缀 闲鱼小猪手 → 咸鱼小猪手；
- `api_push.py` / `gitee_sync.py` 推送白名单补挡 `_dev` / `_bak` / `_recover` 与根级 `_*.txt` 调试残留——`_dev` 内含 mtop 响应体与调试脚本（userToken 级数据），此前属敏感泄漏点；Git Data API 全量重建树，本次推送起远端同步清除。

---

## v1.2.9 · 响应通道回归修复

**2026-11-09**

v1.2.8 为了让报告跟着存档一起进持久区，把 `launch()` 传给引擎实例的目录从 `cacheDirPath()` 改成了 `lvlDirPath()`。但 `callApi()` 用同一个字段拼 `rh_resp_<api尾段>.json` 的路径，宿主始终写 `getExternalCacheDir()`，于是引擎每个请求都在 `files/` 下读空文件，**等满 30s 超时**。lvl 段正常，只看 lvl 日志会误判通过。

```
09:47:44.569 [tasks] 币余额 -> SUCCESS        ← 探针实例（cacheDirPath），读到
09:48:11.350 [tasks] timeout: 地图状态         ← 引擎实例（lvlDirPath），读空
09:48:41.700 [tasks] timeout: 背包列表
09:49:12.057 [tasks] timeout: 擦亮
```

修复：

- 拆字段。`cacheDir` 只负责 `rh_resp_*.json` / `rh_pref.json`（跟随宿主写盘目录）；新增 `reportDir` 只负责 `rh_tasks_result.txt`（持久区 files/）。
- 修指纹撞车。`fileFp()` 优先读宿主的 `.stamp` 毫秒戳，回落 `length:mtime`。连续两次同接口且响应等长、落盘同秒时，旧算法会判定「没新响应」照样超时。
- 报告读取端同步：`FishMtopHook.readReportFile()` 先 files/ 再 cache/，`MainActivity.tryReadReport()` 五通道（files 直读 → cache 直读 → socket → su files → su cache）。

验证：force-stop 冷启动后触发一轮，70s 完成，**零 timeout**：

```
09:53:18.340 [lvl] loaded day=20261109 n=22
09:53:19.052 地图状态 SUCCESS / 09:53:19.759 背包列表 SUCCESS / 09:53:20.465 背包地图 SUCCESS
09:53:21.169 擦亮 SUCCESS / 09:53:21.878 任务列表 SUCCESS
09:53:21 [lvl] 跳过=7 记录=22 day=20261109
09:54:27.222 === task auto done ===
```

剩余失败仅为跨 App 云控任务（`FROM_APP_REJECT` / `ILLEGAL`）与浏览任务（需真实行为），属设计内。

同时补齐 v1.2.8 重建时丢掉的三个界面面板——「游戏任务」开关（常用功能组）、「自动投币」「自动开红包」（瓜分现金红包抽屉组）。v1.2.0 / v1.2.4 / v1.2.6 加这三块时，Java 与 `activity_main.xml` 是一起改的；重建只恢复了引擎类，界面入口整块丢失，导致功能能跑但点不到。本次从 v1.2.7 的构建产物恢复 `MainActivity`（90 个内部类）与完整布局（84 个 id），并把资源 id 从编译期数字还原为 `R.id.*` 符号——数字 id 是按旧 R 表分配的，直接复用会强转成错误控件（首当其冲是 `track` 被解析成 `tvPondWarn`，启动即崩）。

### v1.2.9 追加：圈数挑战日奖自动领取（2026-11-10）

接口定案 `mtop.taobao.idle.treasure.hunt.map.daily.reward`（v1.0，请求仅 `{"fishAdCode","gps"}`，与 map.init 同源）。可领状态在 `map.init` 响应 `data.dailyReward`：`todayHasIssue`（true=今日已发）/`todayNum`（今日币数）/`todayStepCount`/`yesterdayStepCount`（7 天连续判定用）。新增 `FishTaskAuto.maybeClaimDailyReward()` 挂在 doMapRoll 首次 init 之后：`todayHasIssue=true` 或无 `dailyReward` 字段直接跳过；未到点服务端回 FAIL 只落 `[daily-reward]` 日志不进报告。主跑批与 finish 收尾各一次机会，服务端发放幂等，无重复领取面。装机实测：重启 70s 内 `[tasks] 圈数日奖 -> SUCCESS` + `[daily-reward] claimed +100`，当日 100 币自动入账。

### v1.2.9 追加：7天圈数挑战奖励自动领取（2026-11-10）

协议定案：`mtop.idle.interact.cycle.challenge.info` 返回 `tierList`（`{cycle, rewardAmount, status: AWARD/CHALLENGING/LOCKED}` + `recordId`），档位 10 圈 1000 / 18 圈 2000 / 27 圈 5000 / 35 圈 10000；领取走 `mtop.idle.interact.cycle.challenge.reward`（req `{cycle, recordId, fishAdCode, gps}`）。注意这组接口用短命名空间 `mtop.idle.*`，`FishMtopHook` 响应白名单已补齐（此前 34 次请求响应全被丢弃）。

实现三层：`maybeClaimCycleReward()` 挂跑批 mapRoll 后 + finish 收尾；探针 `probeCycleAward()` 发现可领档时切 `claim_only` 迷你跑批（跳过浏览段，20 秒内完成）；本地去重档 `rh_cycle_claim.txt`（`yyMMdd|cycle`，跨天失效）——**服务端领取成功后 status 仍显示 AWARD**（发奖异步），不记档会每轮探针重复领取（实测同档 3 连领后修复）。装机全链路验证：探针 01:51:37 置位 → 20s 内领取成功 → 01:57:11 「今日已领（本地档），跳过」。

待接：`multi.day.challenge.info`（当前活动未开启，响应 `data:{}`）、`challenge.idleCoinSign.info`（挑战期签到，待活动期抓包）。

### v1.2.9 追加：面板接线修复（全量功能测试发现）

面板恢复后做真机全量测试，又暴露两处接线缺口，均已修复：

1. **三个引擎入口没接**。v1.2.7 字节码里 `registerInvoker()` 的初始化序列是
   `FishTaskAuto → RhPondTask → RhOlivertask.autoRunDaily → RhGameTask → RhCashClaim → RhCashJoin → RhOlivertask.autoRunDailyExp`，
   重建源码时中间三个 `autoRunDaily` 调用丢了——引擎类都在，就是没人叫。现象：开关能存（`gameTask`/`cashJoin`/`cashClaim` 均落 `rh_config.json`），但引擎永远不动。已按 v1.2.7 顺序补回 `FishMtopHook.java:349-357`。
2. **报告 Socket 服务缺 6 条指令**。`MainActivity`（恢复自 v1.2.7）会发 `GAME` / `GAME_REPORT` / `JOIN` / `JOIN_REPORT` / `CLAIM` / `CASH_REPORT` / `CASH_STATE`，而 `startReportServer()` 只实现了鱼塘与会员那套。点面板按钮无响应、状态文案恒为「今日未执行（引擎未在线）」。已按 v1.2.7 协议补齐：`CASH_STATE` 回传 `CLAIM|<day|state|…>#JOIN|<day|state|…>`，`#` 前开红包、后投币。

顺手修了一个状态丢失缺陷：`RhCashClaim` / `RhCashJoin` 的状态文件（`rh_cash_claim.txt` / `rh_cash_join.txt`）和三个任务报告原本落在 `getExternalCacheDir()`，**pm install -r 一次整个目录就被清空**（实测更新包后面板状态从「今日已开红包」退回「今日未执行」）。新增 `FishMtopHook.persistDir()`（外部 files，退内部 files），状态与报告主写持久区，读取时回落 cache 兼容旧文件。注意 `dir()`（mtop 响应通道）必须保持 cache/ 不动——`callMtop` 轮询的 `rh_resp_<尾段>.json` 与宿主写盘目录必须同侧，这次差点重蹈 v1.2.8 覆辙（第一版改坏了，真机立即复现 `callMtop timeout`，已回退拆分）。

真机验证（2026-11-09）：

- 游戏任务：点行触发 `GAME` → 引擎跑完 → 报告「88 个游戏 / 59 个任务 / 0 个可做（今日已完成）」；当日自动跑记录 59/59 全成功 +5900 闲鱼币。
- 自动投币：watcher 接管，`TERMINAL done_server`（本期 5/5 已满），面板「今日已投满 ✓」。
- 自动开红包：开奖日窗口内自动领取成功（金额 11），落 done 标记；手动 `CLAIM` 通道同样走通。
- `activity_main.xml` 重新补回全量注释（77 处：文件头结构总览 + 每个抽屉/开关/按钮行内注释），带注释重编译装机界面不变。
- 下载渠道调整：`showDownloadPicker()` 改为 **UC 网盘**（`drive.uc.cn/s/df475a5e0ad94`，国内直连·推荐）→ **夸克网盘**（`pan.quark.cn/s/b0a477c5b50f`）→ **GitHub Releases**（国际源）三项；移除 Gitee 发行版与 123 网盘入口。**更新检测通道不动**：`UpdateChecker` 三源（Gitee raw version.json → jsDelivr → GitHub API）保留，Gitee 仍是第一检测源。`version.json` 的 `cnUrl` 同步改为 UC 网盘（随下次发行 push 生效）。

---

## v1.2.8 · 冷启动标识丢失修复

**2026-11-09**

v1.2.7 的完成标识在「手动杀进程再打开」后失效，7 条僵尸任务重新弹 Toast。日志证据：

```
[lvl] load skip: dir=null
[lvl] load skip: no file /storage/emulated/0/Android/data/com.taobao.idlefish/cache/rh_task_lvl.json
```

根因：存档在 `cache/`，闲鱼冷启动会清空自身 cache 目录（同刻 `rh_tasks_result.txt` 一并消失，`files/` 下落盘日志完好）。空图 → `lvlSkip` 恒 false → 当天重新领一遍。

修复：存档/报告/快照统一迁 `files/`（`getExternalFilesDir`，回落内部 `files/`）；新增 `lvlLoadOnce(ctx)` 懒加载，探针/定时/广播三入口自愈读档；老存档从 `cache/` 自动迁移；`dir=null` / `no file` / `load fail` 三种分支留痕。

验证：冷启动 → `[lvl] loaded day=20261109 n=22` → `[probe] runnable=13`（原 20）→ 签到/玩一玩 `跳过：标识=2` → `领取成功 0`。

---

## v1.2.7 · 任务完成标识

**2026-11-09** ｜ `闲鱼小猪手-1.2.7.apk`

- 做不掉的任务（跨 App 跳转、浏览商品、搜一搜）以前每一轮都要重打十几次必失败的请求，还各弹一次 Toast。现在每条任务挂一个完成度，连续两次没推进当天就不再碰
- 签到与玩一玩单独记，服务端已签就直接打标，不再反复请求
- 报告里多一行 `[lvl] 跳过=N 记录=M day=YYYYMMDD`，跳过时写明原因
- 跨天自动清零，以服务端时间为准，设备时间被改过也不影响

## v1.2.6 · 现金瓜分自动投币

**2026-11-09** ｜ `闲鱼小猪手-1.2.6.apk`

- 新增「现金瓜分自动投币」：按配置份数把闲鱼币投进当期活动，投完整轮不再动
- 投币是花钱动作，所以每一道闸门都写死：未到参与窗口、本期已截止、本期已投满、未投满但入口已关、未绑定支付宝、余额不够、轮次用尽，任一条命中当天就停，报告里写清停在哪一步
- 每轮结果落盘存档，重启不会重复投
- 份数按「目标 - 已投」和「上限 - 已投」取小的算，不会多扣币

## v1.2.5 · 开红包时间窗

**2026-11-09** ｜ `闲鱼小猪手-1.2.5.apk`

- 上一版把「现在还没开奖」和「今天不领了」混成一个判断，开奖前就放弃。现在拆开：没到窗口只记下次检查时间，不算失败
- 连续失败 6 轮才放弃当天，网络抖动不再把额度耗光
- 领完复核服务端是否仍标可领，复核失败不打完成标记，下一轮还会来
- 报告里写明每期的 `可领(时间窗)` / `已开奖` / `开奖失败` / `未知` 状态与原因

## v1.2.4 · 现金夺宝开红包

**2026-11-09** ｜ `闲鱼小猪手-1.2.4.apk`

- 新增「现金夺宝开红包」：把已开奖未领的期自动领掉，金额写进报告
- 期状态分四类，只有「可领」才动手，待开奖 / 已过期 / 已领都跳过
- 领取成功不只看返回码，还核对服务端的开奖状态，避免报了成功实则没到账

## v1.2.2 / v1.2.3 · 重打包

**2026-11-09**

与 v1.2.1 同一份代码，只改打包号，无功能变化。

## v1.2.1 · 未唤醒时自动拉起

**2026-11-08** ｜ `闲鱼小猪手-1.2.1.apk`

- 在模块界面点执行时，如果闲鱼没在跑，先自动打开闲鱼再执行，不用手动切过去
- 拉起失败才提示「先打开闲鱼再点」

## v1.2.0 · 游戏中心任务

**2026-11-08** ｜ `闲鱼小猪手-1.2.0.apk`

- 新增「游戏任务」：闲鱼游戏中心里按时长计的任务可以自动跑（领清单 → 查详情 → 上报完成 → 领奖）
- 等级类、动作类任务进度来自游戏服务端，接口推不动，不做
- 上报即发的任务自动跳过领奖那一步
- 跑完复核一遍，没有待做就打当日完成标记，当晚不再自动执行
- 每一步失败都有单独提示：清单拉取失败、详情查询失败、未判定完成、未发放、领奖异常

---

## v1.1.10 · 每日 500 经验

**2026-10-26** ｜ `闲鱼小猪手-1.1.10.apk`

- 会员任务页顶部「500 每日经验」现在会自动领取。这一项和原来那 4 条会员任务是两套独立体系，之前 4 条全部完成后整轮引擎直接跳过，所以这项永远领不到
- 每日经验单独一条闸门、单独一份记账文件（`rh_oliver_exp.txt`），次日 0 点自动失效，与任务链的完成标记互不影响
- 打开闲鱼 20 秒后先领每日经验，45 秒后再跑 4 条任务，两路错开不抢通道

## v1.1.9 · 去除开屏广告

**2026-10-26** ｜ `闲鱼小猪手-1.1.9.apk`

- 强化功能面板新增「去除开屏广告」：冷启动、热启动都不再出现开屏，点开直接进首页
- 开屏不再发起网络请求，省掉原本 0.5~1.2 秒的等待
- 从外部链接直接打开闲鱼时，原本排在开屏之后才执行的跳转动作照常执行，不会停在首页
- 修复每日任务在部分机型上「自动执行失败后当天就不再重试」的问题，现在失败会自动重试（最多 3 次）
- 修复鱼塘任务在部分机型上响应已被捕获、却仍被判为超时的问题

## v1.1.8 · 不显示桌面图标

**2026-10-25** ｜ `闲鱼小猪手-1.1.8.apk`

- 强化功能新增「不显示桌面图标」开关，隐藏后桌面不再出现图标，界面本身仍可正常打开
- 隐藏状态下可以从 LSPosed 模块页右下角按钮进入界面

## v1.1.7 · 沉浸式

**2026-10-23** ｜ `闲鱼小猪手-1.1.7.apk`

- 主页改为沉浸式：顶部状态栏与底部导航栏转为透明，光斑背景整屏铺满
- 顶部那条奶黄纯色横带消失，不再有硬边色块造成的割裂感
- 状态栏图标改为深色，奶油底上清晰可读
- 标题等内容自动落在状态栏下方，不被遮挡

## v1.1.6 · 背包保存配置

**2026-10-23** ｜ `闲鱼小猪手-1.1.6.apk`

- 背包面板底部新增「保存配置」按钮，改完数量点一下即存，不必先点别处
- 保存成功有明确提示：有变更显示「已保存 N 项：好运骰 3、刮刮卡 -1」，无变更显示「背包配置已保存」
- 点保存时自动收起键盘
- 退到后台时也会兜底保存一次，避免漏存

## v1.1.5 · 分身支持

**2026-10-23** ｜ `闲鱼小猪手-1.1.5.apk`

- 主空间与分身（XSpace / 工作资料）的激活状态彻底分开，主空间激活不再让分身误显示为「已激活」
- 分身环境下的权限状态独立判定，不再出现「没激活却显示已激活」
- 分身的数据目录与日志、报告各自独立，互不覆盖
- 分身环境下 PC 端触发通道使用独立端口，与主空间互不串线

---

## 历史版本

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.1.4 | 2026-10-21 | 首页商品 3/4 列展示（2~4 自选，卡片圆角与字号随列宽自适应）+ UI 比例 40~100 + 报告每行时间戳 + 日志与导出补环境头 + 数字框退后台兜底保存 |
| v1.1.2 | 2026-10-20 | 位置与请求凭证动态化，移除硬编码常量，取不到时省字段提交 |
| v1.1.1 | 2026-10-20 | 背包功能（6 道具，真实掷骰链路推进地图）+ 闲鱼 7.28.60 全任务无响应修复 |
| v1.1.0 | 2026-10-20 | 禁止聊天左滑 + 无水印媒体保存 + 鱼塘任务引擎 |
| v1.0.7 | 2026-10-17 | 今日领取差分账本 + 导出运行日志 + 圈数挑战自动掷骰 |
| v1.0.6 | 2026-10-16 | banner 版本号三态 + Root 权限检测 |
| v1.0.5 | 2026-10-16 | 跑批报告三通道读取 + socket 直触发绕过 MIUI 广播拦截 |
| v1.0.4 | 2026-10-16 | 三渠道更新选择 + 三源检查加速 + 产物更名 |
| v1.0.3 | 2026-10-16 | 任务气泡 Toast + 酷安直达 + PC 触发通道 |
| v1.0.2 | 2026-10-16 | 液态玻璃 UI 重构（三页横滑 + 果冻 pill 底栏 + 自绘控件） |
| v1.0.1 | 2026-10-16 | 酷安署名 + 闲鱼 7.25.31 适配 |
| v1.0 | 2026-10-16 | 首发版 |
