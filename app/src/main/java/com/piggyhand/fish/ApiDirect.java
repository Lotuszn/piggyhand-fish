package com.piggyhand.fish;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * 猪手同款直调通道：不走 mtopsdk 借壳，直接调闲鱼自己的高层协议栈
 *   XModuleCenter.moduleForProtocol(PApiContext) -> sender
 *   ApiProtocol.newInstance().apiNameAndVersion(api, ver).paramMap(map)
 *   sender.send(request, null)
 * 登录态/签名/wua 全部由闲鱼自己的协议栈处理。
 * 响应复用全局 MtopFinishEvent 监听落盘（rh_resp_<api>.json）。
 *
 * 调用链逆向自 com.skyhand.hookhand（闲鱼猪手 1.3.9）c.ub.ۥ()。
 */
public final class ApiDirect {

    private ApiDirect() {}

    /** 尝试直调；成功返回 true（响应由全局监听落盘），失败返回 false（调用方回落借壳） */
    public static boolean send(ClassLoader hostCl, String api, String version, String jsonParams) {
        try {
            /* 1. sender = XModuleCenter.moduleForProtocol(PApiContext.class) */
            Class<?> xmc = Reflect.findClass("com.taobao.idlefish.xmc.XModuleCenter", hostCl);
            Class<?> papi = Reflect.findClass("com.taobao.idlefish.protocol.net.PApiContext", hostCl);
            Object sender = Reflect.callStaticMethod(xmc, "moduleForProtocol", papi);
            if (sender == null) {
                log("[direct] moduleForProtocol -> null");
                return false;
            }

            /* 2. req = new ApiProtocol().apiNameAndVersion(api, version) */
            Class<?> apiCls = Reflect.findClass("com.taobao.idlefish.protocol.net.api.ApiProtocol", hostCl);
            Object apiObj = Reflect.newInstance(apiCls);
            Object req = null;
            for (Method m : apiCls.getMethods()) {
                if ("apiNameAndVersion".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    req = m.invoke(apiObj, api, version);
                    break;
                }
            }
            if (req == null) {
                log("[direct] apiNameAndVersion not found on " + apiCls.getName());
                return false;
            }

            /* 3. req.paramMap(params)（链式，返回值可能是新对象）
             *    猪手 c_ub：p8==null 时整段跳过不调 paramMap（请求不带 data）。
             *    实测 coin.fishing.sold 带 data={} 会 FAIL_SYS_API_NOT_FOUNDED，无参则正常——对齐猪手。 */
            Map<String, Object> params = toMap(jsonParams);
            if (!params.isEmpty()) {
                try {
                    for (Method m : req.getClass().getMethods()) {
                        if ("paramMap".equals(m.getName()) && m.getParameterTypes().length == 1
                                && Map.class.isAssignableFrom(m.getParameterTypes()[0])) {
                            Object r2 = m.invoke(req, params);
                            if (r2 != null) req = r2;
                            break;
                        }
                    }
                } catch (Throwable t) {
                    log("[direct] paramMap fail: " + t);
                }
            }

            /* 4. sender.send(req, null) —— 猪手也允许 callback 为 null */
            for (Method m : sender.getClass().getMethods()) {
                if ("send".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.invoke(sender, req, null);
                    log("[direct] sent: " + api + " v" + version + " via " + sender.getClass().getName());
                    return true;
                }
            }
            log("[direct] send method not found on " + sender.getClass().getName());
            return false;
        } catch (Throwable t) {
            log("[direct] FAIL: " + t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String json) {
        Map<String, Object> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.get(k);
                // org.json 的 Boolean/Integer 与 fastjson 参数兼容；JSONArray 等复杂值直接透传
                out.put(k, v);
            }
        } catch (Throwable t) {
            log("[direct] params json parse fail: " + t);
        }
        return out;
    }

    private static void log(String msg) {
        XposedBridge.log("[RewardHook] " + msg);
        RhFileLog.write(msg);
    }
}
