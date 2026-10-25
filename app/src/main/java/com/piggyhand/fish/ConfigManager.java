package com.piggyhand.fish;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

public class ConfigManager {

    /** 代码内置的默认配置（兜底）。改配置优先用模块 App 界面或 /sdcard 文件。 */
    public static final String DEFAULT_JSON = "{\n" +
            "  \"enabled\": true,\n" +
            "  \"autoRun\": true,\n" +
            "  \"autoRunIntervalMin\": 240,\n" +
            "  \"targets\": [\"com.taobao.idlefish\"],\n" +
            "  \"mtopHook\": true,\n" +
            "  \"toastNotify\": true,\n" +
            "  \"polish\": true,\n" +
            "  \"scout\": true,\n" +
            "  \"scoutPattern\": \"reward|bonus|coin\",\n" +
            "  \"maxScoutMethods\": 400,\n" +
            "  \"adSdkPresets\": true,\n" +
            "  \"noSplashAd\": true,\n" +
            "  \"rules\": [\n" +
            "    {\n" +
            "      \"comment\": \"侦察完成后按日志填规则。示例:\",\n" +
            "      \"class\": \"com.example.target.app.AdTaskActivity\",\n" +
            "      \"method\": \"onRewardVerify\",\n" +
            "      \"action\": \"return_true\"\n" +
            "    }\n" +
            "  ]\n" +
            "}\n";

    public boolean enabled = true;
    public boolean autoRun = true;
    /** 自动跑批兜底间隔（分钟）。0 = 不限（探测节拍内能跑就跑）；上限 1440（24h）。 */
    public int autoRunIntervalMin = 240;
    public boolean scout = true;
    public boolean adSdkPresets = true;
    /** 🚫 去除开屏广告（RhSplashAd 掐断 SplashAdController 入口，连接口都不请求）；默认开 */
    public boolean noSplashAd = true;
    public boolean mtopHook = true;
    public boolean toastNotify = true;
    public boolean polish = true;
    /** 🎣 无水印保存（RhFishFloat 悬浮钮+媒体下载）总开关，模块 UI「强化功能」抽屉可切 */
    public boolean fishMedia = true;
    /** 🐟 鱼塘任务：打开闲鱼自动跑一次（AWARD 收割+签到气泡）；手动 POND 触发不受此开关限制 */
    public boolean pond = true;
    /** 🏅 会员任务：打开闲鱼自动跑一次（会员经验值四条任务 上报+领取）；手动 OLIVER 触发不受此开关限制 */
    public boolean oliverTask = true;
    /** 💬 禁用聊天页左滑进对方主页手势（RhChatHook 双层拦截：容器触摸+主页跳转） */
    public boolean noChatSwipe = false;
    /** 🖥 不显示桌面图标：禁用 LauncherAlias（桌面图标没了，MainActivity 仍可被显式拉起） */
    public boolean hideIcon = false;
    /* 🎒 背包自动使用（FishTaskAuto.doBagUse）：骰子走 map.roll 地图掷骰，刮刮卡走 prop.use；数量 -1=用到 0 */
    public boolean bagLuckOn = false;    public int bagLuckNum = 0;      // 好运骰 LUCKY_DICE
    public boolean bag8On = false;       public int bag8Num = 0;         // 八面骰 SURFACE8_DICE
    public boolean bag12On = false;      public int bag12Num = 0;        // 十二面骰 SURFACE12_DICE
    public boolean bagRocketOn = false;  public int bagRocketNum = 0;    // 火箭骰 ROCKET_DICE
    public boolean bagDoubleOn = false;  public int bagDoubleNum = 0;    // 双倍骰 DOUBLE_DICE
    public boolean bagGgkOn = false;     public int bagGgkNum = 0;       // 刮刮卡 BENEFIT_GGK
    /** 🖼 显示更多商品：首页瀑布流列数覆盖（RhFeedCols 改 vlayout StaggeredGrid 列数）。
     *  cols = 列数，2~4；2 = 原样（闲鱼默认双列） */
    public boolean feedColsOn = false;
    public int feedCols = 3;
    /** 🖼 UI 比例：按应用覆盖显示密度（RhUiScale 改 Resources.updateConfiguration 的 densityDpi）。
     *  percent = 界面缩放百分比，40~100；100 = 原样 */
    public boolean uiScaleOn = false;
    public int uiScalePercent = 80;
    /** 🐟 鱼塘任务引擎参数：城市区号 + 定位（RhPondTask 用）。空串=不覆盖，走 RhLocationContext 动态捕获 */
    public String pondFishAdCode = "";
    public String pondGps = "";
    public String scoutPattern = "reward|bonus|coin";
    public int maxScoutMethods = 400;

