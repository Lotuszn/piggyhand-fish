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
            "  \"autoRunIntervalHours\": 4,\n" +
            "  \"targets\": [\"com.taobao.idlefish\"],\n" +
            "  \"mtopHook\": true,\n" +
            "  \"toastNotify\": true,\n" +
            "  \"scout\": true,\n" +
            "  \"scoutPattern\": \"reward|bonus|coin\",\n" +
            "  \"maxScoutMethods\": 400,\n" +
            "  \"adSdkPresets\": true,\n" +
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
    public int autoRunIntervalHours = 4;
    public boolean scout = true;
    public boolean adSdkPresets = true;
    public boolean mtopHook = true;
    public boolean toastNotify = true;
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

    /** 配置读取优先级：模块 App 保存的 SharedPreferences -> /sdcard/RewardHook/config.json -> 代码内置默认 */
    public static ConfigManager load() {
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

        try {
            File f = new File("/sdcard/RewardHook/config.json");
            if (f.exists() && f.canRead()) {
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                return parse(sb.toString(), "/sdcard/RewardHook/config.json");
            }
        } catch (Throwable ignored) {
        }

        return parse(DEFAULT_JSON, "builtin");
    }

    public static ConfigManager parse(String json, String from) {
        ConfigManager c = new ConfigManager();
        try {
            JSONObject o = new JSONObject(json);
            c.enabled = o.optBoolean("enabled", true);
            c.autoRun = o.optBoolean("autoRun", true);
            c.autoRunIntervalHours = Math.max(1, o.optInt("autoRunIntervalHours", 4));
            c.scout = o.optBoolean("scout", true);
            c.adSdkPresets = o.optBoolean("adSdkPresets", true);
            c.mtopHook = o.optBoolean("mtopHook", true);
            c.toastNotify = o.optBoolean("toastNotify", true);
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
