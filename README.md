# 咸鱼小猪手 (PiggyHand Fish)

LSPosed(Xposed) 模块 —— 闲鱼 App 任务自动化引擎。注入闲鱼进程后，通过闲鱼自有 mtop 协议栈直调接口，无需电脑、无需抓包改机，打开闲鱼即可自动完成每日任务领取奖励。

> 包名：`com.piggyhand.fish` · 适用闲鱼 7.25.31 · 仅供学习研究协议机制使用

## 功能

- **任务链自动化**：拉取任务列表 → 逐个触发 → 领奖，全流程协议直调（queryolivertasklist / uploadolivertasktrigger / getolivertaskbenefit）
- **浏览任务纯协议完成**：nextfresh 拉推荐商品池，逐个上报 implId，不真的打开商品页
- **每日签到** `coin.signin`、**擦亮宝贝** `item.polish` v2.0、**小店日福利** `startup.shop.daily.benefit`
- **寻宝投注链**：lottery feeds → bet → AWARD → claim
- **4 小时间隔自动跑批**：检测到闲鱼启动时，距上次跑完超过设定间隔即自动开始（无需手动触发）
- **规则引擎**：JSON 配置驱动的通用 hook 规则（return_true / invoke / log），改配置不用重新编译
- **侦察模式**：正则扫描目标 APK 类名，全类方法挂日志钩子，用于逆向定位
- **广告 SDK 预设钩子**：穿山甲 / 优量汇 / 快手激励视频回调一键挂日志
- **报告通道**：通知 BigText + 本地 Socket(127.0.0.1:27715) + 广播 + 落盘文件四级回传

## 原理

不碰 mtopsdk，直接借闲鱼自己的协议栈发送请求：

```
XModuleCenter.moduleForProtocol(PApiContext.class)   // 从闲鱼服务注册表拿发送器
  → new ApiProtocol().apiNameAndVersion(api, version).paramMap(params)
  → sender.send(req, null)                            // 登录态/签名/wua 全由闲鱼处理
```

发送器实现类为 `com.taobao.android.remoteobject.easy.MtopLauncher`。响应统一由全局 `MtopFinishEvent` 构造器 hook 捕获落盘。

## 构建

无 Gradle 依赖的手动编译链（javac → d8 → aapt2 → apksigner）：

```bash
# 需自备三件套，通过环境变量传入：
#   ANDROID_JAR   android-34 平台 android.jar
#   BUILD_TOOLS   build-tools 目录（含 aapt2、apksigner.jar）
#   R8_JAR        r8.jar（用其 D8 入口，规避 build-tools 自带 d8 的双层匿名类 NPE bug）
ANDROID_JAR=/path/to/android.jar \
BUILD_TOOLS=/path/to/build-tools \
R8_JAR=/path/to/r8.jar \
bash build-apk.sh
```

产物：`RewardHook.apk`（约 292K）。仓库内 `app/build.gradle` 等为 Gradle 骨架，供 Android Studio 用户自行接入（需将 stub 替换为 `libxposed-api` 或 XposedBridge.jar 依赖）。

## 安装使用

1. 安装 APK，在 LSPosed 管理器启用「咸鱼小猪手」，作用域勾选「闲鱼」
2. 强停闲鱼后重新打开，模块随闲鱼进程注入
3. 打开模块 App 配置开关（跑批间隔默认 4 小时）
4. 手动触发（可选，PC 连接时）：

```bash
adb shell am broadcast -a com.piggyhand.fish.MTOP_CALL --es api __RUN_TASKS__
adb shell am broadcast -a com.piggyhand.fish.MTOP_CALL --es api __GET_RESULT__
```

## 协议接口清单

模块内已验证可直调的 mtop 接口（均经闲鱼自有协议栈发送，SUCCESS）：

| 接口 | 版本 | 用途 |
|------|------|------|
| mtop.taobao.idle.task.queryolivertasklist | 2.0 | 拉任务列表（sceneId=496） |
| mtop.taobao.idle.task.uploadolivertasktrigger | 1.0 | 触发任务 |
| mtop.taobao.idle.task.getolivertaskbenefit | 1.0 | 领取任务奖励 |
| mtop.taobao.idlehome.idle.coin.nextfresh | 1.0 | 推荐商品池（浏览任务） |
| mtop.taobao.idle.coin.signin | 1.0 | 每日签到 |
| mtop.taobao.idle.item.polish | 2.0 | 擦亮宝贝（type=originaljson） |
| mtop.taobao.idle.startup.shop.daily.benefit | 1.0 | 小店日福利 |
| mtop.taobao.idle.coin.lottery.feeds / bet | 1.0 | 寻宝投注链 |

## 项目结构

```
app/src/main/java/com/piggyhand/fish/
├── MainHook.java        # Xposed 入口，规则引擎 + 侦察 + 配置分发
├── FishMtopHook.java    # mtop 请求/响应全局 hook + 广播直调通道 + 报告服务器
├── FishTaskAuto.java    # 任务自动化引擎（签到/任务/擦亮/小店/夺宝/浏览链）
├── ApiDirect.java       # 闲鱼协议栈直调封装（MtopLauncher 借壳）
├── ConfigManager.java   # JSON 配置加载（XSharedPreferences）
├── MainActivity.java    # 模块配置界面（开关式）
└── Reflect.java         # 反射工具库
```

## 免责声明

本项目仅供学习研究 Android 逆向工程与 Xposed 框架机制，请勿用于任何商业用途或违反平台服务条款的场景。使用本模块产生的一切后果由使用者自行承担。项目与闲鱼/阿里巴巴无任何关联。