    public final List<String> targets = new ArrayList<>();
    public final List<Rule> rules = new ArrayList<>();

    public static class Rule {
        public String clazz;
        public String method;
        public String action = "log";
        public Object value;
        public String target;
        public String targetClass;
        public Object[] args;
    }

    /** Android 的 userId 步进：uid = userId * 100000 + appId（UserHandle.getUserId 在 SDK stub 里不可见，自己算） */
    public static final int PER_USER_RANGE = 100000;

    /** 当前进程所属 userId：主用户 0 / 分身 999 */
    public static int myUserId() {
        try {
            return android.os.Process.myUid() / PER_USER_RANGE;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 任意 uid 所属的 userId */
    public static int userIdOf(int uid) {
        return uid / PER_USER_RANGE;
    }

    /**
     * 当前进程所属 Android 用户的存储根：主用户 0 → /storage/emulated/0，分身 → /storage/emulated/999。
     * 2026-10-23 多用户修复：原实现全程硬编码 /storage/emulated/0，在分身里读写的是主用户的数据
     * （账号串号、配置互相覆盖）。所有跨进程路径统一走这里解析。
     */
    public static String storageRoot() {
        return "/storage/emulated/" + myUserId();
    }

    /** 闲鱼外部目录（files / cache），按当前用户解析 */
    public static String fishExternal(String sub) {
        return storageRoot() + "/Android/data/com.taobao.idlefish/" + sub;
    }

    /** 引擎覆盖配置文件：闲鱼外部私有目录 files/ 下，闲鱼进程必可读；su 双写落这里 */
    public static String overridePath() {
        return fishExternal("files") + "/rh_config.json";
    }

    /**
     * 报告服务端口 = 27715 + userId。
     * 2026-10-23 多用户修复：127.0.0.1 回环端口是全设备共享、不按用户隔离的。
     * 固定 27715 时，主用户与分身的闲鱼各起一份报告服务会抢同一端口，
     * 后绑定的 ServerSocket 直接抛异常，且 App 可能连上"另一个用户"的引擎。
     */
    public static int reportPort() {
        return 27715 + myUserId();
    }

    /** 配置读取优先级（2026-10-19 调整）：闲鱼私有覆盖文件（模块 App su 双写，实时最新） -> 模块 App SharedPreferences(XSharedPreferences，LSPosed 快照可能滞后) -> 代码内置默认。sdcard 公共区被闲鱼 Scoped Storage 拦（canRead=false 实测），弃用 */
    public static ConfigManager load() {
        // 1) 覆盖文件通道：模块 App persistSilent 时经 su 双写，实时最新
        final String OVERRIDE_PATH = overridePath(); // 按当前用户解析（分身 → /storage/emulated/999）
        try {
            File f = new File(OVERRIDE_PATH);
            if (f.exists() && f.canRead() && f.length() > 0) {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String json = sb.toString().trim();
                ConfigManager c = parse(json, OVERRIDE_PATH);
                if (c != null) return c;
                // 容错（v1.1.4）：su 并发写入可能多出尾部残字（实测出现过结尾 "}}"），
                // 导致 JSONObject 解析失败 → 依次回退到前一个 '}' 再试，最多退 3 次
                for (int i = 0, cut = json.length(); i < 3; i++) {
                    cut = json.lastIndexOf('}', cut - 1);
                    if (cut <= 0) break;
                    c = parse(json.substring(0, cut + 1), OVERRIDE_PATH + "#repair" + i);
                    if (c != null) break;
                }
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }

        // 2) XSharedPreferences（LSPosed 快照，可能滞后）
        try {
            XSharedPreferences sp = new XSharedPreferences("com.piggyhand.fish", "config");
            sp.reload();
            String json = sp.getString("config_json", null);
            if (json != null && json.trim().length() > 0) {
                return parse(json, "XSharedPreferences");
            }
        } catch (Throwable t) {
            try {
                XposedBridge.log("[RewardHook] xSharedPreferences read fail: " + t);
            } catch (Throwable ignored) {
                // 模块自身进程无 Xposed 环境（NoClassDefFoundError 也是 Throwable）
            }
        }

        return parse(DEFAULT_JSON, "builtin");
    }

    public static ConfigManager parse(String json, String from) {
        ConfigManager c = new ConfigManager();
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", true);
            c.autoRun = o.optBoolean("autoRun", true);
            // 间隔分钟制（2026-10-19 v1.0.8 UI 改版）：新键 autoRunIntervalMin；
            // 旧配置只有 autoRunIntervalHours 时按小时*60 兼容换算
            int min = o.optInt("autoRunIntervalMin", -1);
            if (min < 0) min = Math.max(1, o.optInt("autoRunIntervalHours", 4)) * 60;
            c.autoRunIntervalMin = Math.min(1440, Math.max(0, min));
            c.scout = o.optBoolean("scout", true);
            c.adSdkPresets = o.optBoolean("adSdkPresets", true);
            c.noSplashAd = o.optBoolean("noSplashAd", true);
            c.mtopHook = o.optBoolean("mtopHook", true);
            c.toastNotify = o.optBoolean("toastNotify", true);
            c.fishMedia = o.optBoolean("fishMedia", true);
            c.pond = o.optBoolean("pond", true);
            c.oliverTask = o.optBoolean("oliverTask", true);
            c.noChatSwipe = o.optBoolean("noChatSwipe", false);
            c.hideIcon = o.optBoolean("hideIcon", false);
            c.bagLuckOn = o.optBoolean("bagLuckOn", false);       c.bagLuckNum = o.optInt("bagLuckNum", 0);
            c.bag8On = o.optBoolean("bag8On", false);             c.bag8Num = o.optInt("bag8Num", 0);
            c.bag12On = o.optBoolean("bag12On", false);           c.bag12Num = o.optInt("bag12Num", 0);
            c.bagRocketOn = o.optBoolean("bagRocketOn", false);   c.bagRocketNum = o.optInt("bagRocketNum", 0);
            c.bagDoubleOn = o.optBoolean("bagDoubleOn", false);   c.bagDoubleNum = o.optInt("bagDoubleNum", 0);
            c.bagGgkOn = o.optBoolean("bagGgkOn", false);         c.bagGgkNum = o.optInt("bagGgkNum", 0);
            c.feedColsOn = o.optBoolean("feedColsOn", false);
            c.feedCols = Math.max(2, Math.min(4, o.optInt("feedCols", 3)));
            c.uiScaleOn = o.optBoolean("uiScaleOn", false);
            c.uiScalePercent = Math.max(40, Math.min(100, o.optInt("uiScalePercent", 80)));
            c.pondFishAdCode = o.optString("pondFishAdCode", "");
            c.pondGps = o.optString("pondGps", "");
            // 1.1.2 迁移：旧版内置默认值（重庆）视为未配置，改走动态定位
            if ("500107".equals(c.pondFishAdCode)) c.pondFishAdCode = "";
            if ("29.524374,106.471423".equals(c.pondGps)) c.pondGps = "";
        c.polish = o.optBoolean("polish", true);
            c.scoutPattern = o.optString("scoutPattern", "reward|bonus|coin");
            c.maxScoutMethods = o.optInt("maxScoutMethods", 400);

            JSONArray tg = o.optJSONArray("targets");
            if (tg != null) {
                for (int i = 0; i < tg.length(); i++) c.targets.add(tg.getString(i));
            }

            JSONArray rs = o.optJSONArray("rules");
            if (rs != null) {
                for (int i = 0; i < rs.length(); i++) {
                    JSONObject ro = rs.getJSONObject(i);
                    Rule r = new Rule();
                    r.clazz = ro.has("class") ? ro.getString("class") : null;
                    r.method = ro.has("method") ? ro.getString("method") : null;
                    r.action = ro.optString("action", "log");
                    r.value = ro.has("value") ? ro.get("value") : null;
                    r.target = ro.has("target") ? ro.getString("target") : null;
                    r.targetClass = ro.has("targetClass") ? ro.getString("targetClass") : null;
                    if (ro.has("args")) {
                        JSONArray arr = ro.getJSONArray("args");
                        r.args = new Object[arr.length()];
                        for (int j = 0; j < arr.length(); j++) r.args[j] = arr.get(j);
                    }
                    c.rules.add(r);
                }
            }

            try {
                XposedBridge.log("[RewardHook] config loaded from " + from
                        + " | targets=" + c.targets + " | rules=" + c.rules.size()
                        + " | scout=" + c.scout);
            } catch (Throwable ignored) {
                // 模块自身进程无 Xposed 环境
            }
            return c;
        } catch (Throwable t) {
            try {
                XposedBridge.log("[RewardHook] config parse fail from " + from + ": " + t);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
